package com.davidparry.tooldesign.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The OpenAPI document is the endpoint MCP server's only input, so its operations are the tool catalog. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("benchmark")
class OpenApiDocumentTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Test
    void theDocumentHasTheTwelveOperationsAndNoAdminPaths() throws Exception {
        String body = this.mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
        JsonNode doc = this.json.readTree(body);
        List<String> operationIds = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        doc.get("paths").properties().forEach(path -> {
            paths.add(path.getKey());
            path.getValue().properties().forEach(op -> operationIds.add(op.getValue().get("operationId").asString()));
        });
        assertThat(operationIds).containsExactlyInAnyOrder("search_orders", "get_order", "get_order_items",
                "get_product_variants", "check_exchange_eligibility", "check_return_eligibility", "get_inventory",
                "get_delivery_options", "create_exchange_quote", "create_return_quote", "submit_exchange_quote",
                "submit_return_quote");
        assertThat(paths).allMatch(path -> path.startsWith("/api/"));
    }

    @Test
    void theEndpointServersCopyOfTheDocumentIsCurrent() throws Exception {
        String body = this.mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
        Path copy = Path.of("..", "mcp-endpoint", "src", "test", "resources", "openapi", "order-support-api.json");
        assertThat(this.json.readTree(Files.readString(copy)))
                .as("The API's description changed. Refresh %s from /v3/api-docs, then the endpoint catalog snapshot",
                        copy)
                .isEqualTo(this.json.readTree(body));
    }

    @Test
    void quoteOperationsSayWhatTheyDontDo() throws Exception {
        String body = this.mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
        JsonNode quote = this.json.readTree(body).at("/paths/~1api~1exchange-quotes/post");
        assertThat(quote.get("description").asString())
                .contains("doesn't commit an exchange, reserve stock, or charge the customer");
    }
}
