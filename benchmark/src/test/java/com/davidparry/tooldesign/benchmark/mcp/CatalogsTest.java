package com.davidparry.tooldesign.benchmark.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogsTest {

    private static final JsonMapper json = JsonMapper.builder().build();

    /**
     * A snapshot's tools, read back as MCP tools the way a client receives them, hash to the snapshot's own hash. That
     * holds for the endpoint snapshot the endpoint server wrote and the workflow snapshot the runner wrote, so both
     * sides canonicalize the same way.
     */
    @ParameterizedTest
    @ValueSource(strings = { "endpoint", "workflow" })
    void aSnapshotsToolsHashToTheHashItRecords(String arm) throws Exception {
        Path file = Path.of("..", "fixtures", "catalogs", arm + "-tools.json");
        Map<String, Object> snapshot = json.readValue(Files.readString(file), new TypeReference<Map<String, Object>>() {
        });
        List<McpSchema.Tool> tools = json.convertValue(snapshot.get("tools"), new TypeReference<List<McpSchema.Tool>>() {
        });

        assertThat(Catalogs.sha256(tools)).isEqualTo(snapshot.get("sha256")).isEqualTo(Catalogs.snapshotHash(file));
    }
}
