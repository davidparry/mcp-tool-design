# Benchmark summary

290 valid runs of 290.

| Model | Arm | Split | Valid | Success | Median tokens | Median tokens, successes | Median model calls | Median tool calls | Median latency (s) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| qwen | endpoint | dev | 80 | 58 (73%) | 24761.0 | 16768.0 | 7.0 | 5.0 | 5.1 |
| qwen | endpoint | heldout | 40 | 35 (88%) | 29331.5 | 28517.0 | 8.0 | 7.0 | 5.6 |
| qwen | endpoint | scenario | 25 | 25 (100%) | 70491.0 | 70491.0 | 15.0 | 12.0 | 11.7 |
| qwen | workflow | dev | 80 | 77 (96%) | 11803.0 | 11793.0 | 4.0 | 2.0 | 3.2 |
| qwen | workflow | heldout | 40 | 39 (98%) | 11788.0 | 11743.0 | 4.0 | 2.0 | 3.2 |
| qwen | workflow | scenario | 25 | 25 (100%) | 23119.0 | 23119.0 | 6.0 | 3.0 | 6.2 |

## Failures by class

Runs where the class failed the task, and runs where it was only recorded.

| Class | Failed the task | Recorded only |
| --- | --- | --- |
| hallucinated_value | 10 | 0 |
| wrong_constraint_verdict | 8 | 0 |
| model_arithmetic | 0 | 2 |
| wrong_item | 1 | 0 |
| skipped_clarification | 12 | 0 |
| unapproved_action | 1 | 0 |
| off_track | 0 | 28 |
| false_success | 1 | 0 |

## Against the frontend's screens

Single-message tasks, from the app's call log and quotes. The screens check eligibility and stock, then quote every delivery method at every warehouse that has the replacement, and only for what the customer asked about.

| Model | Arm | Options quoted | Runs quoting fewer | Runs missing an option that fits | No stock check | No eligibility check | Refused calls | Quotes nobody asked for | No-match cards labeled otherwise |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| qwen | endpoint | 103 of 180 | 35 of 45 | 7 | 14 of 60 | 14 of 90 | 29 in 19 runs | 12 in 3 runs | 44 of 45 |
| qwen | workflow | 180 of 180 | 0 of 45 | 0 | 0 of 60 | 0 of 90 | 0 in 0 runs | 0 in 0 runs | 2 of 45 |
