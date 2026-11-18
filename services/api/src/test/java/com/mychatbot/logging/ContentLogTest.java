package com.mychatbot.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ContentLogTest {
    @Test void masksBeforeTruncationAndEscapesLogLines() {
        String value = ContentLog.preview("질문\n위조\r\t\u001b\u2028 " + "가".repeat(9000));
        assertThat(value).contains("질문\\n위조\\r\\t", "...[truncated]")
                .doesNotContain("private-value", "test@example.com", "\n", "\r", "\t", "\u001b", "\u2028");
        assertThat(ContentLog.preview("password=\"alpha beta gamma")).isEqualTo("[REDACTED]");
        assertThat(value.length()).isLessThanOrEqualTo(4014);
    }

    @Test void truncatedQuotedSecretIsOmittedAsAWhole(CapturedOutput output) {
        var response = new ContentLog.Response("ai", new ContentLog.Request("id", "time", "u", "POST", "/api/chat/stream", "127.0.0.1"), "mock");
        response.append("password=\"first " + "private-value ".repeat(1000) + "\"");
        response.finish("done", "-");
        assertThat(output.getOut()).contains("[REDACTED]...[truncated]")
                .doesNotContain("first", "private-value");
    }

    @Test void combinesSplitSecretsCapsBuffersAndLogsOnlyOnce(CapturedOutput output) {
        var response = new ContentLog.Response("ai", new ContentLog.Request("id", "time", "u", "POST", "/api/chat/stream", "127.0.0.1"), "mock");
        response.append("prefix Bear");
        response.append("er private-credential ");
        response.append("답".repeat(100000));
        response.append("not-retained");
        response.finish("cancel", "-");
        response.finish("done", "-");
        assertThat(output.getOut()).contains("ai_response requestId=id provider=mock outcome=cancel", "[REDACTED]", "[truncated]")
                .doesNotContain("private-credential", "not-retained", "outcome=done");
        assertThat(output.getOut().length()).isLessThan(4500);
    }
}
