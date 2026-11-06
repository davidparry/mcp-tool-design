package com.davidparry.tooldesign.benchmark.model;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;

/**
 * Reads token counts off a response without depending on each provider SDK's types. Anthropic's native usage has
 * {@code inputTokens}, {@code outputTokens}, and the two cache counts; OpenAI's has {@code promptTokens} (which
 * includes cached tokens) with details for cached and reasoning tokens; Ollama has none, and its counts are in the
 * response metadata instead.
 */
final class UsageReader {

    private static final Set<String> NATIVE_FIELDS = Set.of("inputTokens", "outputTokens",
            "cacheCreationInputTokens", "cacheReadInputTokens", "promptTokens", "completionTokens", "totalTokens",
            "promptTokensDetails", "completionTokensDetails", "cachedTokens", "reasoningTokens", "audioTokens",
            "acceptedPredictionTokens", "rejectedPredictionTokens", "serviceTier");

    private UsageReader() {
    }

    static Map<String, Object> usage(Usage usage) {
        Map<String, Object> counts = new LinkedHashMap<>();
        if (usage == null) {
            return counts;
        }
        counts.put("inputTokens", usage.getPromptTokens());
        counts.put("outputTokens", usage.getCompletionTokens());
        counts.put("totalTokens", usage.getTotalTokens());
        counts.put("cacheReadTokens", usage.getCacheReadInputTokens());
        counts.put("cacheWriteTokens", usage.getCacheWriteInputTokens());
        return counts;
    }

    static Map<String, Object> nativeUsage(Usage usage) {
        if (usage == null || usage.getNativeUsage() == null) {
            return Map.of();
        }
        Map<String, Object> fields = read(usage.getNativeUsage(), 0);
        fields.put("type", usage.getNativeUsage().getClass().getName());
        return fields;
    }

    static Map<String, Object> metadata(ChatResponseMetadata metadata) {
        Map<String, Object> simple = new LinkedHashMap<>();
        if (metadata == null) {
            return simple;
        }
        metadata.entrySet().forEach(entry -> {
            Object value = entry.getValue();
            if (value instanceof Number || value instanceof Boolean || value instanceof CharSequence) {
                simple.put(entry.getKey(), value instanceof CharSequence ? value.toString() : value);
            }
            else if (value instanceof Duration duration) {
                simple.put(entry.getKey() + "Ms", duration.toMillis());
            }
        });
        return simple;
    }

    private static Map<String, Object> read(Object source, int depth) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Method method : source.getClass().getMethods()) {
            if (!NATIVE_FIELDS.contains(method.getName()) || method.getParameterCount() != 0
                    || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            try {
                Object value = method.invoke(source);
                if (value instanceof Optional<?> optional) {
                    value = optional.orElse(null);
                }
                if (value == null) {
                    continue;
                }
                if (value instanceof Number || value instanceof Boolean) {
                    fields.put(method.getName(), value);
                }
                else if (depth < 2 && method.getName().endsWith("Details")) {
                    fields.put(method.getName(), read(value, depth + 1));
                }
                else {
                    fields.put(method.getName(), value.toString());
                }
            }
            catch (ReflectiveOperationException | RuntimeException e) {
                fields.put(method.getName(), "unreadable: " + e.getClass().getSimpleName());
            }
        }
        return fields;
    }
}
