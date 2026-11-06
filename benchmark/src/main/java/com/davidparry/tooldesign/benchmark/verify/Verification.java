package com.davidparry.tooldesign.benchmark.verify;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What the verifier concluded about one run.
 *
 * @param valid false when the run says nothing about tool design: the setup failed or the provider kept failing
 * @param success the task was done right: every required check passed and no failure fails the task
 * @param failures each with a proposed class; a person reads the transcript before one is shown
 * @param observed what the verifier read off the run, for exports and for checking the verifier itself
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Verification(String runId, String taskId, String arm, String model, boolean valid, boolean success,
        List<Check> checks, List<Failure> failures, Map<String, Object> observed) {

    public static final String HALLUCINATED_VALUE = "hallucinated_value";

    public static final String WRONG_CONSTRAINT_VERDICT = "wrong_constraint_verdict";

    public static final String MODEL_ARITHMETIC = "model_arithmetic";

    public static final String WRONG_ITEM = "wrong_item";

    public static final String SKIPPED_CLARIFICATION = "skipped_clarification";

    public static final String UNAPPROVED_ACTION = "unapproved_action";

    public static final String OFF_TRACK = "off_track";

    public static final String FALSE_SUCCESS = "false_success";

    public static final List<String> CLASSES = List.of(HALLUCINATED_VALUE, WRONG_CONSTRAINT_VERDICT,
            MODEL_ARITHMETIC, WRONG_ITEM, SKIPPED_CLARIFICATION, UNAPPROVED_ACTION, OFF_TRACK, FALSE_SUCCESS);

    /**
     * @param required whether the task fails when the check does
     */
    public record Check(String name, boolean passed, boolean required, String detail) {
    }

    /**
     * @param severity {@code task} fails the run; {@code process} is recorded, but the answer can still be right
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Failure(String failureClass, String severity, String detail) {

        public static Failure task(String failureClass, String detail) {
            return new Failure(failureClass, "task", detail);
        }

        public static Failure process(String failureClass, String detail) {
            return new Failure(failureClass, "process", detail);
        }

        public boolean failsTask() {
            return "task".equals(this.severity);
        }
    }
}
