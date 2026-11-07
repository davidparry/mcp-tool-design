package com.davidparry.tooldesign.benchmark.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class RecordFileTest {

    @Test
    void aResumedBatchRunsSetupErrorsAgainButNotModelErrors(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("runs.jsonl");
        Files.writeString(file, """
                {"runId":"final-gpt-C1-endpoint-r1","stopReason":"setup_error"}
                {"runId":"final-gpt-C1-workflow-r1","stopReason":"model_error"}
                {"runId":"final-gpt-E1-endpoint-r1","stopReason":"answered"}
                """);

        assertThat(new RecordFile(file, JsonMapper.builder().build()).finished())
                .containsExactly("final-gpt-C1-workflow-r1", "final-gpt-E1-endpoint-r1");
    }
}
