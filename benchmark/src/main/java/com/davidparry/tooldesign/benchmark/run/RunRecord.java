package com.davidparry.tooldesign.benchmark.run;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Everything one run did, as one JSONL line: the turns with raw and normalized usage, every tool call with the exact
 * text the model saw, the cards the customer received, the backend calls the app logged for the run, and the
 * store's state afterward. It never holds credentials.
 *
 * @param cards one per customer message, in order; null where the model replied without {@code final_answer}
 * @param stopReason answered, missing_card, model_call_limit, tool_call_limit, model_error, or setup_error
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunRecord(String runId, String batch, String phase, String model, String arm, String taskId,
        String split, String category, String kind, int repetition, int position, Integer seed, Instant startedAt,
        Instant endedAt, long durationMs, String protocolVersion, String catalogSha256,
        String toolDefinitionsSha256, List<String> customerMessages, List<Turn> turns,
        List<WorldChangeApplied> worldChanges, List<Map<String, Object>> cards, String stopReason, List<String> errors,
        Totals totals, List<Map<String, Object>> backendCalls, Map<String, Object> stateAfter) {

    /**
     * One model request and what came back.
     *
     * @param segment which customer message the turn answers, from 0
     * @param messagesSent how many messages the request carried, system prompt included
     * @param reminded true when the turn was a plain reply and the runner answered with the final_answer reminder
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Turn(int index, int segment, Instant startedAt, long latencyMs, int retries, int messagesSent,
            String finishReason, String responseModel, String text, Map<String, Object> usage,
            Map<String, Object> nativeUsage, Map<String, Object> metadata, List<ToolCall> toolCalls,
            Boolean reminded) {
    }

    /**
     * One tool call the model made and what the runner sent back.
     *
     * @param kind mcp, final_answer, unknown_tool, or bad_arguments
     * @param failure the exception class when the call failed below the tool, otherwise null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolCall(int index, String id, String name, String kind, String arguments, String resultText,
            int resultChars, boolean isError, String failure, Instant startedAt, long latencyMs) {
    }

    public record WorldChangeApplied(int beforeSegment, Instant at, TaskSpec.WorldChange change) {
    }

    /**
     * Sums over the run. Input tokens are as each provider reports them; Anthropic's exclude cache reads and writes,
     * which are counted separately, and OpenAI's include cached tokens.
     *
     * @param mcpCalls tool calls forwarded to the arm's server
     * @param resultChars characters of tool results the model received, final_answer acknowledgements excluded
     * @param reminders plain replies the runner answered with the final_answer reminder
     */
    public record Totals(int modelCalls, int toolCalls, int mcpCalls, long inputTokens, long outputTokens,
            long cacheReadTokens, long cacheWriteTokens, long resultChars, int backendCalls, int reminders) {
    }
}
