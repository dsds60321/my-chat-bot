package com.mychatbot.logging;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@EnabledOnOs({OS.MAC, OS.LINUX})
class StartScriptLoggingTest {
    @TempDir Path temporary;
    private Path app, logs, fakeHome;
    private final Map<String, String> extra = new HashMap<>();
    record Result(int exit, String output) {}
    @BeforeEach void setup() throws Exception {
        Path base = temporary.toRealPath();
        app = Files.createDirectories(base.resolve("deployment space"));
        logs = Files.createDirectories(app.resolve("logs"));
        Files.createDirectory(app.resolve("bin"));
        for (String name : List.of("start.sh", "stop.sh", "logback-spring.xml")) Files.copy(Path.of("bin", name), app.resolve("bin").resolve(name));
        Files.writeString(app.resolve("e2u-chat-bot.jar"), "synthetic fixture, never run as Java");
        Files.createDirectories(app.resolve("build/libs"));
        Files.writeString(app.resolve("build/libs/other.jar"), "must not override deployed JAR");
        fakeHome = Files.createDirectories(base.resolve("fake-java/bin")).getParent();
        Path java = fakeHome.resolve("bin/java");
        Files.writeString(java, """
                #!/bin/sh
                if [ "$1" = "-version" ]; then exit 0; fi
                printf '%s\\n' "$@" > "$FAKE_JAVA_ARGS"
                echo BOOTSTRAP_MARKER
                if [ "${FAKE_JAVA_FAIL:-}" = 1 ]; then echo simulated-jvm-failure >&2; exit 7; fi
                trap 'exit 0' TERM INT
                while :; do sleep 1; done
                """);
        assertThat(java.toFile().setExecutable(true)).isTrue();
    }
    private Result run(String script) throws Exception {
        Path terminal = Files.createTempFile(temporary, "terminal-", ".txt");
        var process = new ProcessBuilder("/bin/sh", app.resolve("bin").resolve(script).toString())
                .redirectErrorStream(true).redirectOutput(terminal.toFile());
        process.environment().put("JAVA_HOME", fakeHome.toString());
        process.environment().put("FAKE_JAVA_ARGS", app.resolve("jvm-args.txt").toString());
        process.environment().put("TZ", "UTC");
        process.environment().putAll(extra);
        Process child = process.start();
        if (!child.waitFor(15, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new AssertionError("isolated script timed out"); }
        return new Result(child.exitValue(), Files.readString(terminal));
    }
    @AfterEach void cleanup() throws Exception {
        if (app != null && Files.exists(app.resolve(".local/api.pid"))) {
            Result result = run("stop.sh");
            assertThat(result.exit()).as(result.output()).isZero();
        }
    }
    @Test void usesOnlyNamedJarAndPreservesLogsAcrossBackgroundRestart() throws Exception {
        Files.writeString(app.resolve("backup.jar"), "must never execute");
        Files.writeString(logs.resolve("root.txt"), "active application log");
        Files.writeString(logs.resolve("19990101.txt"), "unrelated closed log");
        Files.writeString(logs.resolve("api-19990101-010203-42.log"), "legacy log");
        var started = run("start.sh");
        assertThat(started.exit()).as(started.output()).isZero();
        assertThat(started.output()).contains("root.txt", "BOOTSTRAP_MARKER").doesNotContain("launcher.log");
        long pid = Long.parseLong(Files.readString(app.resolve(".local/api.pid")).trim());
        var signal = new ProcessBuilder("kill", "-HUP", Long.toString(pid)).start();
        assertThat(signal.waitFor()).isZero();
        Thread.sleep(100);
        assertThat(ProcessHandle.of(pid).orElseThrow().isAlive()).isTrue();
        assertThat(Files.readAllLines(app.resolve("jvm-args.txt"))).containsExactly(
                "-Dlogging.config=" + app.resolve("bin/logback-spring.xml"), "-Dmychatbot.home=" + app,
                "-jar", app.resolve("e2u-chat-bot.jar").toString());
        assertThat(run("start.sh").exit()).isEqualTo(1);
        assertThat(run("stop.sh").exit()).isZero();
        var restarted = run("start.sh");
        assertThat(restarted.exit()).as(restarted.output()).isZero();
        assertThat(restarted.output()).contains("BOOTSTRAP_MARKER");
        assertThat(Files.readString(logs.resolve("root.txt"))).isEqualTo("active application log");
        assertThat(Files.readString(logs.resolve("19990101.txt"))).isEqualTo("unrelated closed log");
        assertThat(Files.readString(logs.resolve("api-19990101-010203-42.log"))).isEqualTo("legacy log");
        assertThat(Files.exists(logs.resolve("19990101.txt.gz"))).isFalse();
        assertThat(Files.exists(logs.resolve("launcher.log"))).isFalse();
        assertThat(Files.exists(app.resolve("nohup.out"))).isFalse();
    }

    @Test void missingNamedJarNeverRunsBackupsOrBuildOutputs() throws Exception {
        Files.delete(app.resolve("e2u-chat-bot.jar"));
        Files.writeString(app.resolve("e2u-chat-bot-backup.jar"), "must never execute");
        Files.writeString(logs.resolve("19990101.txt"), "preserve before startup");
        var result = run("start.sh");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.output()).contains("실행할 JAR가 없습니다", app.resolve("e2u-chat-bot.jar").toString());
        assertThat(Files.exists(app.resolve("jvm-args.txt"))).isFalse();
        assertThat(Files.exists(app.resolve(".local/api.pid"))).isFalse();
        assertThat(Files.readString(logs.resolve("19990101.txt"))).isEqualTo("preserve before startup");
        assertThat(Files.readString(app.resolve("build/libs/other.jar"))).isEqualTo("must not override deployed JAR");
    }

    @Test void missingLogConfigAndJvmFailureLeaveNoPidAndShowDiagnosticsInTerminal() throws Exception {
        Files.delete(app.resolve("bin/logback-spring.xml"));
        var missing = run("start.sh");
        assertThat(missing.exit()).isEqualTo(1);
        assertThat(missing.output()).contains("로그 설정 파일이 없습니다");
        assertThat(Files.exists(app.resolve(".local/api.pid"))).isFalse();
        Files.copy(Path.of("bin/logback-spring.xml"), app.resolve("bin/logback-spring.xml"));
        extra.put("FAKE_JAVA_FAIL", "1");
        var failure = run("start.sh");
        assertThat(failure.exit()).isEqualTo(1);
        assertThat(failure.output()).contains("root.txt", "simulated-jvm-failure").doesNotContain("launcher.log");
        assertThat(Files.exists(app.resolve(".local/api.pid"))).isFalse();
        assertThat(Files.exists(logs.resolve("launcher.log"))).isFalse();
        assertThat(Files.exists(app.resolve("nohup.out"))).isFalse();
    }

    @Test void controlLockStillRejectsConcurrentStart() throws Exception {
        Files.createDirectories(app.resolve(".local/api-control.lock"));
        var result = run("start.sh");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.output()).contains("다른 시작 또는 종료 작업");
        assertThat(Files.exists(app.resolve(".local/api.pid"))).isFalse();
    }
}
