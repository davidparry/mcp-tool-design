package com.davidparry.tooldesign.benchmark.run;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.davidparry.tooldesign.benchmark.app.AdminClient;
import com.davidparry.tooldesign.benchmark.config.BenchProperties;
import com.davidparry.tooldesign.benchmark.config.BenchProperties.Limits;
import com.davidparry.tooldesign.benchmark.config.Redact;
import com.davidparry.tooldesign.benchmark.mcp.Catalogs;
import com.davidparry.tooldesign.benchmark.mcp.McpSession;
import com.davidparry.tooldesign.benchmark.model.DefinitionOnlyTool;
import com.davidparry.tooldesign.benchmark.model.ModelGateway;
import com.davidparry.tooldesign.benchmark.model.ModelReply;
import com.davidparry.tooldesign.benchmark.run.RunRecord.ToolCall;
import com.davidparry.tooldesign.benchmark.run.RunRecord.Turn;
import com.davidparry.tooldesign.benchmark.run.RunRecord.WorldChangeApplied;

/**
 * Runs one task in one arm with one model. The store is reset and the run tagged first, so the app's call log and
 * state belong to this run alone; then the runner drives the conversation turn by turn, runs each tool call through
 * a fresh MCP session, and answers {@code final_answer} itself.
 */
@Component
public class RunExecutor {

    static final String DELIVERED = "{\"status\":\"delivered\"}";

    private final AdminClient admin;

    private final BenchProperties properties;

    private final JsonMapper json;

    private final JsonSchemaValidator validator;

    private final Map<String, Object> cardSchema;

    public RunExecutor(AdminClient admin, BenchProperties properties, JsonMapper json) {
        this.admin = admin;
        this.properties = properties;
        this.json = json;
        this.validator = new DefaultJsonSchemaValidator(json);
        this.cardSchema = json.readValue(Conversation.FINAL_ANSWER_SCHEMA, new TypeReference<Map<String, Object>>() {
        });
    }

    /**
     * @param expectedCatalog the catalog hash the batch was planned with; a session that lists anything else stops
     * the run as a setup error
     */
    public RunRecord execute(RunSpec spec, String batch, String phase, ModelGateway model, String expectedCatalog) {
        Instant started = Instant.now();
        Recorder recorder = new Recorder();
        String protocolVersion = null;
        String catalogSha = null;
        String definitionsSha = null;
        List<Map<String, Object>> cards = new ArrayList<>();
        List<WorldChangeApplied> worldChanges = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String stopReason;
        List<Map<String, Object>> backendCalls = List.of();
        Map<String, Object> stateAfter = null;

        this.admin.reset();
        this.admin.startRun(spec.runId());
        try (McpSession session = McpSession.open(this.properties.servers().get(spec.arm()), this.json)) {
            protocolVersion = session.protocolVersion();
            List<McpSchema.Tool> tools = session.listTools();
            catalogSha = Catalogs.sha256(tools);
            if (expectedCatalog != null && !expectedCatalog.equals(catalogSha)) {
                throw new SetupException("The " + spec.arm() + " server lists catalog " + catalogSha
                        + ", but the batch was planned with " + expectedCatalog);
            }
            List<ToolCallback> definitions = definitions(tools);
            definitionsSha = definitionsSha256(definitions);
            stopReason = converse(spec, model, session, definitions, toolNames(tools), recorder, cards, worldChanges);
        }
        catch (SetupException e) {
            errors.add(e.getMessage());
            stopReason = "setup_error";
        }
        catch (ModelFailure e) {
            errors.add(e.getMessage());
            stopReason = "model_error";
        }
        finally {
            try {
                backendCalls = attribute(this.admin.calls(spec.runId()), recorder.toolCalls());
                stateAfter = this.admin.state();
            }
            catch (RuntimeException e) {
                errors.add("Couldn't read the call log or state: " + e.getMessage());
            }
        }

        Instant ended = Instant.now();
        TaskSpec task = spec.task();
        return new RunRecord(spec.runId(), batch, phase, spec.model(), spec.arm(), task.id(), task.split(),
                task.category(), task.kind(), spec.repetition(), spec.position(), spec.seed(), started, ended,
                Duration.between(started, ended).toMillis(), protocolVersion, catalogSha, definitionsSha,
                task.messages(), recorder.turns, worldChanges, cards, stopReason, errors,
                recorder.totals(backendCalls.size()),
                backendCalls, stateAfter);
    }

    private String converse(RunSpec spec, ModelGateway model, McpSession session, List<ToolCallback> definitions,
            Set<String> toolNames, Recorder recorder, List<Map<String, Object>> cards,
            List<WorldChangeApplied> worldChanges) {
        Limits limits = this.properties.limits();
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(Conversation.SYSTEM_PROMPT));
        List<String> customerMessages = spec.task().messages();

