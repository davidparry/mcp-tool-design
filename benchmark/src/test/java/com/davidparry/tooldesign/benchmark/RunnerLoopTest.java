package com.davidparry.tooldesign.benchmark;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import tools.jackson.databind.json.JsonMapper;

import com.davidparry.tooldesign.benchmark.app.AdminClient;
import com.davidparry.tooldesign.benchmark.config.BenchProperties;
import com.davidparry.tooldesign.benchmark.config.BenchProperties.Limits;
import com.davidparry.tooldesign.benchmark.config.BenchProperties.ModelSpec;
import com.davidparry.tooldesign.benchmark.mcp.Catalogs;
import com.davidparry.tooldesign.benchmark.model.ModelGateway;
import com.davidparry.tooldesign.benchmark.run.Conversation;
import com.davidparry.tooldesign.benchmark.run.RunExecutor;
import com.davidparry.tooldesign.benchmark.run.RunRecord;
import com.davidparry.tooldesign.benchmark.run.RunSpec;
import com.davidparry.tooldesign.benchmark.run.TaskSpec;
import com.davidparry.tooldesign.benchmark.run.Tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.assertj.core.groups.Tuple.tuple;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The runner's loop against the real app and both servers, with a scripted model in place of an LLM. */
class RunnerLoopTest {

    private static final String C1_PREVIEW = """
            {"orderReference":"1042","itemDescription":"medium dog harness","replacementSize":"large",
             "deliverBy":"2026-11-13","costLimit":{"amount":"15.00","comparison":"under"}}""";

    private static final String C1_CARD = """
            {"message":"One option fits: Columbus by ground, arriving November 12, for $11.99.",
             "outcome":"options_available","order_reference":"1042",
             "options":[{"ships_from":"Columbus, OH","method":"GROUND","arrives_by":"2026-11-12",
                         "additional_cost":"11.99","meets_all_constraints":true,"quote_reference":"EQ-5001"}]}""";

    private static Stack stack;

    private static final JsonMapper json = JsonMapper.builder().build();

    private static TaskSpec c1;

    @BeforeAll
    static void startStack() throws Exception {
        assumeTrue(Stack.built(), "Build the app and both servers first: mvn -pl app,mcp-endpoint,mcp-workflow package");
        stack = new Stack();
        c1 = Tasks.load(Stack.ROOT.resolve("fixtures/tasks"), List.of("dev"), json).stream()
                .filter(t -> t.id().equals("C1")).findFirst().orElseThrow();
    }

    @AfterAll
    static void stopStack() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    void aWorkflowRunRecordsTheTurnsTheCardTheBackendCallsAndTheState() {
        ScriptedChatModel model = new ScriptedChatModel().toolCall("preview_exchange", C1_PREVIEW)
                .toolCall("final_answer", C1_CARD);

        RunRecord record = execute(spec("workflow", c1), model, workflowCatalog());

        assertThat(record.stopReason()).isEqualTo("answered");
        assertThat(record.turns()).hasSize(2);
        RunRecord.ToolCall preview = record.turns().get(0).toolCalls().get(0);
        assertThat(preview.kind()).isEqualTo("mcp");
        assertThat(preview.isError()).isFalse();
        assertThat(preview.resultText()).contains("\"outcome\":\"options_available\"");
        assertThat(record.cards()).singleElement().satisfies(card -> {
            assertThat(card).containsEntry("outcome", "options_available");
            assertThat(card.get("options")).asInstanceOf(LIST)
                    .hasSize(1);
        });
        assertThat(record.backendCalls()).hasSize(12).allSatisfy(call -> assertThat(call).containsEntry("toolCall", 1));
        assertThat(record.stateAfter().get("actions")).asInstanceOf(LIST)
                .isEmpty();
        assertThat(record.totals()).isEqualTo(new RunRecord.Totals(2, 1, 1, 200, 20, 0, 0,
                preview.resultChars(), 12, 0));

        List<String> offered = ((ToolCallingChatOptions) model.prompts().get(0).getOptions()).getToolCallbacks()
                .stream().map(t -> t.getToolDefinition().name()).toList();
        assertThat(offered).containsExactly("preview_exchange", "submit_exchange", "find_orders", "get_order_context",
                "preview_return", "submit_return", "final_answer");
        List<Message> second = model.prompts().get(1).getInstructions();
        assertThat(second).hasSize(4).last().isInstanceOf(ToolResponseMessage.class);
        assertThat(((ToolResponseMessage) second.get(3)).getResponses().get(0).responseData())
                .isEqualTo(preview.resultText());
    }

