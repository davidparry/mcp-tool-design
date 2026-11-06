package com.davidparry.tooldesign.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The six tools over MCP against the real app, checked against the app's own state and call log. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkflowServerTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static AppProcess app;

    @DynamicPropertySource
    static void api(DynamicPropertyRegistry registry) throws Exception {
        assumeTrue(AppProcess.jarExists(), "Build the app jar first: mvn -pl app package");
        app = new AppProcess();
        registry.add("workflow.api.base-url", app::baseUrl);
    }

    @AfterAll
    static void stopApp() {
        if (app != null) {
            app.stop();
        }
    }

    @LocalServerPort
    int port;

    McpSyncClient client;

    @BeforeEach
    void connect() throws Exception {
        app.reset();
        this.client = McpClient
                .sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + this.port).endpoint("/mcp")
                        .build())
                .requestTimeout(Duration.ofSeconds(30)).build();
        this.client.initialize();
    }

    @AfterEach
    void disconnect() {
        this.client.closeGracefully();
    }

    Map<String, Object> call(String tool, Map<String, Object> arguments) {
        CallToolResult result = this.client.callTool(new CallToolRequest(tool, arguments));
        assertThat(result.isError()).as("%s failed: %s", tool, result.content()).isNotEqualTo(Boolean.TRUE);
        return JSON.convertValue(result.structuredContent(), new TypeReference<Map<String, Object>>() {
        });
    }

    static Map<String, Object> c1() {
        return Map.of("orderReference", "1042", "itemDescription", "medium dog harness", "replacementSize", "large",
                "deliverBy", "2026-11-13", "costLimit", Map.of("amount", "15.00", "comparison", "under"));
    }

    @Test
    void theCatalogIsSixToolsWithSideEffectsDescribed() {
        List<Tool> tools = this.client.listTools().tools();
        assertThat(tools).extracting(Tool::name).containsExactlyInAnyOrder("find_orders", "get_order_context",
                "preview_exchange", "preview_return", "submit_exchange", "submit_return");
        Map<String, Tool> byName = tools.stream().collect(java.util.stream.Collectors.toMap(Tool::name, t -> t));
        assertThat(byName.get("get_order_context").annotations().readOnlyHint()).isTrue();
        assertThat(byName.get("preview_exchange").annotations().readOnlyHint()).isFalse();
        assertThat(byName.get("preview_exchange").annotations().destructiveHint()).isFalse();
        assertThat(byName.get("submit_exchange").annotations().destructiveHint()).isTrue();
        assertThat(byName.get("submit_exchange").annotations().idempotentHint()).isTrue();
        assertThat(tools).allSatisfy(tool -> assertThat(tool.outputSchema()).isNotNull());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theOrder1042PreviewFindsTheOneOptionThatMeetsBothLimits() {
        Map<String, Object> preview = call("preview_exchange", c1());
        assertThat(preview.get("outcome")).isEqualTo("options_available");
        assertThat((Map<String, Object>) preview.get("matchedItem")).containsEntry("itemId", "1042-1");
        List<Map<String, Object>> options = (List<Map<String, Object>>) preview.get("options");
        assertThat(options).singleElement().satisfies(option -> {
            assertThat(option).containsEntry("warehouseId", "WH-CMH").containsEntry("method", "GROUND")
                    .containsEntry("arrives", "2026-11-12")
                    .containsEntry("quoteExpiresAt", "2026-11-06T09:30:00-06:00");
            assertThat((Map<String, Object>) option.get("additionalCost")).containsEntry("amount", "11.99");
        });
        List<Map<String, Object>> rejected = (List<Map<String, Object>>) preview.get("rejectedOptions");
        assertThat(rejected).hasSize(3);
        assertThat(rejected).filteredOn(r -> "WH-RNO".equals(r.get("warehouseId")) && "GROUND".equals(r.get("method")))
                .singleElement().satisfies(r -> assertThat((List<Map<String, Object>>) r.get("misses"))
                        .singleElement().satisfies(m -> assertThat(m).containsEntry("constraint", "deadline")));
        assertThat((String) preview.get("message")).startsWith(
                "1 of 4 quoted options arrives by 2026-11-13 and costs under $15.00. The cheapest is $11.99");
    }

    @Test
    void aPreviewWithoutLimitsSaysNoneWasChecked() {
        Map<String, Object> preview = call("preview_exchange", Map.of("orderReference", "1029", "itemDescription",
                "GPS collar", "replacementSize", "large"));

        assertThat(preview.get("outcome")).isEqualTo("options_available");
        assertThat((String) preview.get("message")).startsWith("No delivery date or cost limit was given, so none "
                + "was checked; all 6 quoted options are listed as matches.");
        assertThat((String) preview.get("nextStep")).startsWith("If the customer gave a delivery date or a cost "
                + "limit, such as \"no extra cost\", call preview_exchange again with it.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPreviewCommitsNothingAndChangesNoStock() throws Exception {
        Map<String, Object> before = app.state();
        call("preview_exchange", c1());
        call("preview_return", Map.of("orderReference", "1019", "itemDescription", "dog bed"));
        Map<String, Object> after = app.state();
        assertThat((List<Object>) after.get("actions")).isEmpty();
        assertThat(after.get("inventory")).isEqualTo(before.get("inventory"));
        assertThat((List<Map<String, Object>>) after.get("quotes")).hasSize(5)
                .allSatisfy(quote -> assertThat(quote).containsEntry("status", "OPEN"));
    }

    @Test
    void theCallLogShowsEveryBackendCallBehindOnePreview() throws Exception {
        app.startRun("one-preview");
        call("preview_exchange", c1());
        List<String> calls = app.calls("one-preview").stream()
                .map(c -> c.get("method") + " " + c.get("path")).toList();
        assertThat(calls).containsExactly("GET /api/orders/1042", "GET /api/orders/1042/items",
                "GET /api/products/TRB/variants", "GET /api/products/RPE/variants",
                "GET /api/orders/1042/items/1042-1/exchange-eligibility", "GET /api/inventory",
                "GET /api/delivery-options", "POST /api/exchange-quotes", "POST /api/exchange-quotes",
                "GET /api/delivery-options", "POST /api/exchange-quotes", "POST /api/exchange-quotes");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAmbiguousDescriptionReturnsTheCandidates() {
        Map<String, Object> preview = call("preview_exchange", Map.of("orderReference", "1037", "itemDescription",
                "medium harness", "replacementSize", "large"));
        assertThat(preview.get("outcome")).isEqualTo("needs_clarification");
        assertThat((List<Map<String, Object>>) preview.get("candidates")).extracting(c -> c.get("itemId"))
                .containsExactly("1037-1", "1037-2");
        Map<String, Object> toy = call("preview_return", Map.of("orderReference", "1022", "itemDescription", "toy"));
        assertThat(toy.get("outcome")).isEqualTo("needs_clarification");
    }

    @Test
    void aMissedDeadlineSaysWhenTheEarliestOptionArrives() {
        Map<String, Object> preview = call("preview_exchange", Map.of("orderReference", "1038", "itemDescription",
                "collar", "replacementSize", "medium", "deliverBy", "2026-11-09"));
        assertThat(preview.get("outcome")).isEqualTo("no_matching_option");
        assertThat(preview.get("blockingReasons")).isEqualTo(List.of("deadline"));
        assertThat(preview.get("earliestArrival")).isEqualTo("2026-11-10");
    }

    @Test
    void anItemThatHasntArrivedIsIneligible() {
        Map<String, Object> preview = call("preview_exchange", Map.of("orderReference", "1039", "itemDescription",
                "jacket", "replacementSize", "medium"));
        assertThat(preview.get("outcome")).isEqualTo("ineligible");
        assertThat(preview.get("ineligible")).isEqualTo(Map.of("reasonCode", "not_delivered", "reason",
                ((Map<?, ?>) preview.get("ineligible")).get("reason"), "estimatedDelivery", "2026-11-10"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void submittingTwiceCommitsOneExchange() throws Exception {
        Map<String, Object> preview = call("preview_exchange", c1());
        String quoteId = (String) ((List<Map<String, Object>>) preview.get("options")).getFirst().get("quoteId");
        assertThat(call("submit_exchange", Map.of("quoteId", quoteId)).get("outcome")).isEqualTo("submitted");
        assertThat(call("submit_exchange", Map.of("quoteId", quoteId)).get("outcome")).isEqualTo("already_submitted");
        assertThat((List<Object>) app.state().get("actions")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anExpiredQuoteIsRefusedWithWhatToDoNext() throws Exception {
        Map<String, Object> preview = call("preview_exchange", c1());
        String quoteId = (String) ((List<Map<String, Object>>) preview.get("options")).getFirst().get("quoteId");
        app.advanceClock(31);
        Map<String, Object> submitted = call("submit_exchange", Map.of("quoteId", quoteId));
        assertThat(submitted.get("outcome")).isEqualTo("quote_expired");
        assertThat((String) submitted.get("nextStep")).contains("preview_exchange again");
        assertThat((List<Object>) app.state().get("actions")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void lookupsAnswerStatusAndRefundQuestions() {
        Map<String, Object> context = call("get_order_context", Map.of("orderReference", "1042"));
        assertThat(context).containsEntry("status", "DELIVERED").containsEntry("deliveredOn", "2026-10-28");
        Map<String, Object> found = call("find_orders", Map.of("query", "Priya Nair"));
        assertThat((List<Map<String, Object>>) found.get("orders")).extracting(o -> o.get("reference"))
                .containsExactly("1031", "1014");
        Map<String, Object> refund = call("preview_return", Map.of("orderReference", "1036", "itemDescription",
                "GPS collar"));
        assertThat(refund.get("outcome")).isEqualTo("refund_preview");
        assertThat((Map<String, Object>) refund.get("refundAmount")).containsEntry("amount", "110.49");
    }
}
