package com.davidparry.tooldesign.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.davidparry.tooldesign.benchmark.app.AdminClient;
import com.davidparry.tooldesign.benchmark.config.BenchProperties;
import com.davidparry.tooldesign.benchmark.config.BenchProperties.Limits;
import com.davidparry.tooldesign.benchmark.config.BenchProperties.ModelSpec;
import com.davidparry.tooldesign.benchmark.model.BudgetReached;
import com.davidparry.tooldesign.benchmark.model.Gateways;
import com.davidparry.tooldesign.benchmark.model.Spend;
import com.davidparry.tooldesign.benchmark.run.BatchRunner;
import com.davidparry.tooldesign.benchmark.run.RunExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** A final batch runs only against what the freeze recorded. These batches have no models, so nothing is sent. */
class FreezeTest {

    private static final JsonMapper json = JsonMapper.builder().build();

    private static final Limits LIMITS = new Limits(30, 60, 3, Duration.ofSeconds(10));

    private static Stack stack;

    @TempDir
    Path dir;

    @BeforeAll
    static void startStack() throws Exception {
        assumeTrue(Stack.built(), "Build the app and both servers first: mvn -pl app,mcp-endpoint,mcp-workflow package");
        stack = new Stack();
    }

    @AfterAll
    static void stopStack() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    void aBatchWontStartOnceEveryRunnersSpendHasReachedTheBudget() throws IOException {
        Path fixtures = copyFixtures();
        runner(fixtures, LIMITS).freeze(List.of("endpoint", "workflow"), List.of("pilot-test"), false);
        ModelSpec claude = new ModelSpec("anthropic", "claude-opus-4-8", null, null, null, null, null, null, null,
                null, Map.of("input", 5.00, "output", 25.00));
        new Spend(this.dir.resolve("results/logs/spend.jsonl"), json).record("claude", "final-claude-C1-endpoint-r1",
                claude, Map.of("inputTokens", 4_000_000L, "outputTokens", 0L));
        BatchRunner.Request request = finalBatch();
        BatchRunner.Request budgeted = new BatchRunner.Request(request.phase(), request.batch(), request.models(),
                request.arms(), request.splits(), request.taskIds(), request.repetitions(), request.seed(), 20.0);

        assertThatThrownBy(() -> runner(fixtures, LIMITS).run(budgeted)).isInstanceOf(BudgetReached.class)
                .hasMessageContaining("$20.00 budget: $20.00 is spent");
        assertThat(this.dir.resolve("results/final/final-test")).doesNotExist();
    }

    @Test
    void aFinalBatchMatchingTheFreezeRecordsTheFrozenHashes() throws IOException {
        Path fixtures = copyFixtures();
        Path freeze = runner(fixtures, LIMITS).freeze(List.of("endpoint", "workflow"), List.of("pilot-test"), false);

        Path batch = runner(fixtures, LIMITS).run(finalBatch());

        Map<String, Object> frozen = read(freeze);
        assertThat(frozen).containsKeys("frozenAt", "source", "limits").containsEntry("pilotBatches", List.of("pilot-test"));
        assertThat(read(batch.resolve("manifest.json")).get("hashes")).isEqualTo(frozen.get("hashes"));
        assertThat(frozen.get("hashes")).asInstanceOf(InstanceOfAssertFactories.MAP).containsKeys("fixtures", "tasks",
                "conversation", "openapi", "catalogs");
    }

    @Test
    void aFinalBatchRefusesLimitsThatChangedSinceTheFreeze() throws IOException {
        Path fixtures = copyFixtures();
        runner(fixtures, LIMITS).freeze(List.of("endpoint", "workflow"), List.of(), false);

        assertThatIllegalStateException()
                .isThrownBy(() -> runner(fixtures, new Limits(40, 60, 3, Duration.ofSeconds(10))).run(finalBatch()))
                .withMessageContaining("limits changed since");
    }