    @Test
    void aPlainReplyGetsOneReminderAndASecondEndsTheMessageWithoutACard() {
        ScriptedChatModel model = new ScriptedChatModel().reply("Order 1042 was delivered on October 28.")
                .reply("Order 1042 was delivered on October 28.");

        RunRecord record = execute(spec("workflow", c1), model, workflowCatalog());

        assertThat(record.stopReason()).isEqualTo("missing_card");
        assertThat(record.turns()).extracting(RunRecord.Turn::reminded).containsExactly(true, null);
        assertThat(record.cards()).containsExactly((Map<String, Object>) null);
        assertThat(model.prompts().get(1).getInstructions()).last()
                .satisfies(m -> assertThat(m.getText()).isEqualTo(Conversation.REMINDER));
        assertThat(record.totals().reminders()).isEqualTo(1);
    }

    @Test
    void unknownToolsAndBadArgumentsComeBackAsErrorsWithoutReachingTheServer() {
        ScriptedChatModel model = new ScriptedChatModel()
                .toolCalls(new String[] { "lookup_order", "{\"reference\":\"1042\"}" },
                        new String[] { "get_order", "reference=1042" })
                .toolCall("final_answer", "{\"message\":\"I couldn't look that up.\",\"outcome\":\"other\"}");

        RunRecord record = execute(spec("endpoint", c1), model, endpointCatalog());

        assertThat(record.turns().get(0).toolCalls()).extracting(RunRecord.ToolCall::kind, RunRecord.ToolCall::isError)
                .containsExactly(tuple("unknown_tool", true),
                        tuple("bad_arguments", true));
        assertThat(record.backendCalls()).isEmpty();
        assertThat(record.totals().mcpCalls()).isZero();
        assertThat(record.stopReason()).isEqualTo("answered");
    }

    @Test
    void aScenarioChangesTheWorldBeforeItsFollowUpAndTheRecordShowsWhatTheSubmitFound() {
        TaskSpec scenario = new TaskSpec("S-test", "scenario", "submission", "exchange", null, c1.prompt(),
                List.of(new TaskSpec.FollowUp(List.of(new TaskSpec.WorldChange(31, null)),
                        "Yes, go ahead with the Columbus option.")),
                0, null, Map.of());
        ScriptedChatModel model = new ScriptedChatModel().toolCall("preview_exchange", C1_PREVIEW)
                .toolCall("final_answer", C1_CARD)
                .toolCall("submit_exchange", "{\"quoteId\":\"EQ-5001\"}")
                .toolCall("final_answer", "{\"message\":\"That quote expired.\",\"outcome\":\"submission_failed\"}");

        RunRecord record = execute(spec("workflow", scenario), model, workflowCatalog());

        assertThat(record.stopReason()).isEqualTo("answered");
        assertThat(record.worldChanges()).singleElement()
                .satisfies(change -> assertThat(change.beforeSegment()).isEqualTo(1));
        assertThat(record.turns()).extracting(RunRecord.Turn::segment).containsExactly(0, 0, 1, 1);
        assertThat(record.turns().get(2).toolCalls().get(0).resultText()).contains("\"outcome\":\"quote_expired\"");
        assertThat(record.cards()).hasSize(2);
        assertThat(record.stateAfter().get("actions")).asInstanceOf(LIST)
                .isEmpty();
    }

