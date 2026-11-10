package com.davidparry.tooldesign.benchmark.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class DotEnvTest {

    @TempDir
    Path dir;

    @AfterEach
    void clear() {
        System.clearProperty("DOTENV_TEST_PLAIN");
        System.clearProperty("DOTENV_TEST_QUOTED");
        System.clearProperty("DOTENV_TEST_EXPORTED");
        System.clearProperty("spring.ai.anthropic.api-key");
        System.clearProperty("spring.ai.openai.api-key");
    }

    @Test
    void theDocumentedKeysWinOverSpringsOwnVariables() {
        DotEnv.preferDocumentedKeys(Map.of("ANTHROPIC_API_KEY", "documented-anthropic")::get);

        assertThat(System.getProperty("spring.ai.anthropic.api-key")).isEqualTo("documented-anthropic");
        assertThat(System.getProperty("spring.ai.openai.api-key")).isNull();
    }

    @Test
    void readsKeysSkipsCommentsAndStripsQuotes() throws Exception {
        Path file = this.dir.resolve(".env");
        Files.writeString(file, """
                # hosted model keys
                DOTENV_TEST_PLAIN=one
                DOTENV_TEST_QUOTED="two words"
                export DOTENV_TEST_EXPORTED='three'

                not a setting
                """);

        DotEnv.load(file);

        assertThat(System.getProperty("DOTENV_TEST_PLAIN")).isEqualTo("one");
        assertThat(System.getProperty("DOTENV_TEST_QUOTED")).isEqualTo("two words");
        assertThat(System.getProperty("DOTENV_TEST_EXPORTED")).isEqualTo("three");
    }

    @Test
    void theEnvironmentWins() throws Exception {
        Path file = this.dir.resolve(".env");
        Files.writeString(file, "PATH=/nowhere\n");

        DotEnv.load(file);

        assertThat(System.getProperty("PATH")).isNull();
    }

    @Test
    void aMissingFileIsFine() {
        DotEnv.load(this.dir.resolve("absent.env"));
    }
}
