# Benchmark summary

290 valid runs of 290.

| Model | Arm | Split | Valid | Success | Median tokens | Median tokens, successes | Median model calls | Median tool calls | Median latency (s) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| gpt | endpoint | dev | 80 | 70 (88%) | 21985.5 | 15755.5 | 6.0 | 6.0 | 58.4 |
| gpt | endpoint | heldout | 40 | 37 (93%) | 23615.5 | 23209.0 | 7.0 | 7.0 | 65.0 |
| gpt | endpoint | scenario | 25 | 17 (68%) | 49844.0 | 46098.0 | 10.0 | 16.0 | 148.6 |
| gpt | workflow | dev | 80 | 80 (100%) | 7339.5 | 7339.5 | 3.0 | 1.0 | 24.5 |
| gpt | workflow | heldout | 40 | 40 (100%) | 8463.5 | 8463.5 | 3.0 | 2.0 | 27.8 |
| gpt | workflow | scenario | 25 | 23 (92%) | 17313.0 | 17313.0 | 5.0 | 2.0 | 59.4 |

## Failures by class

Runs where the class failed the task, and runs where it was only recorded.

| Class | Failed the task | Recorded only |
| --- | --- | --- |
| hallucinated_value | 3 | 0 |
| wrong_constraint_verdict | 3 | 0 |
| model_arithmetic | 0 | 2 |
| wrong_item | 13 | 0 |
| skipped_clarification | 0 | 0 |
| unapproved_action | 0 | 0 |
| off_track | 10 | 59 |
| false_success | 0 | 0 |

## Against the frontend's screens

Single-message tasks, from the app's call log and quotes. The screens check eligibility and stock, then quote every delivery method at every warehouse that has the replacement, and only for what the customer asked about.

| Model | Arm | Options quoted | Runs quoting fewer | Runs missing an option that fits | No stock check | No eligibility check | Refused calls | Quotes nobody asked for | No-match cards labeled otherwise |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| gpt | endpoint | 170 of 180 | 5 of 45 | 0 | 0 of 60 | 0 of 90 | 4 in 3 runs | 85 in 26 runs | 45 of 45 |
| gpt | workflow | 180 of 180 | 0 of 45 | 0 | 0 of 60 | 0 of 90 | 0 in 0 runs | 3 in 3 runs | 5 of 45 |
