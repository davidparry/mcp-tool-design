package com.davidparry.tooldesign.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.davidparry.tooldesign.endpoint.OpenApiToolGenerator.Operation;

/**
 * The committed snapshot is the catalog generated from the committed OpenAPI document. The app's own tests check
 * that document against what the app serves, so a changed API description fails one of the two.
 */
class CatalogSnapshotTest {

    @Test
    void theSnapshotIsWhatTheGeneratorProducesFromTheDocument() throws IOException {
        List<Map<String, Object>> catalog = ToolCatalog.describe(OpenApiToolGenerator
                .generate(OpenApiToolGeneratorTest.document()).stream().map(Operation::tool).toList());
        Path snapshot = Path.of("..", "fixtures", "catalogs", "endpoint-tools.json");
        assertThat(ToolCatalog.snapshotHash(snapshot))
                .as("Refresh %s by starting mcp-endpoint with endpoint.catalog.mode=write", snapshot)
                .isEqualTo(ToolCatalog.sha256(catalog));
    }
}
