package com.mychatbot.chat;

import com.mychatbot.ai.AiException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RequestLimiter {
    private static final long WINDOW_NANOS = 60_000_000_000L;
    private static final long SECOND_NANOS = 1_000_000_000L;
    private final Map<String, Window> windows = new HashMap<>();
    private final int maximum;
    private final LongSupplier nanoTime;
    @Autowired
    public RequestLimiter(@Value("${pet.api.requests-per-minute:12}") int maximum) { this(maximum, System::nanoTime); }
    RequestLimiter(int maximum, LongSupplier nanoTime) {
        this.maximum = Math.max(1, maximum);
        this.nanoTime = nanoTime;
    }
    public synchronized Lease acquire(String user) {
        long now = nanoTime.getAsLong();
        windows.entrySet().removeIf(entry -> !entry.getValue().active && now - entry.getValue().start >= WINDOW_NANOS);
        Window window = windows.computeIfAbsent(user, ignored -> new Window(now));
        if (window.active) throw new AiException("REQUEST_IN_PROGRESS", "진행 중인 요청을 취소하거나 완료한 뒤 다시 시도해 주세요.");
        if (window.count >= maximum) {
            long remaining = WINDOW_NANOS - (now - window.start);
            int seconds = (int) Math.ceilDiv(remaining, SECOND_NANOS);
            throw new AiException("LOCAL_RATE_LIMITED", "미니펫 서버의 분당 요청 한도에 도달했어요.", seconds);
        }
        window.count++;
        window.active = true;
        return new Lease(window);
    }
    private static final class Window {
        final long start;
        int count;
        boolean active;
        Window(long start) { this.start = start; }
    }
    public final class Lease implements AutoCloseable {
        private final Window window;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Lease(Window window) { this.window = window; }
        public void close() {
            if (closed.compareAndSet(false, true)) synchronized (RequestLimiter.this) { window.active = false; }
        }
    }
}
