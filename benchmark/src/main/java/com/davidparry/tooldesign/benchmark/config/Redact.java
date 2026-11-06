package com.davidparry.tooldesign.benchmark.config;

import java.util.regex.Pattern;

/**
 * Removes anything shaped like a credential from text bound for a log line or a run record. Provider errors echo
 * keys back, sometimes masked but with a few real characters left.
 */
public final class Redact {

    private static final Pattern KEYS = Pattern.compile(
            "(sk-[A-Za-z0-9_*-]{8,}|sk-ant-[A-Za-z0-9_*-]+|(?i:bearer)\\s+[A-Za-z0-9._~+/*-]{8,}|"
                    + "(?i:x-api-key|api[_-]?key)[\"']?\\s*[:=]\\s*[\"']?[A-Za-z0-9._*-]{8,})");

    private Redact() {
    }

    public static String secrets(String text) {
        return text == null ? null : KEYS.matcher(text).replaceAll("[redacted]");
    }
}
