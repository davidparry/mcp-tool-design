package com.davidparry.tooldesign.benchmark.run;

/**
 * One planned run.
 *
 * @param position where the run falls in the batch's seeded order, from 1
 * @param seed the sampling seed sent to providers that take one, derived from the batch seed and the run ID
 */
public record RunSpec(String runId, String model, String arm, TaskSpec task, int repetition, int position,
        int seed) {

    public static String id(String phase, String model, String taskId, String arm, int repetition) {
        return phase + "-" + model + "-" + taskId + "-" + arm + "-r" + repetition;
    }
}
