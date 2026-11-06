package com.davidparry.tooldesign.benchmark.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads {@code KEY=VALUE} lines from a git-ignored {@code .env} into system properties, so API keys can live outside
 * the shell. A variable already in the environment wins. Values are never logged or written to a run record.
 */
public final class DotEnv {

    private DotEnv() {
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
