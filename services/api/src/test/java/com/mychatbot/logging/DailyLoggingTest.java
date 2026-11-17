package com.mychatbot.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.rolling.DefaultTimeBasedFileNamingAndTriggeringPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class DailyLoggingTest {
    @TempDir Path temporary;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private LoggerContext configure() throws Exception {
        var context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        context.putProperty("mychatbot.home", temporary.toRealPath().toString());
        var config = new JoranConfigurator(); config.setContext(context);
        config.doConfigure(Path.of("bin/logback-spring.xml").toFile());
        assertThat(context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).iteratorForAppenders()).hasNext();
        return context;
    }
    private Path active() { return temporary.resolve("logs/root.txt"); }
    private String gunzip(Path path) throws Exception {
        try (var input = new GZIPInputStream(Files.newInputStream(path))) { return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
    }
    private static void log(LoggerContext context, String content) { context.getLogger("daily-log-test").info(content); }
    private void logOnce(String content) throws Exception {
        var context = configure();
        try { log(context, content); } finally { context.stop(); }
    }

    @Test void sameDayRestartAppendsToOneActiveRootFile() throws Exception {
        logOnce("FIRST_START_MARKER");
        logOnce("SECOND_START_MARKER");
        assertThat(Files.readString(active())).contains("FIRST_START_MARKER", "SECOND_START_MARKER");
        try (var files = Files.list(active().getParent())) { assertThat(files.map(path -> path.getFileName().toString()).toList()).containsExactly("root.txt"); }
    }

    @Test void firstEventAfterMidnightCompressesPreviousDayAndKeepsNextDayInRootWithoutAnotherArchive() throws Exception {
        LocalDate today = LocalDate.now();
        var context = configure();
        try {
            @SuppressWarnings("unchecked") var appender = (RollingFileAppender<ILoggingEvent>) context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("FILE");
            @SuppressWarnings("unchecked") var policy = (TimeBasedRollingPolicy<ILoggingEvent>) appender.getRollingPolicy();
            appender.stop(); policy.stop();
            var clock = new DefaultTimeBasedFileNamingAndTriggeringPolicy<ILoggingEvent>();
            clock.setContext(context);
            clock.setCurrentTime(today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            policy.setTimeBasedFileNamingAndTriggeringPolicy(clock); policy.start(); appender.start();
            log(context, "PREVIOUS_DAY_MARKER");
            clock.setCurrentTime(today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).plusMinutes(1).toInstant().toEpochMilli());
            log(context, "NEXT_DAY_MARKER");
            clock.setCurrentTime(today.plusDays(1).atTime(15, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            log(context, "SAME_NEXT_DAY_MARKER");
        } finally { context.stop(); }
        Path archive = temporary.resolve("logs/" + DAY.format(today) + ".txt.gz");
        assertThat(gunzip(archive)).contains("PREVIOUS_DAY_MARKER").doesNotContain("NEXT_DAY_MARKER");
        assertThat(Files.readString(active())).contains("NEXT_DAY_MARKER", "SAME_NEXT_DAY_MARKER").doesNotContain("PREVIOUS_DAY_MARKER");
        assertThat(Files.exists(temporary.resolve("logs/" + DAY.format(today) + ".txt"))).isFalse();
        try (var files = Files.list(active().getParent())) { assertThat(files.map(path -> path.getFileName().toString()).toList()).containsExactlyInAnyOrder("root.txt", DAY.format(today) + ".txt.gz"); }
    }

    @Test void restartUsesPreviousActiveFileDateAndRotatesOnFirstNewDayEvent() throws Exception {
        logOnce("OLD_PROCESS_MARKER");
        LocalDate yesterday = LocalDate.now().minusDays(1);
        Files.setLastModifiedTime(active(), FileTime.from(yesterday.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()));
        logOnce("NEW_PROCESS_MARKER");
        assertThat(gunzip(temporary.resolve("logs/" + DAY.format(yesterday) + ".txt.gz"))).contains("OLD_PROCESS_MARKER").doesNotContain("NEW_PROCESS_MARKER");
        assertThat(Files.readString(active())).contains("NEW_PROCESS_MARKER").doesNotContain("OLD_PROCESS_MARKER");
    }
}
