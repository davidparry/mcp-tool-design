package com.davidparry.tooldesign.benchmark.model;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.davidparry.tooldesign.benchmark.config.BenchProperties;
import com.davidparry.tooldesign.benchmark.config.BenchProperties.ModelSpec;

/**
 * What the hosted models have cost so far, at list prices. Every priced call adds a line to one ledger, and every
 * runner process appends to the same file, so a budget covers batches running side by side.
 */
@Component
public class Spend {

    public static final String PREFLIGHT = "preflight";

    private final Path ledger;

    private final JsonMapper json;

    @Autowired
    public Spend(BenchProperties properties, JsonMapper json) {
        this(Path.of(properties.resultsDir(), "logs", "spend.jsonl"), json);
    }

    public Spend(Path ledger, JsonMapper json) {
        this.ledger = ledger;
        this.json = json;
    }

    public Path ledger() {
        return this.ledger;
    }

    /**
     * USD for these counts at the model's list prices, or zero for a model without prices. Anthropic counts cache
     * reads and writes apart from input tokens; OpenAI's input tokens include the cached ones.
     */
    public static double usd(ModelSpec spec, long input, long output, long cacheRead, long cacheWrite) {
        Map<String, Double> prices = spec.prices();
        if (prices == null || prices.isEmpty()) {
            return 0;
        }
        double in = prices.getOrDefault("input", 0.0);
        long uncached = "anthropic".equals(spec.provider()) ? input : input - cacheRead;
        return (uncached * in + cacheRead * prices.getOrDefault("cacheRead", in)
                + cacheWrite * prices.getOrDefault("cacheWrite", in) + output * prices.getOrDefault("output", 0.0))
                / 1_000_000;
    }

    /**
     * Adds one call to the ledger, if its model has prices.
     *
     * @param purpose the run ID, or {@link #PREFLIGHT}
     */
    public void record(String model, String purpose, ModelSpec spec, Map<String, Object> usage) {
        if (spec.prices() == null || spec.prices().isEmpty()) {
            return;
        }
        long input = count(usage.get("inputTokens"));
        long output = count(usage.get("outputTokens"));
        long cacheRead = count(usage.get("cacheReadTokens"));
        long cacheWrite = count(usage.get("cacheWriteTokens"));
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("at", Instant.now().toString());
        call.put("model", model);
        call.put("purpose", purpose);
        call.put("inputTokens", input);
        call.put("outputTokens", output);
        call.put("cacheReadTokens", cacheRead);
        call.put("cacheWriteTokens", cacheWrite);
        call.put("usd", usd(spec, input, output, cacheRead, cacheWrite));
        try {
            Files.createDirectories(this.ledger.toAbsolutePath().getParent());
            // One write per line, so lines from runners sharing the ledger don't interleave.
            Files.writeString(this.ledger, this.json.writeValueAsString(call) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Everything in the ledger, from every runner that wrote to it. */
    public Summary summary() {
        if (!Files.exists(this.ledger)) {
            return new Summary(0, 0, Map.of(), Map.of());
        }
        String content;
        try {
            content = Files.readString(this.ledger);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        double total = 0;
        int calls = 0;
        Map<String, Double> byModel = new TreeMap<>();
        Map<String, Double> byPurpose = new LinkedHashMap<>();
        // A line another runner is still writing has no newline yet.
        for (String line : content.substring(0, content.lastIndexOf('\n') + 1).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> call = this.json.readValue(line, new TypeReference<Map<String, Object>>() {
            });
            double usd = ((Number) call.get("usd")).doubleValue();
            total += usd;
            calls++;
            byModel.merge(String.valueOf(call.get("model")), usd, Double::sum);
            byPurpose.merge(String.valueOf(call.get("purpose")), usd, Double::sum);
        }
        return new Summary(total, calls, byModel, byPurpose);
    }

    /**
     * Stops before a run that could take the spend past the budget: the spend so far plus the costliest run so far.
     * A null budget never stops anything.
     */
    public void check(Double budget) {
        if (budget == null) {
            return;
        }
        Summary summary = summary();
        if (summary.total() + summary.costliestRun() > budget) {
            throw new BudgetReached(budget, summary);
        }
    }

    /**
     * @param byPurpose USD by run ID, with every preflight under {@link #PREFLIGHT}
     */
    public record Summary(double total, int calls, Map<String, Double> byModel, Map<String, Double> byPurpose) {

        public double costliestRun() {
            return this.byPurpose.entrySet().stream().filter(e -> !PREFLIGHT.equals(e.getKey()))
                    .mapToDouble(Map.Entry::getValue).max().orElse(0);
        }
    }

    private static long count(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }
}
