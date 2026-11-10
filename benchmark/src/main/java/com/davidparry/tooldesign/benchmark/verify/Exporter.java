package com.davidparry.tooldesign.benchmark.verify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import com.davidparry.tooldesign.benchmark.config.BenchProperties;
import com.davidparry.tooldesign.benchmark.config.BenchProperties.ModelSpec;
import com.davidparry.tooldesign.benchmark.model.Spend;
import com.davidparry.tooldesign.benchmark.run.RunRecord;
import com.davidparry.tooldesign.benchmark.run.TaskSpec;

/**
 * Writes the numbers the talk and the post quote, from verified runs: success, complete-task tokens, calls, latency,
 * cost at list prices, the order 1042 per-turn token series, failures by class with their run IDs, and what each run
 * asked the store for against what the frontend's screens ask for.
 */
@Component
public class Exporter {

    private final BenchProperties properties;

    private final JsonMapper pretty;

    public Exporter(BenchProperties properties, JsonMapper json) {
        this.properties = properties;
        this.pretty = json.rebuild().enable(SerializationFeature.INDENT_OUTPUT).build();
    }

    /** One run, its task, and its verification, with the token counts normalized across providers. */
    public record Row(RunRecord run, Verification verification, String provider, TaskSpec task) {

        /**
         * Every input token the provider processed, cached or not. Anthropic reports cache reads and writes apart
         * from input tokens; OpenAI's prompt tokens already include cached ones; Ollama doesn't cache across requests.
         */
        long allInputTokens() {
            RunRecord.Totals t = this.run.totals();
            return "anthropic".equals(this.provider) ? t.inputTokens() + t.cacheReadTokens() + t.cacheWriteTokens()
                    : t.inputTokens();
        }

        long completeTaskTokens() {
            return allInputTokens() + this.run.totals().outputTokens();
        }

        String split() {
            return this.run.split();
        }

        /** Submission scenarios aren't, because their last step is a person's click. */
        boolean measuredAgainstScreens() {
            return !"scenario".equals(split());
        }
    }

