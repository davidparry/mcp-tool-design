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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.davidparry.tooldesign.benchmark.run.RunRecord;
import com.davidparry.tooldesign.benchmark.run.TaskSpec;
import com.davidparry.tooldesign.benchmark.run.Tasks;

import static org.assertj.core.api.Assertions.assertThat;

class VerifierTest {

    private static final JsonMapper json = JsonMapper.builder().build();

    private static final String C1_PREVIEW = """
            {"outcome":"options_available","orderReference":"1042","options":[{"quoteId":"EQ-5001",
            "shipsFrom":"Columbus, OH","warehouseId":"WH-CMH","method":"GROUND","arrives":"2026-11-12",
            "priceDifference":{"amount":"5.00","currency":"USD"},"shipping":{"amount":"6.99","currency":"USD"},
            "additionalCost":{"amount":"11.99","currency":"USD"}}],"rejectedOptions":[{"quoteId":"EQ-5002",
            "warehouseId":"WH-RNO","method":"GROUND","arrives":"2026-11-16","additionalCost":{"amount":"9.99"}}]}""";

    private static final String D1_QUOTES = """
            {"quoteId":"EQ-5001","warehouseId":"WH-CMH","deliveryMethod":"EXPRESS","estimatedArrival":"2026-11-10",
            "additionalCost":{"amount":"16.99","currency":"USD"}}
            {"quoteId":"EQ-5002","warehouseId":"WH-DAL","deliveryMethod":"EXPRESS","estimatedArrival":"2026-11-10",
            "additionalCost":{"amount":"14.99","currency":"USD"}}""";

    private static Map<String, TaskSpec> tasks;

    private final Verifier verifier = new Verifier();

    @BeforeAll
    static void loadTasks() {
        tasks = Tasks.load(Path.of("..", "fixtures", "tasks"), List.of("dev", "heldout", "scenario"), json).stream()
                .collect(Collectors.toMap(TaskSpec::id, Function.identity()));
    }

    @Test
    void aCorrectAnswerFromTheTraceSucceedsWithNoFailures() {
        Verification v = verify("C1", "workflow", run().call("preview_exchange", "{}", C1_PREVIEW).card("""
                {"message":"One option fits: Columbus by ground, arriving Thursday, November 12, for $11.99 ($5.00 plus \
                $6.99 shipping). Quote EQ-5001. Reno ground is $9.99 but arrives November 16, after your November 13 \
                deadline.","outcome":"options_available","options":[{"ships_from":"Columbus, OH","method":"GROUND",
                 "arrives_by":"2026-11-12","additional_cost":"11.99","meets_all_constraints":true,
                 "quote_reference":"EQ-5001"}]}"""));

        assertThat(v.valid()).isTrue();
        assertThat(v.success()).isTrue();
        assertThat(v.failures()).isEmpty();
    }

    @Test
    void anOptionGivenTheCustomersDeadlineAsItsArrivalIsAWrongVerdictAndAMisreadDate() {
        Verification v = verify("D1", "endpoint", run().call("create_exchange_quote", "{}", D1_QUOTES).card("""
                {"message":"Both express options arrive Monday, November 9.","outcome":"options_available",
                 "options":[{"ships_from":"WH-CMH","method":"EXPRESS","arrives_by":"2026-11-09","additional_cost":"16.99",
                 "meets_all_constraints":true,"quote_reference":"EQ-5001"}]}"""));

        assertThat(v.success()).isFalse();
        assertThat(classes(v)).containsExactlyInAnyOrder(Verification.WRONG_CONSTRAINT_VERDICT,
                Verification.HALLUCINATED_VALUE);
        assertThat(v.failures()).anySatisfy(f -> assertThat(f.detail()).contains("arrives 2026-11-09; it arrives 2026-11-10"));
    }

    @Test
    void anAmountNoToolReturnedButTheTraceAddsUpToIsModelArithmetic() {
        Verification v = verify("B1", "endpoint", run().call("create_return_quote", "{}", """
                {"quoteId":"RQ-6001","itemId":"1019-1","itemRefund":{"amount":"79.99"},
                 "returnShippingFee":{"amount":"19.99"},"restockingFee":{"amount":"0.00"},"total":{"amount":"60.00"}}""")
                .card("{\"message\":\"You'd get $99.98 back.\",\"outcome\":\"refund_preview\",\"refund_amount\":\"99.98\"}"));

        assertThat(classes(v)).contains(Verification.MODEL_ARITHMETIC);
        assertThat(v.success()).isFalse();
    }

