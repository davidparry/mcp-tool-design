package com.davidparry.tooldesign.benchmark.verify;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import com.davidparry.tooldesign.benchmark.run.RunRecord;
import com.davidparry.tooldesign.benchmark.run.TaskSpec;
import com.davidparry.tooldesign.benchmark.run.Tasks;

import static org.assertj.core.api.Assertions.assertThat;

class FrontendStepsTest {

    private static Map<String, TaskSpec> tasks;

    @BeforeAll
    static void loadTasks() {
        tasks = Tasks.load(Path.of("..", "fixtures", "tasks"), List.of("dev", "heldout", "scenario"),
                JsonMapper.builder().build()).stream().collect(Collectors.toMap(TaskSpec::id, Function.identity()));
    }

    @Test
    void quotingEveryOptionAfterTheStockCheckMatchesTheScreens() {
        FrontendSteps steps = steps("C1", new Records()
                .call("GET", "/api/orders/1042/items/1042-1/exchange-eligibility", null, 200)
                .call("GET", "/api/inventory", "sku=TRB-L-BLU", 200)
                .exchange("1042-1", "TRB-L-BLU", "WH-CMH", "GROUND").exchange("1042-1", "TRB-L-BLU", "WH-CMH", "EXPRESS")
                .exchange("1042-1", "TRB-L-BLU", "WH-RNO", "GROUND").exchange("1042-1", "TRB-L-BLU", "WH-RNO", "EXPRESS")
                .card(null).card("options_available"));

        assertThat(steps.screenCalls()).isEqualTo(12);
        assertThat(steps.eligibilityChecked()).isTrue();
        assertThat(steps.stockChecked()).isTrue();
        assertThat(steps.optionsQuoted()).isEqualTo(4);
        assertThat(steps.optionsOnScreen()).isEqualTo(4);
        assertThat(steps.fitsNotQuoted()).isZero();
        assertThat(steps.refusedCalls()).isZero();
        assertThat(steps.unaskedQuotes()).isZero();
        assertThat(steps.cardOutcome()).isEqualTo("options_available");
    }

    @Test
    void skippingTheStockCheckAndAWarehouseLeavesTheFitUnquoted() {
        FrontendSteps steps = steps("C1", new Records()
                .call("GET", "/api/orders/1042/items/1042-1/exchange-eligibility", null, 200)
                .call("GET", "/api/inventory", "sku=TRB-L-RED", 200)
                .exchange("1042-1", "TRB-L-BLU", "WH-RNO", "GROUND").exchange("1042-1", "TRB-L-BLU", "WH-RNO", "EXPRESS"));

        assertThat(steps.stockChecked()).isFalse();
        assertThat(steps.optionsQuoted()).isEqualTo(2);
        assertThat(steps.fitsNotQuoted()).isEqualTo(1);
    }

    @Test
    void refusedCallsAndQuotesForAColorNobodyAskedForAreCounted() {
        FrontendSteps steps = steps("C1", new Records()
                .call("GET", "/api/delivery-options", "warehouseId=WH-ORD&postalCode=78704", 404)
                .call("POST", "/api/exchange-quotes", null, 409)
                .exchange("1042-1", "TRB-L-RED", "WH-CMH", "GROUND"));

        assertThat(steps.refusedCalls()).isEqualTo(2);
        assertThat(steps.unaskedQuotes()).isEqualTo(1);
        assertThat(steps.optionsQuoted()).isZero();
        assertThat(steps.backendCalls()).isEqualTo(2);
    }

    @Test
    void aReturnIsTheSearchTheOrderTheEligibilityCheckAndTheRefundQuote() {
        FrontendSteps steps = steps("B1", new Records().quote("RETURN", "1019-1", null, null, null));

        assertThat(steps.screenCalls()).isEqualTo(5);
        assertThat(steps.eligibilityChecked()).isFalse();
        assertThat(steps.stockChecked()).isNull();
        assertThat(steps.unaskedQuotes()).isZero();
        assertThat(steps.cardOutcome()).isEmpty();
    }

    @Test
    void wordsThatFitTwoItemsHaveNoSinglePathToCompareWith() {
        FrontendSteps steps = steps("H2", new Records().quote("RETURN", "1022-1", null, null, null));

        assertThat(steps.screenCalls()).isNull();
        assertThat(steps.eligibilityChecked()).isNull();
        assertThat(steps.unaskedQuotes()).isNull();
    }

    @Test
    void anIneligibleItemStopsTheScreensAtTheEligibilityCheck() {
        FrontendSteps steps = steps("G3", new Records()
                .call("GET", "/api/orders/1039/items/1039-1/exchange-eligibility", null, 200));

        assertThat(steps.screenCalls()).isEqualTo(4);
        assertThat(steps.eligibilityChecked()).isTrue();
        assertThat(steps.stockChecked()).isNull();
    }

    private static FrontendSteps steps(String taskId, Records records) {
        TaskSpec task = tasks.get(taskId);
        RunRecord run = new RunRecord("test-" + taskId, "test", "test", "scripted", "endpoint", taskId, task.split(),
                task.category(), task.kind(), 1, 1, 7, Instant.now(), Instant.now(), 10, "2025-11-25", "sha", "sha",
                task.messages(), List.of(), List.of(), records.cards, "answered", List.of(),
                new RunRecord.Totals(1, 0, 0, 100, 10, 0, 0, 0, records.calls.size(), 0), records.calls,
                Map.of("quotes", records.quotes));
        return FrontendSteps.of(run, task);
    }

    /** The app's call log and the quotes the store holds after a run. */
    private static final class Records {

        private final List<Map<String, Object>> calls = new ArrayList<>();

        private final List<Map<String, Object>> quotes = new ArrayList<>();

        private final List<Map<String, Object>> cards = new ArrayList<>();

        Records call(String method, String path, String query, int status) {
            Map<String, Object> call = new HashMap<>();
            call.put("seq", this.calls.size() + 1);
            call.put("caller", "support-agent");
            call.put("method", method);
            call.put("path", path);
            call.put("query", query);
            call.put("status", status);
            this.calls.add(call);
            return this;
        }

        Records exchange(String itemId, String replacementSku, String warehouseId, String method) {
            return quote("EXCHANGE", itemId, replacementSku, warehouseId, method);
        }

        Records quote(String type, String itemId, String replacementSku, String warehouseId, String method) {
            Map<String, Object> quote = new HashMap<>();
            quote.put("quoteId", "Q-" + (this.quotes.size() + 1));
            quote.put("type", type);
            quote.put("itemId", itemId);
            quote.put("replacementSku", replacementSku);
            quote.put("warehouseId", warehouseId);
            quote.put("deliveryMethod", method);
            this.quotes.add(quote);
            return this;
        }

        Records card(String outcome) {
            this.cards.add(outcome == null ? null : Map.of("message", "...", "outcome", outcome));
            return this;
        }
    }
}
