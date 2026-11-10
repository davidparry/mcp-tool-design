package com.davidparry.tooldesign.benchmark.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.databind.json.JsonMapper;

import com.davidparry.tooldesign.benchmark.ScriptedChatModel;
import com.davidparry.tooldesign.benchmark.config.BenchProperties.ModelSpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class SpendTest {

    private static final ModelSpec CLAUDE = spec("anthropic",
            Map.of("input", 5.00, "output", 25.00, "cacheRead", 0.50, "cacheWrite", 6.25));

    private static final ModelSpec GPT = spec("openai", Map.of("input", 1.25, "output", 10.00, "cacheRead", 0.125));

    private static final ModelSpec QWEN = spec("ollama", null);

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void anthropicCountsCacheReadsApartFromInputAndOpenAiCountsThemInIt() {
        assertThat(Spend.usd(CLAUDE, 1_000_000, 100_000, 1_000_000, 200_000)).isCloseTo(5.00 + 2.50 + 0.50 + 1.25,
                within(1e-9));
        assertThat(Spend.usd(GPT, 1_000_000, 100_000, 400_000, 0)).isCloseTo(0.75 + 1.00 + 0.05, within(1e-9));
        assertThat(Spend.usd(QWEN, 1_000_000, 100_000, 0, 0)).isZero();
    }

    @Test
    void twoRunnersSharingALedgerSpendFromOneTotal(@TempDir Path dir) {
        Path ledger = dir.resolve("logs/spend.jsonl");
        new Spend(ledger, this.json).record("claude", "final-claude-C1-endpoint-r1", CLAUDE, usage(80_000));
        new Spend(ledger, this.json).record("gpt", "final-gpt-C1-endpoint-r1", GPT, usage(80_000));

        Spend.Summary summary = new Spend(ledger, this.json).summary();

        assertThat(summary.calls()).isEqualTo(2);
        assertThat(summary.total()).isCloseTo(0.40 + 0.10, within(1e-9));
        assertThat(summary.byModel()).containsOnlyKeys("claude", "gpt");
    }

    @Test
    void aLineAnotherRunnerIsStillWritingIsntCountedYet(@TempDir Path dir) throws IOException {
        Path ledger = dir.resolve("spend.jsonl");
        new Spend(ledger, this.json).record("claude", "final-claude-C1-endpoint-r1", CLAUDE, usage(80_000));
        Files.writeString(ledger, "{\"model\":\"claude\",\"purpose\":\"final-cl", StandardOpenOption.APPEND);

        assertThat(new Spend(ledger, this.json).summary().calls()).isEqualTo(1);
    }

    @Test
    void theBudgetStopsARunThatCouldTakeTheSpendPastIt(@TempDir Path dir) {
        Spend spend = new Spend(dir.resolve("spend.jsonl"), this.json);
        spend.record("claude", Spend.PREFLIGHT, CLAUDE, usage(10_000));
        spend.record("claude", "final-claude-C1-endpoint-r1", CLAUDE, usage(40_000));
        spend.record("claude", "final-claude-C1-endpoint-r1", CLAUDE, usage(40_000));

        spend.check(null);
        spend.check(0.90);
        spend.record("claude", "final-claude-H1-endpoint-r1", CLAUDE, usage(60_000));

        assertThat(spend.summary().costliestRun()).isCloseTo(0.40, within(1e-9));
        assertThatThrownBy(() -> spend.check(1.00)).isInstanceOf(BudgetReached.class)
                .hasMessageContaining("$1.00 budget: $0.75 is spent").hasMessageContaining("cost $0.40");
        spend.check(1.20);
    }

    @Test
    void theGatewayPutsEveryPricedCallInTheLedgerAndLeavesTheLocalModelOut(@TempDir Path dir) {
        Spend spend = new Spend(dir.resolve("spend.jsonl"), this.json);
        ScriptedChatModel model = new ScriptedChatModel().reply("ready").reply("ready");

        new ModelGateway("claude", CLAUDE, model, spend).call("scratch-claude-C1-endpoint-r1",
                List.of(new UserMessage("hi")), List.of(), null, 0, Duration.ZERO);
        new ModelGateway("qwen", QWEN, model, spend).call("scratch-qwen-C1-endpoint-r1", List.of(new UserMessage("hi")),
                List.of(), null, 0, Duration.ZERO);

        Spend.Summary summary = spend.summary();
        assertThat(summary.calls()).isEqualTo(1);
        assertThat(summary.byPurpose()).containsOnlyKeys("scratch-claude-C1-endpoint-r1");
        assertThat(summary.total()).isCloseTo((100 * 5.00 + 10 * 25.00) / 1_000_000, within(1e-12));
    }

    private static Map<String, Object> usage(long inputTokens) {
        return Map.of("inputTokens", inputTokens, "outputTokens", 0L);
    }

    private static ModelSpec spec(String provider, Map<String, Double> prices) {
        return new ModelSpec(provider, "model", null, null, null, null, null, null, null, null, prices);
    }
}
