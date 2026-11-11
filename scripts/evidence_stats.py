#!/usr/bin/env python3
"""Prints how strong the difference between the two servers is, from the final exports: per-task
token ratios, paired runs, failed runs with exact tests, and intervals that resample tasks.

    python3 scripts/evidence_stats.py > results/final/evidence.md
"""
import csv
import json
import math
import random
import statistics
from collections import defaultdict
from pathlib import Path

FINAL = Path(__file__).resolve().parent.parent / "results" / "final"
MODELS = [("qwen", "qwen", "final-qwen"), ("gpt", "GPT-5", "final-gpt"), ("claude", "Claude Opus 4.8", None)]
SUBSETS = [("All 24 tasks", {"dev", "heldout"}), ("8 held-out tasks", {"heldout"}),
           ("5 submission scenarios", {"scenario"})]
RESAMPLES = 10000
SEED = 20261106


def export_runs(model):
    with open(FINAL / model / "export" / "runs.csv") as f:
        return [row for row in csv.DictReader(f) if row["valid"] == "true"]


def first_call_medians(batch):
    tokens = defaultdict(list)
    for line in (FINAL / batch / "runs.jsonl").read_text().splitlines():
        if line.strip():
            run = json.loads(line)
            if run["stopReason"] != "setup_error" and run["turns"]:
                tokens[run["arm"]].append(run["turns"][0]["usage"]["inputTokens"])
    return {arm: statistics.median(values) for arm, values in tokens.items()}


def binomial_p(k, n):
    """Exact two-sided binomial test against even odds."""
    if n == 0:
        return 1.0
    probs = [math.comb(n, i) / 2 ** n for i in range(n + 1)]
    return min(1.0, sum(p for p in probs if p <= probs[k] * (1 + 1e-7)))


def fisher_p(a, b, c, d):
    """Fisher's exact test, two-sided, on the table [[a, b], [c, d]]."""
    row, other, col = a + b, c + d, a + c

    def prob(x):
        return math.comb(row, x) * math.comb(other, col - x) / math.comb(row + other, col)

    observed = prob(a)
    xs = range(max(0, col - other), min(row, col) + 1)
    return min(1.0, sum(prob(x) for x in xs if prob(x) <= observed * (1 + 1e-7)))


