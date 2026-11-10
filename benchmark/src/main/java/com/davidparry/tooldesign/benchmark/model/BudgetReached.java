package com.davidparry.tooldesign.benchmark.model;

/** The spend so far is too close to the budget to start another run. Finished runs are kept. */
public class BudgetReached extends RuntimeException {

    public BudgetReached(double budget, Spend.Summary summary) {
        super(String.format("Paused before going over the $%.2f budget: $%.2f is spent at list prices, and the "
                + "costliest run so far cost $%.2f. Run the same command with a higher --budget to go on; finished "
                + "runs are kept.", budget, summary.total(), summary.costliestRun()));
    }
}
