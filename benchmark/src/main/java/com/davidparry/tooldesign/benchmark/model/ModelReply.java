package com.davidparry.tooldesign.benchmark.model;

import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * One model response.
 *
 * @param usage the provider's counts as Spring AI normalizes them: inputTokens, outputTokens, totalTokens,
 * cacheReadTokens, cacheWriteTokens
 * @param nativeUsage the provider's own usage fields, unnormalized
 * @param metadata the response metadata with simple values, which is where Ollama puts its eval counts and timings
 */
public record ModelReply(AssistantMessage message, String finishReason, String responseModel,
        Map<String, Object> usage, Map<String, Object> nativeUsage, Map<String, Object> metadata, long latencyMs,
        int retries) {
}
