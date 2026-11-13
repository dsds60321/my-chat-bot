package com.mychatbot.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class SensitiveDataTest {
    @Test void rejectsAndMasksCredentialsPersonalIdsAndContactData() {
        for (String sample : new String[] { "api_key: synthetic_value", "API Key: synthetic-only-123",
            "| ID | Password |\n| --- | --- |\n| one | |\n| two | |\n| three | synthetic-only-123 |", "비밀번호: synthetic-only-123", "<password>synthetic-only-123</password>",
            "| ID | Password |\n| test | synthetic-only-123 |",
            "Row 1: A=ID | B=Password |\nRow 2: A=test | B=synthetic-only-123 |",
            "password: \"synthetic value with spaces\"", "password=x",
            "ID\tPassword\nsynthetic-user\tsynthetic-only-123",
            "| Account No |\n| --- |\n| 123-456-7890 |", "password=synthetic_value", "Bearer synthetic.value.123",
            "-----BEGIN PRIVATE KEY-----\nSYNTHETIC\n-----END PRIVATE KEY-----", "account_no=123-456-7890",
            "900101-1234567", "010-1234-5678", "synthetic@example.invalid" }) {
            assertThat(SensitiveData.guard(sample)).as("unsafe sample is rejected").isFalse();
            assertThat(SensitiveData.mask(sample)).contains("[REDACTED]");
        }
        assertThat(SensitiveData.guard("비밀번호 재설정 방법을 설명해 주세요.")).isTrue();
        assertThat(SensitiveData.guard("MCHT 테이블의 mchtId 컬럼은 VARCHAR 형식이다.")).isTrue();
    }

    @Test void onlyAnExactServiceCardIdBypassesTheUnlabeledResidentNumberRule() {
        String internal = "SVCCRD_A9001011234567B0123456789";
        assertThat(SensitiveData.guard(internal + "를 확인해 주세요.")).isTrue();
        assertThat(SensitiveData.mask(internal)).isEqualTo(internal);

        for (String malformed : new String[] { "X" + internal, internal + "F", internal.substring(0, internal.length() - 1),
                internal.replace("SVCCRD_", "OTHER_"), internal.replace("9001011234567", "900101-1234567"),
                internal.replace("9001011234567", "900101 1234567") }) {
            assertThat(SensitiveData.guard(malformed)).isFalse();
            assertThat(SensitiveData.mask(malformed)).contains("[REDACTED]");
        }

        for (String separate : new String[] { "9001011234567", "주민등록번호: 900101-1234567", "password=synthetic",
                "주민등록번호: " + internal, "SVCCRD_A01012345678BCDEF12345678" }) {
            String text = internal + " " + separate;
            assertThat(SensitiveData.guard(text)).isFalse();
            String masked = SensitiveData.mask(text);
            assertThat(masked).startsWith(internal + " ");
            assertThat(masked.substring(internal.length() + 1)).contains("[REDACTED]").doesNotContain(separate);
        }
    }
}
