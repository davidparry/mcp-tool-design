package com.davidparry.tooldesign.benchmark.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedactTest {

    @Test
    void maskedAndUnmaskedKeysAreRemoved() {
        assertThat(Redact.secrets("401: Incorrect API key provided: sk-proj-************************8cwA. You can"))
                .isEqualTo("401: Incorrect API key provided: [redacted]. You can");
        assertThat(Redact.secrets("key sk-ant-api03-AbC_dEf-123 rejected")).isEqualTo("key [redacted] rejected");
        assertThat(Redact.secrets("sent x-api-key: abcd1234efgh")).isEqualTo("sent [redacted]");
        assertThat(Redact.secrets("Authorization: Bearer abc.def.ghi123")).isEqualTo("Authorization: [redacted]");
    }

    @Test
    void ordinaryTextIsLeftAlone() {
        assertThat(Redact.secrets("invalid x-api-key")).isEqualTo("invalid x-api-key");
        assertThat(Redact.secrets("quote EQ-5001 expired at 2026-11-06T09:30:00-06:00"))
                .isEqualTo("quote EQ-5001 expired at 2026-11-06T09:30:00-06:00");
        assertThat(Redact.secrets(null)).isNull();
    }
}