def wilcoxon_p(diffs):
    """Exact two-sided Wilcoxon signed-rank test. The exact null needs no zeros and no tied sizes."""
    n = len(diffs)
    if 0 in diffs or len({abs(d) for d in diffs}) < n:
        raise ValueError("zero or tied differences")
    order = sorted(range(n), key=lambda i: abs(diffs[i]))
    w = sum(rank + 1 for rank, i in enumerate(order) if diffs[i] > 0)
    counts = [1] + [0] * (n * (n + 1) // 2)
    for k in range(1, n + 1):
        for s in range(len(counts) - 1, k - 1, -1):
            counts[s] += counts[s - k]
    return min(1.0, 2 * min(sum(counts[:w + 1]), sum(counts[w:])) / 2 ** n)


def wilson(k, n, z=1.959964):
    p = k / n
    centre = (p + z * z / (2 * n)) / (1 + z * z / n)
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / (1 + z * z / n)
    return max(0.0, centre - half), min(1.0, centre + half)


def percentile(values, q):
    values = sorted(values)
    at = (len(values) - 1) * q
    low = math.floor(at)
    high = min(low + 1, len(values) - 1)
    return values[low] + (values[high] - values[low]) * (at - low)


def bootstrap(n, statistic, rng):
    samples = [statistic([rng.randrange(n) for _ in range(n)]) for _ in range(RESAMPLES)]
    return percentile(samples, 0.025), percentile(samples, 0.975)


def p_text(p):
    return f"{p:.1e}" if p < 0.001 else f"{p:.3f}"


def describe(rows, splits, rng):
    by_task = defaultdict(lambda: defaultdict(dict))
    for row in rows:
        if row["split"] in splits:
            by_task[row["task"]][row["arm"]][int(row["repetition"])] = row
    tasks = sorted(by_task)
    n = len(tasks)
    if n == 0:
        return None

    def tokens(row):
        return int(row["completeTaskTokens"])

    def failed(row):
        return row["success"] != "true"

    def arm(name):
        return [row for t in tasks for row in by_task[t][name].values()]

    endpoint, workflow = arm("endpoint"), arm("workflow")
    ratios = [statistics.median(tokens(r) for r in by_task[t]["endpoint"].values())
              / statistics.median(tokens(r) for r in by_task[t]["workflow"].values()) for t in tasks]
    logs = [math.log(ratio) for ratio in ratios]
    higher = sum(ratio > 1 for ratio in ratios)
    ratio_low, ratio_high = bootstrap(n, lambda picks: math.exp(statistics.fmean(logs[i] for i in picks)), rng)
    lowest, highest = min(zip(ratios, tasks)), max(zip(ratios, tasks))
    pairs = [(by_task[t]["endpoint"][k], by_task[t]["workflow"][k])
             for t in tasks for k in sorted(by_task[t]["endpoint"]) if k in by_task[t]["workflow"]]

    failed_e, failed_w = sum(map(failed, endpoint)), sum(map(failed, workflow))
    only_e = sum(failed(e) and not failed(w) for e, w in pairs)
    only_w = sum(failed(w) and not failed(e) for e, w in pairs)
    per_task = [(sum(map(failed, by_task[t]["endpoint"].values())), len(by_task[t]["endpoint"]),
                 sum(map(failed, by_task[t]["workflow"].values())), len(by_task[t]["workflow"])) for t in tasks]
    worse = sum(fe > fw for fe, _, fw, _ in per_task)
    better = sum(fe < fw for fe, _, fw, _ in per_task)

    def gap(picks):
        chosen = [per_task[i] for i in picks]
        return (sum(c[0] for c in chosen) / sum(c[1] for c in chosen)
                - sum(c[2] for c in chosen) / sum(c[3] for c in chosen))

    gap_low, gap_high = bootstrap(n, gap, rng)
    e_low, e_high = wilson(failed_e, len(endpoint))
    w_low, w_high = wilson(failed_w, len(workflow))
    by_task_failures = ", ".join(f"{t} {fe}-{fw}" for t, (fe, _, fw, _) in zip(tasks, per_task) if fe or fw)

    return [
        ("Tasks where the endpoint server's median tokens were higher",
         f"{higher} of {n} (sign test p = {p_text(binomial_p(higher, n))})"),
        ("Exact Wilcoxon signed-rank test on the per-task log ratios", f"p = {p_text(wilcoxon_p(logs))}"),
        ("Per-task token ratio, geometric mean (95% interval)",
         f"{statistics.geometric_mean(ratios):.2f}x ({ratio_low:.2f}x to {ratio_high:.2f}x)"),
        ("Lowest and highest per-task ratio", f"{lowest[0]:.2f}x ({lowest[1]}) to {highest[0]:.2f}x ({highest[1]})"),
        ("Paired runs where the endpoint run used more tokens",
         f"{sum(tokens(e) > tokens(w) for e, w in pairs)} of {len(pairs)}"),
        ("Median tokens per run, endpoint and workflow",
         f"{statistics.median(map(tokens, endpoint)):,.0f} and {statistics.median(map(tokens, workflow)):,.0f}"),
        ("Model calls, endpoint and workflow",
         f"{sum(int(r['modelCalls']) for r in endpoint)} and {sum(int(r['modelCalls']) for r in workflow)}"),
        ("Calls to the server's tools, endpoint and workflow",
         f"{sum(int(r['mcpCalls']) for r in endpoint)} and {sum(int(r['mcpCalls']) for r in workflow)}"),
        ("Failed runs on the endpoint server (95% interval)",
         f"{failed_e} of {len(endpoint)} ({e_low:.1%} to {e_high:.1%})"),
        ("Failed runs on the workflow server (95% interval)",
         f"{failed_w} of {len(workflow)} ({w_low:.1%} to {w_high:.1%})"),
        ("Fisher's exact test on failed runs",
         f"p = {p_text(fisher_p(failed_e, len(endpoint) - failed_e, failed_w, len(workflow) - failed_w))}"),
        ("Paired runs where only the endpoint run failed, and where only the workflow run did",
         f"{only_e} and {only_w} (exact McNemar p = {p_text(binomial_p(only_e, only_e + only_w))})"),
        ("Tasks where the endpoint server failed more often, less often, and as often",
         f"{worse}, {better}, and {n - worse - better} (sign test p = {p_text(binomial_p(worse, worse + better))})"),
        ("Failure-rate gap, endpoint minus workflow (95% interval)",
         f"{gap(range(n)) * 100:+.1f} points ({gap_low * 100:+.1f} to {gap_high * 100:+.1f})"),
        ("Failed runs by task, endpoint-workflow", by_task_failures or "none"),
    ]


def main():
    rng = random.Random(SEED)
    print("# How strong the evidence is\n")
    print("Generated by `python3 scripts/evidence_stats.py` from each model's `export/runs.csv`, and for the")
    print("first model call from its task batch's `runs.jsonl`. Every task ran five times on each server.")
    print("A run's tokens are its complete-task tokens, and its success is the verifier's grade. A ratio is")
    print("the endpoint server's median over the workflow server's for one task, and paired runs share a")
    print("task and a repetition. Intervals are 95%: Wilson intervals for a share of runs, and percentile")
    print(f"bootstrap intervals that resample tasks {RESAMPLES:,} times (seed {SEED}), since runs of one task")
    print("aren't independent. Token counts can't be compared across providers.")
    for model, name, batch in MODELS:
        rows = export_runs(model)
        tables = [(label, describe(rows, splits, rng)) for label, splits in SUBSETS]
        tables = [(label, table) for label, table in tables if table]
        print(f"\n## {name}\n")
        if batch:
            first = first_call_medians(batch)
            print(f"The first model call of a task run took a median of {first['endpoint']:,.0f} input tokens on the")
            print(f"endpoint server and {first['workflow']:,.0f} on the workflow server.\n")
        print("| Measure | " + " | ".join(label for label, _ in tables) + " |")
        print("| --- |" + " --- |" * len(tables))
        for i, (measure, _) in enumerate(tables[0][1]):
            print(f"| {measure} | " + " | ".join(table[i][1] for _, table in tables) + " |")


if __name__ == "__main__":
    main()
