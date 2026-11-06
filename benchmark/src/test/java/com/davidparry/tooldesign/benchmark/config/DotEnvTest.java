package com.davidparry.tooldesign.benchmark.config;

import java.nio.file.Files;
import java.nio.file.Path;

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
