# Benchmark summary

50 valid runs of 50.

| Model | Arm | Split | Valid | Success | Median tokens | Median tokens, successes | Median model calls | Median tool calls | Median latency (s) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| claude | endpoint | scenario | 25 | 16 (64%) | 65705.0 | 68118.5 | 9.0 | 11.0 | 32.3 |
| claude | workflow | scenario | 25 | 25 (100%) | 25607.0 | 25607.0 | 5.0 | 2.0 | 16.3 |

## Failures by class

Runs where the class failed the task, and runs where it was only recorded.

| Class | Failed the task | Recorded only |
| --- | --- | --- |
| hallucinated_value | 9 | 0 |
| wrong_constraint_verdict | 0 | 0 |
| model_arithmetic | 0 | 10 |
| wrong_item | 0 | 0 |
| skipped_clarification | 0 | 0 |
| unapproved_action | 0 | 0 |
| off_track | 0 | 2 |
| false_success | 0 | 0 |

## Against the frontend's screens

Single-message tasks, from the app's call log and quotes. The screens check eligibility and stock, then quote every delivery method at every warehouse that has the replacement, and only for what the customer asked about.

| Model | Arm | Options quoted | Runs quoting fewer | Runs missing an option that fits | No stock check | No eligibility check | Refused calls | Quotes nobody asked for | No-match cards labeled otherwise |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