    @Test
    void aFinalBatchRefusesTasksThatChangedSinceTheFreeze() throws IOException {
        Path fixtures = copyFixtures();
        runner(fixtures, LIMITS).freeze(List.of("endpoint", "workflow"), List.of(), false);
        Path a1 = fixtures.resolve("tasks/dev/A1.json");
        Files.writeString(a1, Files.readString(a1).replace("1042", "1043"));

        assertThatIllegalStateException().isThrownBy(() -> runner(fixtures, LIMITS).run(finalBatch()))
                .withMessageContaining("tasks changed since");
    }

    @Test
    void aStoppedFinalBatchResumesWhicheverOrderItsManifestKeptTheConversationIn() throws IOException {
        Path fixtures = copyFixtures();
        runner(fixtures, LIMITS).freeze(List.of("endpoint", "workflow"), List.of(), false);
        Path manifest = runner(fixtures, LIMITS).run(finalBatch()).resolve("manifest.json");
        Map<String, Object> written = read(manifest);
        Map<?, ?> conversation = (Map<?, ?>) written.get("conversation");
        Map<Object, Object> reordered = new LinkedHashMap<>();
        List.copyOf(conversation.keySet()).reversed().forEach(key -> reordered.put(key, conversation.get(key)));
        written.put("conversation", reordered);
        Files.writeString(manifest, json.writeValueAsString(written));

        assertThatNoException().isThrownBy(() -> runner(fixtures, LIMITS).run(finalBatch()));
    }

    @Test
    void aFinalBatchNeedsAFreezeAndAFreezeIsNotOverwritten() throws IOException {
        Path fixtures = copyFixtures();

        assertThatIllegalStateException().isThrownBy(() -> runner(fixtures, LIMITS).run(finalBatch()))
                .withMessageContaining("Run --command=freeze after the pilot");
        runner(fixtures, LIMITS).freeze(List.of("endpoint", "workflow"), List.of(), false);
        assertThatIllegalStateException()
                .isThrownBy(() -> runner(fixtures, LIMITS).freeze(List.of("endpoint", "workflow"), List.of(), false))
                .withMessageContaining("Use --force");
    }

    private BatchRunner.Request finalBatch() {
        return new BatchRunner.Request(BatchRunner.FINAL, "final-test", List.of(), List.of("endpoint", "workflow"),
                List.of("dev"), List.of("A1"), 1, 7);
    }

    private BatchRunner runner(Path fixtures, Limits limits) {
        BenchProperties properties = new BenchProperties(stack.appUrl(), "admin-dev-key",
                Map.of("endpoint", stack.endpointUrl(), "workflow", stack.workflowUrl()), Map.of(), limits,
                fixtures.toString(), this.dir.resolve("results").toString(), "verify");
        AdminClient admin = new AdminClient(properties, json);
        DefaultListableBeanFactory none = new DefaultListableBeanFactory();
        Spend spend = new Spend(properties, json);
        Gateways gateways = new Gateways(properties, spend, none.getBeanProvider(AnthropicChatModel.class),
                none.getBeanProvider(OpenAiChatModel.class), none.getBeanProvider(OllamaChatModel.class));
        return new BatchRunner(new RunExecutor(admin, properties, json), gateways, admin, properties, json, spend);
    }

    private Path copyFixtures() throws IOException {
        Path source = Stack.ROOT.resolve("fixtures");
        Path target = this.dir.resolve("fixtures");
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).filter(f -> !f.endsWith(BatchRunner.FREEZE_FILE))
                    .toList()) {
                Path copy = target.resolve(source.relativize(file).toString());
                Files.createDirectories(copy.getParent());
                Files.copy(file, copy);
            }
        }
        return target;
    }

    private static Map<String, Object> read(Path file) throws IOException {
        return json.readValue(Files.readString(file), new TypeReference<Map<String, Object>>() {
        });
    }
}
