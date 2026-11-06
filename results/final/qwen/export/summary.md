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