        for (int segment = 0; segment < customerMessages.size(); segment++) {
            if (segment > 0) {
                for (TaskSpec.WorldChange change : spec.task().followUps().get(segment - 1).before()) {
                    apply(change);
                    worldChanges.add(new WorldChangeApplied(segment, Instant.now(), change));
                }
            }
            messages.add(new UserMessage(customerMessages.get(segment)));
            Map<String, Object> card = null;
            boolean replied = false;
            boolean reminded = false;
            while (!replied) {
                if (recorder.modelCalls() >= limits.maxModelCalls()) {
                    cards.add(card);
                    return "model_call_limit";
                }
                if (recorder.toolCallCount() >= limits.maxToolCalls()) {
                    cards.add(card);
                    return "tool_call_limit";
                }
                Instant turnStarted = Instant.now();
                int sent = messages.size();
                ModelReply reply;
                try {
                    reply = model.call(spec.runId(), messages, definitions, spec.seed(), limits.modelRetries(),
                            limits.retryBackoff());
                }
                catch (RuntimeException e) {
                    cards.add(card);
                    String failure = model.key() + " failed on turn " + (recorder.modelCalls() + 1) + ": "
                            + e.getClass().getSimpleName() + ": " + Redact.secrets(e.getMessage());
                    if (ModelGateway.providerProblem(e)) {
                        throw new SetupException(failure);
                    }
                    throw new ModelFailure(failure);
                }
                AssistantMessage assistant = reply.message();
                messages.add(assistant);
                List<ToolCall> calls = new ArrayList<>();
                boolean remindedNow = false;
                if (assistant.hasToolCalls()) {
                    List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                    for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                        ToolCall recorded = run(call, session, toolNames, recorder.nextToolCallIndex());
                        calls.add(recorded);
                        responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                                recorded.resultText()));
                        if (Conversation.FINAL_ANSWER.equals(recorded.kind())) {
                            card = parse(call.arguments());
                            replied = true;
                        }
                    }
                    messages.add(ToolResponseMessage.builder().responses(responses).build());
                }
                else if (!reminded) {
                    messages.add(new UserMessage(Conversation.REMINDER));
                    reminded = true;
                    remindedNow = true;
                }
                else {
                    replied = true;
                }
                recorder.turns.add(new Turn(recorder.turns.size() + 1, segment, turnStarted, reply.latencyMs(),
                        reply.retries(), sent, reply.finishReason(), reply.responseModel(), text(assistant),
                        reply.usage(), reply.nativeUsage(), reply.metadata(), calls, remindedNow ? true : null));
            }
            cards.add(card);
        }
        return cards.stream().allMatch(c -> c != null) ? "answered" : "missing_card";
    }

    private ToolCall run(AssistantMessage.ToolCall call, McpSession session, Set<String> toolNames, int index) {
        Instant started = Instant.now();
        if (Conversation.FINAL_ANSWER.equals(call.name())) {
            Map<String, Object> card = parse(call.arguments());
            if (card == null) {
                return finished(index, call, "bad_arguments", "{\"status\":\"not_delivered\",\"reason\":\"The "
                        + "arguments aren't a JSON object. Call final_answer again with valid JSON.\"}", true, null,
                        started);
            }
            JsonSchemaValidator.ValidationResponse check = this.validator.validate(this.cardSchema, card);
            if (!check.valid()) {
                return finished(index, call, "bad_arguments", this.json.writeValueAsString(Map.of("status",
                        "not_delivered", "reason", check.errorMessage() + ". Call final_answer again with arguments "
                                + "that match its schema.")), true, null, started);
            }
            return finished(index, call, Conversation.FINAL_ANSWER, DELIVERED, false, null, started);
        }
        if (!toolNames.contains(call.name())) {
            return finished(index, call, "unknown_tool", "There's no tool named " + call.name() + ".", true, null,
                    started);
        }
        Map<String, Object> arguments = parse(call.arguments());
        if (arguments == null) {
            return finished(index, call, "bad_arguments", "The arguments for " + call.name()
                    + " aren't a JSON object.", true, null, started);
        }
        McpSession.ToolOutcome outcome = session.call(call.name(), arguments);
        return new ToolCall(index, call.id(), call.name(), "mcp", call.arguments(), outcome.text(),
                outcome.text().length(), outcome.isError(), outcome.failure(), outcome.startedAt(),
                outcome.latencyMs());
    }

    private static ToolCall finished(int index, AssistantMessage.ToolCall call, String kind, String text,
            boolean isError, String failure, Instant started) {
        return new ToolCall(index, call.id(), call.name(), kind, call.arguments(), text, text.length(), isError,
                failure, started, Duration.between(started, Instant.now()).toMillis());
    }

    private void apply(TaskSpec.WorldChange change) {
        if (change.advanceClockMinutes() != null) {
            this.admin.advanceClock(change.advanceClockMinutes());
        }
        if (change.setStock() != null) {
            TaskSpec.StockChange stock = change.setStock();
            this.admin.setStock(stock.sku(), stock.warehouseId(), stock.available());
        }
    }

    /**
     * The MCP tools as the model receives them: name, description, and input schema, exactly as listed, plus
     * {@code final_answer}. Titles, output schemas, and annotations aren't sent, so the arms differ only in what
     * the models read.
     */
    private List<ToolCallback> definitions(List<McpSchema.Tool> tools) {
        List<ToolCallback> definitions = new ArrayList<>();
        for (McpSchema.Tool tool : tools) {
            definitions.add(DefinitionOnlyTool.of(tool.name(), tool.description(),
                    this.json.writeValueAsString(tool.inputSchema())));
        }
        DefinitionOnlyTool finalAnswer = Conversation.finalAnswerTool();
        definitions.add(DefinitionOnlyTool.of(finalAnswer.definition().name(), finalAnswer.definition().description(),
                this.json.writeValueAsString(this.json.readTree(finalAnswer.definition().inputSchema()))));
        return definitions;
    }

    /** The tool definitions in the form the manifest records them. */
    public List<Map<String, Object>> describe(List<ToolCallback> definitions) {
        return definitions.stream().map(d -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", d.getToolDefinition().name());
            entry.put("description", d.getToolDefinition().description());
            entry.put("inputSchema", this.json.readValue(d.getToolDefinition().inputSchema(),
                    new TypeReference<Map<String, Object>>() {
                    }));
            return entry;
        }).toList();
    }

    /** Lists the arm's tools once outside a run, for the manifest and the preflight check. */
    public List<Map<String, Object>> definitionsFor(String arm) {
        try (McpSession session = McpSession.open(this.properties.servers().get(arm), this.json)) {
            return describe(definitions(session.listTools()));
        }
    }

    private String definitionsSha256(List<ToolCallback> definitions) {
        return Catalogs.sha256Of(describe(definitions));
    }

    private static Set<String> toolNames(List<McpSchema.Tool> tools) {
        return tools.stream().map(McpSchema.Tool::name).collect(Collectors.toSet());
    }

    private Map<String, Object> parse(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return this.json.readValue(arguments, new TypeReference<Map<String, Object>>() {
            });
        }
        catch (JacksonException e) {
            return null;
        }
    }

    private static String text(AssistantMessage message) {
        String text = message.getText();
        return text == null || text.isBlank() ? null : text;
    }

    /**
     * Tags each backend call with the tool call it happened during. The app records a call when it finishes, so a
     * call belongs to the tool call whose start and end bracket that moment.
     */
    private static List<Map<String, Object>> attribute(List<Map<String, Object>> calls, List<ToolCall> toolCalls) {
        List<Map<String, Object>> attributed = new ArrayList<>();
        for (Map<String, Object> call : calls) {
            Map<String, Object> entry = new LinkedHashMap<>(call);
            entry.remove("runId");
            Instant at = Instant.parse(String.valueOf(call.get("at")));
            toolCalls.stream().filter(t -> "mcp".equals(t.kind()))
                    .filter(t -> !at.isBefore(t.startedAt()) && !at.isAfter(t.startedAt().plusMillis(t.latencyMs() + 1)))
                    .findFirst().ifPresent(t -> entry.put("toolCall", t.index()));
            attributed.add(entry);
        }
        return attributed;
    }

    private static final class Recorder {

        private final List<Turn> turns = new ArrayList<>();

        private int toolCallIndex;

        int modelCalls() {
            return this.turns.size();
        }

        int nextToolCallIndex() {
            return ++this.toolCallIndex;
        }

        /** Tool calls other than final_answer, which is the runner's own and doesn't count against the limit. */
        int toolCallCount() {
            return (int) toolCalls().stream().filter(c -> !Conversation.FINAL_ANSWER.equals(c.kind())).count();
        }

        List<ToolCall> toolCalls() {
            return this.turns.stream().flatMap(t -> t.toolCalls().stream()).toList();
        }

        RunRecord.Totals totals(int backendCalls) {
            long input = 0;
            long output = 0;
            long cacheRead = 0;
            long cacheWrite = 0;
            for (Turn turn : this.turns) {
                input += number(turn.usage().get("inputTokens"));
                output += number(turn.usage().get("outputTokens"));
                cacheRead += number(turn.usage().get("cacheReadTokens"));
                cacheWrite += number(turn.usage().get("cacheWriteTokens"));
            }
            List<ToolCall> calls = toolCalls();
            long resultChars = calls.stream().filter(c -> !Conversation.FINAL_ANSWER.equals(c.kind()))
                    .mapToLong(ToolCall::resultChars).sum();
            int mcpCalls = (int) calls.stream().filter(c -> "mcp".equals(c.kind())).count();
            int reminders = (int) this.turns.stream().filter(t -> Boolean.TRUE.equals(t.reminded())).count();
            return new RunRecord.Totals(this.turns.size(), toolCallCount(), mcpCalls, input, output, cacheRead,
                    cacheWrite, resultChars, backendCalls, reminders);
        }

        private static long number(Object value) {
            return value instanceof Number n ? n.longValue() : 0;
        }
    }

    static final class SetupException extends RuntimeException {

        SetupException(String message) {
            super(message);
        }
    }

    static final class ModelFailure extends RuntimeException {

        ModelFailure(String message) {
            super(message);
        }
    }
}
