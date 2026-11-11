# MCP tool design experiment

This application is only a supporting project for the article
[One Tool per Endpoint Hands the Model Your Frontend's Job](https://davidparry.com/blog/2026/11/06/one-tool-per-endpoint/).
It exists to prove that designing an MCP server is critical, and to show why: a server generated
with one tool per REST endpoint hands the model the orchestration your frontend already does in
code, and the model pays for it on every turn.

One pet-supply order-support API, two MCP servers in front of it, and a benchmark that measures
what each server costs a model in tokens and in mistakes.

- **`mcp-endpoint`** is generated from the app's OpenAPI document: twelve tools, one per REST
  operation, with the raw response bodies passed straight through.
- **`mcp-workflow`** has six tools shaped around the support tasks. Its orchestration is plain Java
  over the same REST API, and it never calls a model.

Both servers call the same API with the same key. Neither depends on `app` at compile time, so
neither can reach the database or the business rules directly. The design is in
[docs/project-plan.md](docs/project-plan.md), the choices the build made are in
[docs/decisions.md](docs/decisions.md), the graded results so far, from a local qwen model,
GPT-5, and Claude Opus 4.8's submission scenarios, are in
[results/final/qwen/export/summary.md](results/final/qwen/export/summary.md),
[results/final/gpt/export/summary.md](results/final/gpt/export/summary.md), and
[results/final/claude/export/summary.md](results/final/claude/export/summary.md), and the talk is in
[talks/speaking.md](talks/speaking.md).

## Results so far

The local qwen model's 290 final runs, GPT-5's 290, and Claude Opus 4.8's 50 submission scenarios
are graded, and every failed run was read in full. The article explains what they show, and
docs/decisions.md has the counts behind each number, under [Results](docs/decisions.md#results)
for qwen, [GPT-5's final runs](docs/decisions.md#gpt-5s-final-runs), and
[Claude's scenario runs](docs/decisions.md#claudes-scenario-runs).

| Model | Server | Task runs that succeeded, of 120 | Median tokens per task run | Submission scenarios that succeeded, of 25 | Median tokens per scenario |
| --- | --- | ---: | ---: | ---: | ---: |
| qwen | endpoint | 93 | 25,757 | 25 | 70,491 |
| qwen | workflow | 116 | 11,803 | 25 | 23,119 |
| GPT-5 | endpoint | 107 | 23,566 | 17 | 49,844 |
| GPT-5 | workflow | 120 | 7,484 | 23 | 17,313 |
| Claude Opus 4.8 | endpoint | not run | not run | 16 | 65,705 |
| Claude Opus 4.8 | workflow | not run | not run | 25 | 25,607 |

Task by task, the endpoint server used more tokens on 23 of the 24 tasks with qwen and all 24 with
GPT-5, 2.2 and 2.8 times as many per task. In runs paired by task and repetition, it failed 23 with
qwen and 13 with GPT-5 that the workflow server passed, and the workflow server never failed a run
the endpoint server passed. [How strong the evidence is](docs/decisions.md#how-strong-the-evidence-is)
has the tests and what they don't show, and [results/final/evidence.md](results/final/evidence.md)
has every number.

GPT-5's runs cost $14.92 at list prices, a median of 6.4 cents per endpoint task run and 2.7 cents
per workflow task run. Claude's 50 scenario runs cost $13.21, and its 240 task runs, projected at
$23 to $29 more, weren't run.
[Hosted runs](docs/decisions.md#hosted-runs) has the checks, the scratch runs, and every cost.

## Modules

| Module | What it is | Port |
| --- | --- | --- |
| `app` | REST API, business rules, H2 fixtures; serves the frontend build | 8080 |
| `frontend` | React + Vite support screens; `npm run dev` proxies `/api` to 8080 | 5173 (dev) |
| `mcp-endpoint` | generated MCP server, Streamable HTTP at `/mcp` | 8081 |
| `mcp-workflow` | task-shaped MCP server, Streamable HTTP at `/mcp` | 8082 |
| `benchmark` | runner, model adapters, verifier, exporters (command line) | none |
| `fixtures/` | seed data, task catalog, frozen tool catalogs | |
| `viewer/` | offline side-by-side run viewer | |
| `results/` | run records; only the frozen final set is committed | |

## Build

Java 21, Maven 3.9, and Node 24.

```bash
mvn package                      # everything, frontend included
mvn package -Dskip.frontend      # backend only
```

## Run

```bash
java -jar app/target/app-0.1.0-SNAPSHOT.jar
java -jar mcp-endpoint/target/mcp-endpoint-0.1.0-SNAPSHOT.jar
java -jar mcp-workflow/target/mcp-workflow-0.1.0-SNAPSHOT.jar
```

Open http://localhost:8080 for the support screens and http://localhost:8080/swagger-ui.html for
the API. The business date is fixed at Friday, November 6, 2026, 9:00 a.m. Central.

The Playwright walk-throughs of the order 1042 and order 1025 exchange previews start their own app
on port 8090. On a new machine, run `npx playwright install chromium` in `frontend/` first.

```bash
mvn -q -pl frontend,app package -DskipTests && (cd frontend && npx playwright test)
```

## Benchmark

The benchmark needs the app under the `benchmark` profile, which turns on the admin endpoints
the runner resets and tags runs with, and both servers. The endpoint server refuses to start
under that profile unless its catalog matches the frozen snapshot.

```bash
scripts/bench-stack.sh up        # builds, starts all three, and stops them on Ctrl-C
```

Hosted model keys come from the environment or a git-ignored `.env` at the repo root
(`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`), and they win over Spring's own `SPRING_AI_ANTHROPIC_API_KEY`
and `SPRING_AI_OPENAI_API_KEY`. The local model is `qwen3.6:35b-a3b-nvfp4` on Ollama, and
the runner won't pull it, so run `ollama pull qwen3.6:35b-a3b-nvfp4` first. Then, from the repo
root:

```bash
mvn -q -pl benchmark package -DskipTests
bench() { java -jar benchmark/target/benchmark-0.1.0-SNAPSHOT.jar "$@"; }
bench --command=check                                     # app, both catalogs, each model with each arm's tools
bench --command=run --phase=pilot --models=qwen --splits=dev --reps=3 --seed=20261106
bench --command=run --phase=scratch --models=qwen --tasks=C1,H1 --reps=1
bench --command=run --phase=pilot --batch=scenarios --models=qwen --splits=scenario --reps=3
bench --command=verify --phase=pilot --batch=pilot-qwen      # grade a batch
bench --command=export --phase=pilot --batches=pilot-qwen,scenarios --name=pilot
bench --command=freeze --pilot=pilot-qwen,scenarios          # after the pilot, on a clean commit
bench --command=run --phase=final --models=qwen --splits=dev,heldout --reps=5
```

A batch is named `<phase>-<models>` unless `--batch` names it, and writes
`results/<phase>/<batch>/manifest.json`, which records the commit, versions,
hashes, model settings, exact tool definitions, and seeded run order, plus `runs.jsonl` with
one record per run. Starting the same batch again skips finished runs, runs setup errors again,
and refuses to continue if anything in the manifest changed.
`--command=catalog --arms=workflow --force` rewrites a catalog snapshot, which is only allowed
before the catalogs are frozen.

`verify` grades each run against its task and writes `verification.jsonl` beside `runs.jsonl`,
then logs the success count for each model, arm, and split and every failed run. `export` grades
the batches it's given and writes `results/<phase>/<name>/export/`: `runs.csv`, `failures.csv`,
`frontend-steps.csv`, `tokens-order-1042.csv`, `summary.json`, and `summary.md`.
`docs/decisions.md` says how runs are graded and what the token counts include.

`freeze` writes `fixtures/freeze.json` with the run limits and the hashes of the seed data, tasks,
conversation, OpenAPI document, and both catalogs. A `final` batch won't start unless all of them
still match, `catalog` won't write a snapshot once they're frozen, and a `final` export won't
combine batches that ran with different hashes or limits. Only `results/final/` is committed.

The hosted models run the same final batches as qwen, one batch per model, so each can have its
own stack. `STACK_PORT` starts a second stack on other ports, and the runner finds it through
`APP_BASE_URL`, `ENDPOINT_MCP_URL`, and `WORKFLOW_MCP_URL`. A batch records its servers, so resume
it on the stack it started on.

Every hosted call goes into `results/logs/spend.jsonl` at list prices, and every runner shares
that file. With `--budget=20`, a batch pauses before any run that could take the total past $20,
exits with status 3, and goes on from the next run when it's started again with a higher budget.
`bench --command=spend` prints the total by model and by phase, model, and arm. A hosted batch
takes hours, so keep the machine awake: a request that's open when it sleeps fails, and the batch
stops as a setup error. On a Mac, run it under `caffeinate -i` and keep a laptop's lid open, since
closing it sleeps the machine anyway.

```bash
bench --command=check --models=claude,gpt
bench --command=run --phase=scratch --models=claude,gpt --tasks=C1 --reps=1 --budget=20
bench --command=run --phase=final --models=claude --splits=dev,heldout --reps=5 --seed=20261106 --budget=20
bench --command=run --phase=final --batch=final-claude-scenarios --models=claude --splits=scenario --reps=5 --seed=20261106 --budget=20

STACK_PORT=9080 scripts/bench-stack.sh up --no-build    # in a second terminal
export APP_BASE_URL=http://localhost:9080 ENDPOINT_MCP_URL=http://localhost:9081 WORKFLOW_MCP_URL=http://localhost:9082
bench --command=run --phase=final --models=gpt --splits=dev,heldout --reps=5 --seed=20261106 --budget=20
bench --command=run --phase=final --batch=final-gpt-scenarios --models=gpt --splits=scenario --reps=5 --seed=20261106 --budget=20
bench --command=spend

bench --command=export --phase=final --batches=final-gpt,final-gpt-scenarios --name=gpt
bench --command=export --phase=final --batches=final-claude-scenarios --name=claude
bench --command=export --phase=final --batches=final-qwen,final-qwen-scenarios,final-gpt,final-gpt-scenarios,final-claude-scenarios --name=all
```

`scripts/evidence_stats.py` reads the qwen, GPT-5, and Claude exports and prints the per-task
ratios, paired runs, exact tests, and intervals that
[How strong the evidence is](docs/decisions.md#how-strong-the-evidence-is) cites. It needs only
Python 3.

```bash
python3 scripts/evidence_stats.py > results/final/evidence.md
```

## Viewer

Open `viewer/index.html` straight from disk; it needs no server and no network. Load a
`runs.jsonl` on each side, plus its `verification.jsonl` for the grades, pick a run, and press
Pair to put the same task and repetition from the other arm beside it. Step or play through the
model calls: each pane shows the customer's messages, the tool calls with their results and the
backend calls under them, the answer card, and the verifier's checks, and the chart puts both
runs' input tokens per model call on one scale.

```bash
node viewer/make-examples.mjs --batches=results/final/final-qwen \
  --pair="Order 1042=final-qwen-C1-endpoint-r1,final-qwen-C1-workflow-r1"
```

bundles chosen pairs into `viewer/examples/examples.js`, which the page offers when it opens.

## License

Copyright 2026 David Parry. Licensed under the Apache License, Version 2.0; see [LICENSE](LICENSE).
