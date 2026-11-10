package com.davidparry.tooldesign.benchmark.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Reads {@code KEY=VALUE} lines from a git-ignored {@code .env} into system properties, so API keys can live outside
 * the shell. A variable already in the environment wins. Values are never logged or written to a run record.
 */
public final class DotEnv {

    private static final Map<String, String> KEYS = Map.of("ANTHROPIC_API_KEY", "spring.ai.anthropic.api-key",
            "OPENAI_API_KEY", "spring.ai.openai.api-key");

    private DotEnv() {
    }

    /**
     * Spring reads {@code SPRING_AI_ANTHROPIC_API_KEY} and {@code SPRING_AI_OPENAI_API_KEY} ahead of the placeholders in
     * application.yml, so one left over from another project would be sent instead of the documented key.
     */
    public static void preferDocumentedKeys() {
        preferDocumentedKeys(name -> System.getenv(name) != null ? System.getenv(name) : System.getProperty(name));
    }

    static void preferDocumentedKeys(UnaryOperator<String> lookup) {
        KEYS.forEach((variable, property) -> {
            String value = lookup.apply(variable);
            if (value != null && !value.isBlank()) {
                System.setProperty(property, value);
            }
        });
    }

    public static void load(Path file) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String raw : Files.readAllLines(file)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                    continue;
                }
                if (line.startsWith("export ")) {
                    line = line.substring("export ".length()).strip();
                }
                String key = line.substring(0, line.indexOf('=')).strip();
                String value = unquote(line.substring(line.indexOf('=') + 1).strip());
                if (System.getenv(key) == null && System.getProperty(key) == null) {
                    System.setProperty(key, value);
                }
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException("Can't read " + file, e);
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
