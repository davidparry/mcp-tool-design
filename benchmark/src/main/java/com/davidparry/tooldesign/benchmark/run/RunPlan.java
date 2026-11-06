package com.davidparry.tooldesign.benchmark.run;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The batch's runs in a seeded random order. Each model's runs are shuffled together across arms, tasks, and
 * repetitions, so neither arm gets a warmer cache, a quieter hour, or a later model version. Models run one after
 * another in the order given.
 */
public final class RunPlan {

    private RunPlan() {
    }

    public static List<RunSpec> plan(String phase, List<String> models, List<String> arms, List<TaskSpec> tasks,
            int repetitions, long seed) {
        Random random = new Random(seed);
        List<RunSpec> planned = new ArrayList<>();
        for (String model : models) {
            List<RunSpec> runs = new ArrayList<>();
            for (TaskSpec task : tasks) {
                for (int repetition = 1; repetition <= repetitions; repetition++) {
                    for (String arm : arms) {
                        String runId = RunSpec.id(phase, model, task.id(), arm, repetition);
                        runs.add(new RunSpec(runId, model, arm, task, repetition, 0,
                                (int) (seed ^ runId.hashCode()) & Integer.MAX_VALUE));
                    }
                }
            }
            Collections.shuffle(runs, random);
            planned.addAll(runs);
        }
        List<RunSpec> numbered = new ArrayList<>(planned.size());
        for (int i = 0; i < planned.size(); i++) {
            RunSpec run = planned.get(i);
            numbered.add(new RunSpec(run.runId(), run.model(), run.arm(), run.task(), run.repetition(), i + 1,
                    run.seed()));
        }
        return numbered;
    }
}
