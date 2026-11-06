package com.davidparry.tooldesign.benchmark.run;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunPlanTest {

    private static final List<TaskSpec> TASKS = List.of(task("A1"), task("C1"), task("H1"));

    @Test
    void theSameSeedGivesTheSameOrderAndADifferentSeedADifferentOne() {
        List<String> first = ids(RunPlan.plan("pilot", List.of("qwen"), List.of("endpoint", "workflow"), TASKS, 3, 7));
        List<String> again = ids(RunPlan.plan("pilot", List.of("qwen"), List.of("endpoint", "workflow"), TASKS, 3, 7));
        List<String> other = ids(RunPlan.plan("pilot", List.of("qwen"), List.of("endpoint", "workflow"), TASKS, 3, 8));

        assertThat(again).isEqualTo(first);
        assertThat(other).containsExactlyInAnyOrderElementsOf(first).isNotEqualTo(first);
    }

    @Test
    void everyCombinationRunsOnceAndEachModelsRunsStayTogether() {
        List<RunSpec> plan = RunPlan.plan("final", List.of("claude", "qwen"), List.of("endpoint", "workflow"), TASKS,
                2, 20261106);

        assertThat(plan).hasSize(2 * 2 * 3 * 2);
        assertThat(ids(plan)).doesNotHaveDuplicates().contains("final-qwen-C1-workflow-r2");
        assertThat(plan.subList(0, 12)).allSatisfy(run -> assertThat(run.model()).isEqualTo("claude"));
        assertThat(plan).extracting(RunSpec::position)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 24).boxed().toList());
    }

    @Test
    void theArmsAreInterleavedRatherThanRunInBlocks() {
        List<RunSpec> plan = RunPlan.plan("pilot", List.of("qwen"), List.of("endpoint", "workflow"), TASKS, 3, 7);

        assertThat(plan.subList(0, 9)).extracting(RunSpec::arm).contains("endpoint", "workflow");
    }

    @Test
    void repetitionsGetDifferentSeeds() {
        List<RunSpec> plan = RunPlan.plan("pilot", List.of("qwen"), List.of("workflow"), List.of(task("C1")), 3, 7);

        assertThat(plan).extracting(RunSpec::seed).doesNotHaveDuplicates().allSatisfy(s -> assertThat(s).isNotNegative());
    }

    private static List<String> ids(List<RunSpec> plan) {
        return plan.stream().map(RunSpec::runId).toList();
    }

    private static TaskSpec task(String id) {
        return new TaskSpec(id, "dev", "category", "kind", null, "prompt", null, 0, null, Map.of());
    }
}
