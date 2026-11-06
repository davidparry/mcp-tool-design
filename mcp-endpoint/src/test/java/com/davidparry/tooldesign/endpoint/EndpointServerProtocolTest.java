package com.davidparry.tooldesign.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Talks to the generated server over MCP, the way the benchmark does, with a stub standing in for the API. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndpointServerProtocolTest {

    static final StubApi API = new StubApi();

    @DynamicPropertySource
    static void api(DynamicPropertyRegistry registry) {
        registry.add("endpoint.api.base-url", API::baseUrl);
        registry.add("endpoint.api.key", () -> "test-agent-key");
    }

    @AfterAll
    static void stopApi() {
        API.stop();
    }

    @LocalServerPort
    int port;

    McpSyncClient client;

    @BeforeEach
    void connect() {
        API.requests.clear();
        this.client = McpClient
                .sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + this.port).endpoint("/mcp")
                        .build())
                .requestTimeout(Duration.ofSeconds(10)).build();
        this.client.initialize();
    }

    @AfterEach
    void disconnect() {
        this.client.closeGracefully();
    }

    @Test
    void theServerListsTheTwelveGeneratedTools() {
        assertThat(this.client.listTools().tools()).extracting(Tool::name).hasSize(12).contains("get_order",
                "create_exchange_quote", "submit_exchange_quote");
    }

    @Test
    void aToolResultIsTheRestBodyByteForByte() {
        CallToolResult result = this.client.callTool(new CallToolRequest("get_order", Map.of("reference", "1042")));
        assertThat(result.isError()).isFalse();
        assertThat(((TextContent) result.content().getFirst()).text()).isEqualTo(StubApi.ORDER_BODY);
        assertThat(API.requests).singleElement().satisfies(request -> {
            assertThat(request.path()).isEqualTo("/api/orders/1042");
            assertThat(request.apiKey()).isEqualTo("test-agent-key");
        });
    }

    @Test
    void aConflictComesBackAsAnErrorWithTheProblemDetail() {
        CallToolResult result = this.client
                .callTool(new CallToolRequest("submit_exchange_quote", Map.of("quoteId", "EQ-5001")));
        assertThat(result.isError()).isTrue();
        assertThat(((TextContent) result.content().getFirst()).text()).isEqualTo(StubApi.EXPIRED_BODY);
    }

    @Test
    void bodyArgumentsPassThroughAsTheModelSentThem() {
        CallToolResult result = this.client.callTool(new CallToolRequest("create_exchange_quote",
                Map.of("orderReference", "1042", "itemId", "1042-1", "replacementSku", "TRB-L-BLU", "quantity", 1,
                        "warehouseId", "WH-CMH", "deliveryMethod", "GROUND")));
        assertThat(result.isError()).isFalse();
        assertThat(API.requests).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.body()).contains("\"deliveryMethod\":\"GROUND\"").contains("\"quantity\":1")
                    .doesNotContain("reference\":\"1042\",\"itemId");
        });
    }

    @Test
    void argumentsThatBreakTheSchemaAreRefusedBeforeTheApiIsCalled() {
        CallToolResult result = this.client.callTool(new CallToolRequest("create_exchange_quote",
                Map.of("orderReference", "1042", "itemId", "1042-1", "replacementSku", "TRB-L-BLU", "quantity", 1,
                        "warehouseId", "WH-CMH", "deliveryMethod", "ground")));
        assertThat(result.isError()).isTrue();
        assertThat(((TextContent) result.content().getFirst()).text()).contains("input validation failed")
                .contains("/deliveryMethod");
        assertThat(API.requests).isEmpty();
    }

    @Test
    void queryArgumentsGoInTheQueryStringAndAbsentOnesAreLeftOut() {
        this.client.callTool(new CallToolRequest("search_orders", Map.of("query", "Maya Chen", "size", 5)));
        assertThat(API.requests).singleElement()
                .satisfies(request -> assertThat(request.query()).isEqualTo("query=Maya%20Chen&size=5"));
    }
}