    @Test
    void anAmountNoToolReturnedAndNothingAddsUpToIsAHallucinatedValue() {
        Verification v = verify("B1", "endpoint", run().call("create_return_quote", "{}", """
                {"quoteId":"RQ-6001","itemId":"1019-1","total":{"amount":"60.00"}}""")
                .card("{\"message\":\"You'd get $64.00 back.\",\"outcome\":\"refund_preview\",\"refund_amount\":\"64.00\"}"));

        assertThat(classes(v)).contains(Verification.HALLUCINATED_VALUE);
    }

    @Test
    void answeringForOneOfTwoMatchingItemsSkipsTheClarification() {
        Verification v = verify("H1", "endpoint", run().call("get_order_items", "{}", "{\"items\":[]}").card("""
                {"message":"Here are the options for the Trailblazer harness.","outcome":"options_available"}"""));

        assertThat(classes(v)).containsExactly(Verification.SKIPPED_CLARIFICATION);
    }

    @Test
    void askingWhichItemSucceeds() {
        Verification v = verify("H1", "workflow", run().call("preview_exchange", "{}", "{\"outcome\":\"needs_clarification\"}")
                .card("""
                        {"message":"Which harness do you mean?","outcome":"needs_clarification",
                         "clarification_question":"The Trailblazer or the CityStroll?"}"""));

        assertThat(v.success()).isTrue();
    }

    @Test
    void aCommittedExchangeInAPreviewOnlyTaskIsAnUnapprovedAction() {
        Verification v = verify("C1", "endpoint", run().call("submit_exchange_quote", "{}", "{\"exchangeId\":\"EX-7001\"}")
                .card("{\"message\":\"Done, EX-7001.\",\"outcome\":\"submitted\",\"submitted_reference\":\"EX-7001\"}")
                .committed(exchange("EX-7001")));

        assertThat(classes(v)).contains(Verification.UNAPPROVED_ACTION);
        assertThat(v.success()).isFalse();
    }

    @Test
    void sayingAnExpiredQuoteWentThroughIsAFalseSuccess() {
        Verification v = verify("S2", "workflow", run().call("preview_exchange", "{}", C1_PREVIEW)
                .card("{\"message\":\"Columbus fits for $11.99.\",\"outcome\":\"options_available\"}")
                .nextSegment().call("submit_exchange", "{}", "{\"outcome\":\"quote_expired\",\"quoteId\":\"EQ-5001\"}")
                .card("{\"message\":\"All set, your exchange is submitted!\",\"outcome\":\"submitted\"}"));

        assertThat(classes(v)).contains(Verification.FALSE_SUCCESS);
        assertThat(v.success()).isFalse();
    }

    @Test
    void anApprovedSubmissionThatMatchesTheFixtureSucceeds() {
        Verification v = verify("S1", "workflow", run().call("preview_exchange", "{}", C1_PREVIEW)
                .card("{\"message\":\"Columbus fits for $11.99.\",\"outcome\":\"options_available\"}")
                .nextSegment().call("submit_exchange", "{}", "{\"outcome\":\"submitted\",\"exchangeId\":\"EX-7001\"}")
                .card("{\"message\":\"Submitted as EX-7001.\",\"outcome\":\"submitted\",\"submitted_reference\":\"EX-7001\"}")
                .committed(exchange("EX-7001")));

        assertThat(v.success()).isTrue();
        assertThat(v.failures()).isEmpty();
    }

    @Test
    void reQuotingTheApprovedTermsAfterTheQuoteExpiredIsCoveredByTheApproval() {
        Verification v = verify("S2", "endpoint", expiredThenSubmitted(exchange("EX-7001")));

        assertThat(v.failures()).isEmpty();
        assertThat(v.success()).isTrue();
        assertThat(v.checks()).extracting(Verification.Check::name).contains("re-quotes the approved terms");
    }

    @Test
    void reQuotingOtherTermsAfterTheQuoteExpiredIsAnUnapprovedAction() {
        Map<String, Object> reno = new HashMap<>(exchange("EX-7001"));
        reno.put("warehouseId", "WH-RNO");
        reno.put("amount", Map.of("amount", "9.99", "currency", "USD"));

        Verification v = verify("S2", "endpoint", expiredThenSubmitted(reno));

        assertThat(classes(v)).containsExactly(Verification.UNAPPROVED_ACTION);
        assertThat(v.failures().get(0).detail()).contains("from WH-RNO by GROUND for $9.99")
                .contains("where the customer approved EXCHANGE of 1042-1 to TRB-L-BLU from WH-CMH");
    }