    @Test
    void aSessionListingADifferentCatalogStopsTheRunBeforeTheModelIsCalled() {
        ScriptedChatModel model = new ScriptedChatModel().toolCall("final_answer", C1_CARD);

        RunRecord record = execute(spec("workflow", c1), model, "not-the-frozen-catalog");

        assertThat(record.stopReason()).isEqualTo("setup_error");
        assertThat(record.errors()).singleElement().asString().contains("not-the-frozen-catalog");
        assertThat(model.prompts()).isEmpty();
    }

    @Test
    void theToolCallLimitStopsARunThatKeepsCalling() {
        ScriptedChatModel model = new ScriptedChatModel();
        for (int i = 0; i < 5; i++) {
            model.toolCall("get_order", "{\"reference\":\"1042\"}");
        }

        RunRecord record = execute(spec("endpoint", c1), model, endpointCatalog(), new Limits(10, 3, 0, Duration.ZERO));

        assertThat(record.stopReason()).isEqualTo("tool_call_limit");
        assertThat(record.totals().toolCalls()).isEqualTo(3);
        assertThat(record.backendCalls()).hasSize(3);
    }

    @Test
    void aRecordNeverCarriesAnApiKey() {
        ScriptedChatModel model = new ScriptedChatModel().toolCall("get_order", "{\"reference\":\"1042\"}")
                .toolCall("final_answer", "{\"message\":\"Delivered October 28.\",\"outcome\":\"order_status\"}");

        String line = json.writeValueAsString(execute(spec("endpoint", c1), model, endpointCatalog()));

        assertThat(line).doesNotContain("support-agent-dev-key", "admin-dev-key", "frontend-dev-key", "X-Api-Key");
    }

    @Test
    void aCardThatBreaksItsSchemaIsSentBackAndTheCorrectedOneIsKept() {
        ScriptedChatModel model = new ScriptedChatModel().toolCall("preview_exchange", C1_PREVIEW)
                .toolCall("final_answer", "{\"message\":\"One option fits.\",\"options\":\"[{ships_from: CMH}]\"}")
                .toolCall("final_answer", C1_CARD);

        RunRecord record = execute(spec("workflow", c1), model, workflowCatalog());

        assertThat(record.stopReason()).isEqualTo("answered");
        assertThat(record.turns().get(1).toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.kind()).isEqualTo("bad_arguments");
            assertThat(call.isError()).isTrue();
            assertThat(call.resultText()).contains("not_delivered", "required property 'outcome' not found",
                    "/options: string found, array expected");
        });
        assertThat(record.cards()).singleElement()
                .satisfies(card -> assertThat(card).containsEntry("outcome", "options_available"));
    }

    private static RunRecord execute(RunSpec spec, ScriptedChatModel model, String catalog) {
        return execute(spec, model, catalog, new Limits(10, 20, 0, Duration.ZERO));
    }

    private static RunRecord execute(RunSpec spec, ScriptedChatModel model, String catalog, Limits limits) {
        BenchProperties properties = new BenchProperties(stack.appUrl(), "admin-dev-key",
                Map.of("endpoint", stack.endpointUrl(), "workflow", stack.workflowUrl()),
                Map.of("scripted", modelSpec()), limits, Stack.ROOT.resolve("fixtures").toString(),
                Path.of("target", "results").toString(), "verify");
        RunExecutor executor = new RunExecutor(new AdminClient(properties, json), properties, json);
        return executor.execute(spec, "test", "test", new ModelGateway("scripted", modelSpec(), model), catalog);
    }

    private static RunSpec spec(String arm, TaskSpec task) {
        return new RunSpec(RunSpec.id("test", "scripted", task.id(), arm, 1) + "-" + System.nanoTime(), "scripted",
                arm, task, 1, 1, 7);
    }

    private static ModelSpec modelSpec() {
        return new ModelSpec("ollama", "scripted", null, null, null, null, null, null, null, null, null);
    }

    private static String workflowCatalog() {
        return Catalogs.snapshotHash(Stack.ROOT.resolve("fixtures/catalogs/workflow-tools.json"));
    }

    private static String endpointCatalog() {
        return Catalogs.snapshotHash(Stack.ROOT.resolve("fixtures/catalogs/endpoint-tools.json"));
    }
}
