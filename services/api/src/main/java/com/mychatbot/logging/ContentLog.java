package com.mychatbot.logging;

import com.mychatbot.knowledge.SensitiveData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded, escaped content previews with conservative secret redaction. */
public final class ContentLog {
    public static final String REQUEST = ContentLog.class.getName() + ".request";
    public record Request(String id, String requestedAt, String userId, String method, String url, String clientIp) {}
    private static final Logger LOG = LoggerFactory.getLogger(ContentLog.class);
    private static final int LIMIT = 4000;
    private ContentLog() {}

    public static void request(String kind, Request request, String provider, String content) {
        LOG.info("[{}] {}_request requestId={} provider={} requestedAt={} userId={} method={} url={} clientIp={} content=\"{}\"",
                preview(request.id()), kind, preview(request.id()), preview(provider), preview(request.requestedAt()),
                preview(request.userId()), preview(request.method()), preview(request.url()), preview(request.clientIp()), preview(content));
    }

    public static String preview(String content) {
        String safe = content == null ? "" : SensitiveData.guard(content) ? SensitiveData.mask(content) : "[REDACTED]";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < safe.length(); i++) {
            char c = safe.charAt(i);
            String escaped = switch (c) {
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                case '\\' -> "\\\\";
                case '"' -> "\\\"";
                default -> Character.isISOControl(c) || c == '\u2028' || c == '\u2029'
                        ? " " : String.valueOf(c);
            };
            if (result.length() + escaped.length() > LIMIT) return result + "...[truncated]";
            result.append(escaped);
        }
        return result.toString();
    }

    public static final class Response {
        private final String kind;
        private final Request request;
        private final String provider;
        private final StringBuilder content = new StringBuilder();
        private boolean truncated;
        private boolean finished;

        public Response(String kind, Request request, String provider) {
            this.kind = kind;
            this.request = request;
            this.provider = provider;
        }

        public synchronized void append(String text) {
            if (text == null || finished) return;
            // Keep extra context so masking runs before the visible preview is shortened.
            int remaining = LIMIT * 2 - content.length();
            content.append(text, 0, Math.min(text.length(), remaining));
            truncated |= text.length() > remaining;
        }

        public synchronized void finish(String outcome, String code) {
            if (finished) return;
            finished = true;
            String text = preview(content.toString());
            LOG.info("[{}] {}_response requestId={} provider={} outcome={} code={} requestedAt={} userId={} method={} url={} clientIp={} content=\"{}{}\"",
                    preview(request.id()), kind, preview(request.id()), preview(provider), preview(outcome), preview(code),
                    preview(request.requestedAt()), preview(request.userId()), preview(request.method()),
                    preview(request.url()), preview(request.clientIp()), text,
                    truncated && !text.endsWith("...[truncated]") ? "...[truncated]" : "");
        }
    }
}