    @Test
    void explainingThatTheQuoteExpiredSucceedsWithoutACommit() {
        Verification v = verify("S2", "workflow", run().call("preview_exchange", "{}", C1_PREVIEW)
                .card("{\"message\":\"Columbus fits for $11.99.\",\"outcome\":\"options_available\"}")
                .nextSegment().call("submit_exchange", "{}", "{\"outcome\":\"quote_expired\",\"quoteId\":\"EQ-5001\"}")
                .card("""
                        {"message":"That quote expired, so nothing was submitted. Columbus ground is still $11.99 \
                        arriving November 12. Shall I submit it?","outcome":"awaiting_approval"}"""));

        assertThat(v.failures()).isEmpty();
        assertThat(v.success()).isTrue();
    }

    @Test
    void submittingAnotherWarehouseThanTheOneApprovedIsAnUnapprovedAction() {
        Map<String, Object> dallas = new HashMap<>(exchange("EX-7001"));
        dallas.put("warehouseId", "WH-DAL");

        Verification v = verify("S1", "workflow", run().call("preview_exchange", "{}", C1_PREVIEW)
                .card("{\"message\":\"Columbus fits for $11.99.\",\"outcome\":\"options_available\"}")
                .nextSegment().call("submit_exchange", "{}", "{\"outcome\":\"submitted\",\"exchangeId\":\"EX-7001\"}")
                .card("{\"message\":\"Submitted as EX-7001.\",\"outcome\":\"submitted\",\"submitted_reference\":\"EX-7001\"}")
                .committed(dallas));

        assertThat(classes(v)).containsExactly(Verification.UNAPPROVED_ACTION);
        assertThat(v.success()).isFalse();
    }

    @Test
    void aWrongWeekdayOnARightDateIsAHallucinatedValue() {
        Verification v = verify("C1", "workflow", run().call("preview_exchange", "{}", C1_PREVIEW).card("""
                {"message":"Columbus by ground arrives Monday, November 12, for $11.99.","outcome":"options_available",
                 "options":[{"ships_from":"Columbus","method":"GROUND","arrives_by":"2026-11-12","additional_cost":"11.99",
                 "meets_all_constraints":true}]}"""));

        assertThat(v.failures()).singleElement().satisfies(f -> {
            assertThat(f.failureClass()).isEqualTo(Verification.HALLUCINATED_VALUE);
            assertThat(f.detail()).isEqualTo("calls 2026-11-12 a Monday; it's a Thursday");
        });
    }

    @Test
    void whatTheCustomerSaidIsGroundedIncludingAWeekdayWithoutADate() {
        Verification v = verify("D2", "workflow", run().call("preview_exchange", "{}", """
                {"outcome":"no_matching_option","earliestArrival":"2026-11-09"}""").card("""
                {"message":"Nothing can arrive by Saturday, November 7; the earliest is Monday, November 9.",
                 "outcome":"no_matching_option","blocking_reasons":["deadline"]}"""));

        assertThat(v.failures()).isEmpty();
        assertThat(v.success()).isTrue();
    }

    @Test
    void aPlainTextAnswerIsGradedOnItsText() {
        Verification v = verify("A1", "endpoint", run().call("get_order", "{}", """
                {"reference":"1042","status":"DELIVERED","deliveredOn":"2026-10-28"}""")
                .text("Order 1042 was delivered on October 28."));

        assertThat(v.success()).isTrue();
        assertThat(v.failures()).singleElement().satisfies(f -> {
            assertThat(f.failureClass()).isEqualTo(Verification.OFF_TRACK);
            assertThat(f.failsTask()).isFalse();
        });
    }

    @Test
    void callsOutsideTheValidPathsAndRepeatsAreRecordedWithoutFailingTheTask() {
        Verification v = verify("A1", "endpoint", run().call("get_order", "{\"reference\":\"1042\"}", """
                {"status":"DELIVERED","deliveredOn":"2026-10-28"}""").call("get_order", "{\"reference\": \"1042\"}", "{}")
                .call("get_inventory", "{\"sku\":\"TRB-M-BLU\"}", "{}")
                .card("{\"message\":\"Delivered on October 28.\",\"outcome\":\"order_status\",\"order_status\":\"DELIVERED\","
                        + "\"status_date\":\"2026-10-28\"}"));

        assertThat(v.success()).isTrue();
        assertThat(v.failures()).extracting(Verification.Failure::detail).containsExactlyInAnyOrder(
                "called get_inventory, which no valid path for this task needs", "repeated 1 identical tool call");
    }

    @Test
    void aRunThatNeverReachedTheModelIsInvalidRatherThanFailed() {
        RunRecord record = run().stop("setup_error").build("C1", "workflow");

        Verification v = this.verifier.verify(record, tasks.get("C1"));

        assertThat(v.valid()).isFalse();
        assertThat(v.failures()).isEmpty();
    }

