package com.davidparry.tooldesign.benchmark.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the app and the two MCP servers are, the models and their settings, and the limits every run shares.
 *
 * @param catalogMode {@code verify} refuses to run when an arm's catalog differs from its snapshot; {@code record}
 * runs and records the hash without comparing, which is only for building the benchmark before it's frozen
 */
@ConfigurationProperties("bench")
public record BenchProperties(String appUrl, String adminKey, Map<String, String> servers,
        Map<String, ModelSpec> models, Limits limits, @DefaultValue("fixtures") String fixturesDir,
        @DefaultValue("results") String resultsDir, @DefaultValue("verify") String catalogMode) {

    /**
     * One model and its settings. Unset settings use the provider's default and are recorded as unset.
     *
     * @param provider anthropic, openai, or ollama
     * @param prices list price in USD per million tokens: input, output, cacheRead, cacheWrite
     */
    public record ModelSpec(String provider, String model, Integer maxTokens, Double temperature, Double topP,
            Integer topK, Integer numCtx, Boolean think, String effort, String reasoningEffort,
            Map<String, Double> prices) {
    }

    /** Hard stops for one run. The pilot sets them; the final run freezes them. */
    public record Limits(@DefaultValue("30") int maxModelCalls, @DefaultValue("60") int maxToolCalls,
            @DefaultValue("3") int modelRetries, @DefaultValue("10s") Duration retryBackoff) {
    }
}