    public Path export(List<Row> rows, Path dir) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("runs.csv"), runsCsv(rows));
            Files.writeString(dir.resolve("failures.csv"), failuresCsv(rows));
            Files.writeString(dir.resolve("tokens-order-1042.csv"), order1042Csv(rows));
            Files.writeString(dir.resolve("frontend-steps.csv"), frontendStepsCsv(rows));
            Map<String, Object> summary = summary(rows);
            Files.writeString(dir.resolve("summary.json"), this.pretty.writeValueAsString(summary) + "\n");
            Files.writeString(dir.resolve("summary.md"), markdown(summary));
            return dir;
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Map<String, Object> summary(List<Row> rows) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runs", rows.size());
        summary.put("validRuns", rows.stream().filter(r -> r.verification().valid()).count());
        List<Map<String, Object>> groups = new ArrayList<>();
        Map<String, List<Row>> byGroup = new TreeMap<>();
        for (Row row : rows) {
            byGroup.computeIfAbsent(row.run().model() + "|" + row.run().arm() + "|" + row.split(), k -> new ArrayList<>())
                    .add(row);
        }
        byGroup.forEach((key, members) -> groups.add(group(key.split("\\|"), members)));
        summary.put("groups", groups);
        summary.put("failures", failures(rows));
        summary.put("frontendSteps", frontendSteps(rows));
        return summary;
    }

    /** Per model and arm, how far the runs' calls fell short of, or went past, what the screens ask the store for. */
    private static List<Map<String, Object>> frontendSteps(List<Row> rows) {
        Map<String, List<FrontendSteps>> byArm = new TreeMap<>();
        for (Row row : rows) {
            if (row.verification().valid() && row.measuredAgainstScreens()) {
                byArm.computeIfAbsent(row.run().model() + "|" + row.run().arm(), k -> new ArrayList<>())
                        .add(FrontendSteps.of(row.run(), row.task()));
            }
        }
        List<Map<String, Object>> arms = new ArrayList<>();
        byArm.forEach((key, steps) -> {
            List<FrontendSteps> withOptions = steps.stream().filter(s -> s.optionsOnScreen() > 0).toList();
            List<FrontendSteps> noMatch = steps.stream()
                    .filter(s -> "no_matching_option".equals(s.expectedOutcome())).toList();
            Map<String, Object> arm = new LinkedHashMap<>();
            arm.put("model", key.split("\\|")[0]);
            arm.put("arm", key.split("\\|")[1]);
            arm.put("runs", steps.size());
            arm.put("runsWithOptions", withOptions.size());
            arm.put("runsQuotingFewerOptions", withOptions.stream().filter(s -> s.optionsQuoted() < s.optionsOnScreen())
                    .count());
            arm.put("optionsQuoted", withOptions.stream().mapToInt(FrontendSteps::optionsQuoted).sum());
            arm.put("optionsOnScreen", withOptions.stream().mapToInt(FrontendSteps::optionsOnScreen).sum());
            arm.put("runsMissingAFit", steps.stream().filter(s -> s.fitsNotQuoted() > 0).count());
            arm.put("stockChecksDue", steps.stream().filter(s -> s.stockChecked() != null).count());
            arm.put("runsWithoutStockCheck", steps.stream().filter(s -> Boolean.FALSE.equals(s.stockChecked())).count());
            arm.put("eligibilityChecksDue", steps.stream().filter(s -> s.eligibilityChecked() != null).count());
            arm.put("runsWithoutEligibilityCheck", steps.stream()
                    .filter(s -> Boolean.FALSE.equals(s.eligibilityChecked())).count());
            arm.put("refusedCalls", steps.stream().mapToInt(FrontendSteps::refusedCalls).sum());
            arm.put("runsWithRefusedCalls", steps.stream().filter(s -> s.refusedCalls() > 0).count());
            arm.put("unaskedQuotes", steps.stream().filter(s -> s.unaskedQuotes() != null)
                    .mapToInt(FrontendSteps::unaskedQuotes).sum());
            arm.put("runsWithUnaskedQuotes", steps.stream()
                    .filter(s -> s.unaskedQuotes() != null && s.unaskedQuotes() > 0).count());
            arm.put("noMatchRuns", noMatch.size());
            arm.put("noMatchCardsLabeledOtherwise", noMatch.stream()
                    .filter(s -> !"no_matching_option".equals(s.cardOutcome())).count());
            arms.add(arm);
        });
        return arms;
    }

    private Map<String, Object> group(String[] key, List<Row> rows) {
        List<Row> valid = rows.stream().filter(r -> r.verification().valid()).toList();
        List<Row> succeeded = valid.stream().filter(r -> r.verification().success()).toList();
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("model", key[0]);
        group.put("arm", key[1]);
        group.put("split", key[2]);
        group.put("runs", rows.size());
        group.put("valid", valid.size());
        group.put("succeeded", succeeded.size());
        group.put("successRate", valid.isEmpty() ? null : round(succeeded.size() / (double) valid.size(), 3));
        group.put("completeTaskTokens", Map.of("allAttempts", stats(valid, r -> r.completeTaskTokens()),
                "successfulOnly", stats(succeeded, r -> r.completeTaskTokens())));
        group.put("inputTokens", stats(valid, Row::allInputTokens));
        group.put("outputTokens", stats(valid, r -> r.run().totals().outputTokens()));
        group.put("cacheReadTokens", stats(valid, r -> r.run().totals().cacheReadTokens()));
        group.put("modelCalls", stats(valid, r -> r.run().totals().modelCalls()));
        group.put("toolCalls", stats(valid, r -> r.run().totals().toolCalls()));
        group.put("backendCalls", stats(valid, r -> r.run().totals().backendCalls()));
        group.put("toolResultChars", stats(valid, r -> r.run().totals().resultChars()));
        group.put("reminders", valid.stream().mapToInt(r -> r.run().totals().reminders()).sum());
        group.put("latencyMs", stats(valid, r -> r.run().durationMs()));
        group.put("costPer1000Tasks", cost(key[0], valid));
        Map<String, Long> classes = new TreeMap<>();
        for (Row row : valid) {
            row.verification().failures().stream().filter(Verification.Failure::failsTask)
                    .map(Verification.Failure::failureClass).distinct().forEach(c -> classes.merge(c, 1L, Long::sum));
        }
        group.put("runsFailingByClass", classes);
        return group;
    }

    /**
     * USD for 1,000 runs like these at the model's list prices, once with cache reads billed at the cache price and
     * once as if every input token were billed in full. Null when the model has no prices configured.
     */
    private Map<String, Object> cost(String model, List<Row> rows) {
        ModelSpec spec = this.properties.models().get(model);
        Map<String, Double> prices = spec == null ? null : spec.prices();
        if (prices == null || prices.isEmpty() || rows.isEmpty()) {
            return null;
        }
        double in = prices.getOrDefault("input", 0.0);
        double out = prices.getOrDefault("output", 0.0);
        double withCache = 0;
        double withoutCache = 0;
        for (Row row : rows) {
            RunRecord.Totals t = row.run().totals();
            withCache += Spend.usd(spec, t.inputTokens(), t.outputTokens(), t.cacheReadTokens(),
                    t.cacheWriteTokens());
            withoutCache += (row.allInputTokens() * in + t.outputTokens() * out) / 1_000_000.0;
        }
        double perTask = 1000.0 / rows.size();
        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("withCacheReads", round(withCache * perTask, 2));
        cost.put("withoutCacheReads", round(withoutCache * perTask, 2));
        cost.put("prices", prices);
        return cost;
    }

    private static List<Map<String, Object>> failures(List<Row> rows) {
        Map<String, Map<String, Object>> byClass = new LinkedHashMap<>();
        for (String failureClass : Verification.CLASSES) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("class", failureClass);
            entry.put("taskFailures", new ArrayList<String>());
            entry.put("processOnly", new ArrayList<String>());
            byClass.put(failureClass, entry);
        }
        for (Row row : rows) {
            if (!row.verification().valid()) {
                continue;
            }
            Map<String, Boolean> classes = new LinkedHashMap<>();
            row.verification().failures().forEach(f -> classes.merge(f.failureClass(), f.failsTask(), Boolean::logicalOr));
            classes.forEach((failureClass, failsTask) -> {
                @SuppressWarnings("unchecked")
                List<String> ids = (List<String>) byClass.get(failureClass).get(failsTask ? "taskFailures" : "processOnly");
                ids.add(row.run().runId());
            });
        }
        byClass.values().forEach(entry -> {
            entry.put("taskFailureCount", ((List<?>) entry.get("taskFailures")).size());
            entry.put("processOnlyCount", ((List<?>) entry.get("processOnly")).size());
        });
        return new ArrayList<>(byClass.values());
    }

    private static String runsCsv(List<Row> rows) {
        StringBuilder csv = new StringBuilder("runId,model,arm,task,split,category,repetition,valid,success,stopReason,"
                + "modelCalls,toolCalls,mcpCalls,inputTokens,allInputTokens,outputTokens,cacheReadTokens,"
                + "cacheWriteTokens,completeTaskTokens,toolResultChars,backendCalls,reminders,durationMs,taskFailures,"
                + "processFailures\n");
        for (Row row : sorted(rows)) {
            RunRecord r = row.run();
            RunRecord.Totals t = r.totals();
            Verification v = row.verification();
            csv.append(String.join(",", r.runId(), r.model(), r.arm(), r.taskId(), r.split(), r.category(),
                    String.valueOf(r.repetition()), String.valueOf(v.valid()), String.valueOf(v.success()),
                    r.stopReason(), String.valueOf(t.modelCalls()), String.valueOf(t.toolCalls()),
                    String.valueOf(t.mcpCalls()), String.valueOf(t.inputTokens()), String.valueOf(row.allInputTokens()),
                    String.valueOf(t.outputTokens()), String.valueOf(t.cacheReadTokens()),
                    String.valueOf(t.cacheWriteTokens()), String.valueOf(row.completeTaskTokens()),
                    String.valueOf(t.resultChars()), String.valueOf(t.backendCalls()), String.valueOf(t.reminders()),
                    String.valueOf(r.durationMs()), classes(v, true), classes(v, false))).append('\n');
        }
        return csv.toString();
    }

    private static String failuresCsv(List<Row> rows) {
        StringBuilder csv = new StringBuilder("runId,model,arm,task,class,severity,detail\n");
        for (Row row : sorted(rows)) {
            for (Verification.Failure f : row.verification().failures()) {
                csv.append(String.join(",", row.run().runId(), row.run().model(), row.run().arm(), row.run().taskId(),
                        f.failureClass(), f.severity(), quote(f.detail()))).append('\n');
            }
        }
        return csv.toString();
    }

    /** For every single-message task run, what the screens ask the store for and what the app's records show. */
    private static String frontendStepsCsv(List<Row> rows) {
        StringBuilder csv = new StringBuilder("runId,model,arm,task,category,success,screenCalls,backendCalls,"
                + "eligibilityChecked,stockChecked,optionsQuoted,optionsOnScreen,fitsNotQuoted,refusedCalls,"
                + "unaskedQuotes,cardOutcome,expectedOutcome\n");
        for (Row row : sorted(rows)) {
            if (!row.measuredAgainstScreens()) {
                continue;
            }
            RunRecord r = row.run();
            FrontendSteps s = FrontendSteps.of(r, row.task());
            csv.append(String.join(",", r.runId(), r.model(), r.arm(), r.taskId(), r.category(),
                    String.valueOf(row.verification().success()), blank(s.screenCalls()),
                    String.valueOf(s.backendCalls()), blank(s.eligibilityChecked()), blank(s.stockChecked()),
                    String.valueOf(s.optionsQuoted()), String.valueOf(s.optionsOnScreen()),
                    String.valueOf(s.fitsNotQuoted()), String.valueOf(s.refusedCalls()), blank(s.unaskedQuotes()),
                    s.cardOutcome(), s.expectedOutcome())).append('\n');
        }
        return csv.toString();
    }

    /**
     * Input tokens on each turn of every order 1042 preview (task C1). Each turn re-reads every earlier tool result,
     * which is the chart that shows where an arm's tokens go.
     */
    private static String order1042Csv(List<Row> rows) {
        StringBuilder csv = new StringBuilder("runId,model,arm,success,turn,inputTokens,outputTokens,toolCalls\n");
        for (Row row : sorted(rows)) {
            if (!"C1".equals(row.run().taskId()) || !row.verification().valid()) {
                continue;
            }
            for (RunRecord.Turn turn : row.run().turns()) {
                csv.append(String.join(",", row.run().runId(), row.run().model(), row.run().arm(),
                        String.valueOf(row.verification().success()), String.valueOf(turn.index()),
                        String.valueOf(turnInput(row, turn)), String.valueOf(number(turn.usage().get("outputTokens"))),
                        quote(turn.toolCalls().stream().map(RunRecord.ToolCall::name).collect(Collectors.joining(" ")))))
                        .append('\n');
            }
        }
        return csv.toString();
    }

    @SuppressWarnings("unchecked")
    private static String markdown(Map<String, Object> summary) {
        StringBuilder md = new StringBuilder("# Benchmark summary\n\n");
        md.append(summary.get("validRuns")).append(" valid runs of ").append(summary.get("runs")).append(".\n\n");
        md.append("| Model | Arm | Split | Valid | Success | Median tokens | Median tokens, successes | Median model calls "
                + "| Median tool calls | Median latency (s) |\n| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Map<String, Object> g : (List<Map<String, Object>>) summary.get("groups")) {
            Map<String, Object> tokens = (Map<String, Object>) g.get("completeTaskTokens");
            md.append("| ").append(g.get("model")).append(" | ").append(g.get("arm")).append(" | ").append(g.get("split"))
                    .append(" | ").append(g.get("valid")).append(" | ").append(g.get("succeeded")).append(" (")
                    .append(percent(g.get("successRate"))).append(") | ")
                    .append(median(tokens.get("allAttempts"))).append(" | ").append(median(tokens.get("successfulOnly")))
                    .append(" | ").append(median(g.get("modelCalls"))).append(" | ").append(median(g.get("toolCalls")))
                    .append(" | ").append(seconds(median(g.get("latencyMs")))).append(" |\n");
        }
        md.append("\n## Failures by class\n\nRuns where the class failed the task, and runs where it was only recorded.\n\n"
                + "| Class | Failed the task | Recorded only |\n| --- | --- | --- |\n");
        for (Map<String, Object> f : (List<Map<String, Object>>) summary.get("failures")) {
            md.append("| ").append(f.get("class")).append(" | ").append(f.get("taskFailureCount")).append(" | ")
                    .append(f.get("processOnlyCount")).append(" |\n");
        }
        md.append("\n## Against the frontend's screens\n\nSingle-message tasks, from the app's call log and quotes. The "
                + "screens check eligibility and stock, then quote every delivery method at every warehouse that has "
                + "the replacement, and only for what the customer asked about.\n\n| Model | Arm | Options quoted | Runs "
                + "quoting fewer | Runs missing an option that fits | No stock check | No eligibility check | Refused "
                + "calls | Quotes nobody asked for | No-match cards labeled otherwise |\n"
                + "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Map<String, Object> s : (List<Map<String, Object>>) summary.get("frontendSteps")) {
            md.append("| ").append(s.get("model")).append(" | ").append(s.get("arm")).append(" | ")
                    .append(s.get("optionsQuoted")).append(" of ").append(s.get("optionsOnScreen")).append(" | ")
                    .append(s.get("runsQuotingFewerOptions")).append(" of ").append(s.get("runsWithOptions")).append(" | ")
                    .append(s.get("runsMissingAFit")).append(" | ")
                    .append(s.get("runsWithoutStockCheck")).append(" of ").append(s.get("stockChecksDue")).append(" | ")
                    .append(s.get("runsWithoutEligibilityCheck")).append(" of ").append(s.get("eligibilityChecksDue"))
                    .append(" | ").append(s.get("refusedCalls")).append(" in ").append(s.get("runsWithRefusedCalls"))
                    .append(" runs | ").append(s.get("unaskedQuotes")).append(" in ").append(s.get("runsWithUnaskedQuotes"))
                    .append(" runs | ").append(s.get("noMatchCardsLabeledOtherwise")).append(" of ")
                    .append(s.get("noMatchRuns")).append(" |\n");
        }
        return md.toString();
    }

    private static String blank(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long turnInput(Row row, RunRecord.Turn turn) {
        long input = number(turn.usage().get("inputTokens"));
        if ("anthropic".equals(row.provider())) {
            input += number(turn.usage().get("cacheReadTokens")) + number(turn.usage().get("cacheWriteTokens"));
        }
        return input;
    }

    private static Map<String, Object> stats(List<Row> rows, ToDoubleFunction<Row> value) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("n", rows.size());
        if (rows.isEmpty()) {
            return stats;
        }
        double[] values = rows.stream().mapToDouble(value).sorted().toArray();
        stats.put("mean", round(java.util.Arrays.stream(values).average().orElse(0), 1));
        stats.put("median", round(percentile(values, 0.5), 1));
        stats.put("p95", round(percentile(values, 0.95), 1));
        stats.put("min", round(values[0], 1));
        stats.put("max", round(values[values.length - 1], 1));
        return stats;
    }

    /** Linear interpolation between the closest ranks. */
    static double percentile(double[] sorted, double p) {
        if (sorted.length == 1) {
            return sorted[0];
        }
        double rank = p * (sorted.length - 1);
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        return sorted[low] + (sorted[high] - sorted[low]) * (rank - low);
    }

    private static List<Row> sorted(List<Row> rows) {
        return rows.stream().sorted(Comparator.comparing((Row r) -> r.run().model()).thenComparing(r -> r.run().taskId())
                .thenComparing(r -> r.run().arm()).thenComparingInt(r -> r.run().repetition())).toList();
    }

    private static String classes(Verification v, boolean failsTask) {
        return v.failures().stream().filter(f -> f.failsTask() == failsTask).map(Verification.Failure::failureClass)
                .distinct().collect(Collectors.joining(";"));
    }

    private static String quote(String text) {
        return "\"" + String.valueOf(text).replace("\"", "\"\"").replace("\n", " ") + "\"";
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }

    private static double round(double value, int places) {
        return BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP).doubleValue();
    }

    @SuppressWarnings("unchecked")
    private static Object median(Object stats) {
        return stats instanceof Map<?, ?> m ? ((Map<String, Object>) m).getOrDefault("median", "") : "";
    }

    private static String percent(Object rate) {
        return rate instanceof Number n ? Math.round(n.doubleValue() * 100) + "%" : "n/a";
    }

    private static String seconds(Object millis) {
        return millis instanceof Number n ? String.valueOf(round(n.doubleValue() / 1000, 1)) : "";
    }
}