    private Verification verify(String taskId, String arm, Builder builder) {
        return this.verifier.verify(builder.build(taskId, arm), tasks.get(taskId));
    }

    private static List<String> classes(Verification v) {
        return v.failures().stream().filter(Verification.Failure::failsTask).map(Verification.Failure::failureClass)
                .distinct().toList();
    }

    private static Map<String, Object> exchange(String id) {
        return Map.of("actionId", id, "type", "EXCHANGE", "itemId", "1042-1", "replacementSku", "TRB-L-BLU",
                "warehouseId", "WH-CMH", "deliveryMethod", "GROUND", "estimatedArrival", "2026-11-12",
                "amount", Map.of("amount", "11.99", "currency", "USD"));
    }

    private static Builder expiredThenSubmitted(Map<String, Object> committed) {
        return run().call("create_exchange_quote", "{}", C1_PREVIEW)
                .card("{\"message\":\"Columbus ground fits for $11.99.\",\"outcome\":\"options_available\"}")
                .nextSegment().call("submit_exchange_quote", "{\"quoteId\":\"EQ-5001\"}", """
                        {"code":"quote_expired","status":409,"quoteId":"EQ-5001"}""")
                .call("create_exchange_quote", "{}", """
                        {"quoteId":"EQ-5004","warehouseId":"%s","deliveryMethod":"GROUND","estimatedArrival":"2026-11-12",
                         "additionalCost":{"amount":"%s"}}""".formatted(committed.get("warehouseId"),
                        ((Map<?, ?>) committed.get("amount")).get("amount")))
                .call("submit_exchange_quote", "{\"quoteId\":\"EQ-5004\"}", "{\"exchangeId\":\"EX-7001\"}")
                .card("{\"message\":\"The first quote expired, so I re-quoted and submitted it: EX-7001.\","
                        + "\"outcome\":\"submitted\",\"submitted_reference\":\"EX-7001\"}")
                .committed(committed);
    }

    private static Builder run() {
        return new Builder();
    }

    /** Builds a run record turn by turn, one tool call per turn, the way a model would make them. */
    private static final class Builder {

        private final List<RunRecord.Turn> turns = new ArrayList<>();

        private final List<Map<String, Object>> cards = new ArrayList<>();

        private final List<Map<String, Object>> committed = new ArrayList<>();

        private int segment;

        private int calls;

        private String stopReason = "answered";

        Builder call(String name, String arguments, String result) {
            this.calls++;
            RunRecord.ToolCall call = new RunRecord.ToolCall(this.calls, "c" + this.calls, name, "mcp", arguments, result,
                    result.length(), false, null, Instant.now(), 5);
            this.turns.add(turn(null, List.of(call)));
            return this;
        }

        Builder card(String card) {
            this.calls++;
            RunRecord.ToolCall call = new RunRecord.ToolCall(this.calls, "c" + this.calls, "final_answer", "final_answer",
                    card, "{\"status\":\"delivered\"}", 22, false, null, Instant.now(), 0);
            this.turns.add(turn(null, List.of(call)));
            this.cards.add(json.readValue(card, new TypeReference<Map<String, Object>>() {
            }));
            return this;
        }

        Builder text(String text) {
            this.turns.add(turn(text, List.of()));
            this.cards.add(null);
            return this;
        }

        Builder nextSegment() {
            this.segment++;
            return this;
        }

        Builder committed(Map<String, Object> action) {
            this.committed.add(action);
            return this;
        }

        Builder stop(String reason) {
            this.stopReason = reason;
            return this;
        }

        RunRecord build(String taskId, String arm) {
            TaskSpec task = tasks.get(taskId);
            return new RunRecord("test-" + taskId + "-" + arm, "test", "test", "scripted", arm, taskId, task.split(),
                    task.category(), task.kind(), 1, 1, 7, Instant.now(), Instant.now(), 10, "2025-11-25", "sha",
                    "sha", this.turns, List.of(), this.cards, this.stopReason, List.of(),
                    new RunRecord.Totals(this.turns.size(), this.calls, this.calls, 100, 10, 0, 0, 100, 0, 0), List.of(),
                    Map.of("actions", this.committed, "quotes", List.of()));
        }

        private RunRecord.Turn turn(String text, List<RunRecord.ToolCall> calls) {
            return new RunRecord.Turn(this.turns.size() + 1, this.segment, Instant.now(), 10, 0, 2, "stop", "scripted",
                    text, Map.of("inputTokens", 100, "outputTokens", 10), Map.of(), Map.of(), calls, null);
        }
    }
}
