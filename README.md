# MCP tool design experiment

One pet-supply order-support API, two MCP servers in front of it, and a benchmark that measures
what each server costs a model in tokens and in mistakes.

- **`mcp-endpoint`** is generated from the app's OpenAPI document: twelve tools, one per REST
  operation, with the raw response bodies passed straight through.
- **`mcp-workflow`** has six tools shaped around the support tasks. Its orchestration is plain Java
  over the same REST API, and it never calls a model.

Both servers call the same API with the same key. Neither depends on `app` at compile time, so
neither can reach the database or the business rules directly. The design is in
[docs/project-plan.md](docs/project-plan.md), the choices the build made are in
[docs/decisions.md](docs/decisions.md), and the talk is in [talks/speaking.md](talks/speaking.md).

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

The Playwright walk-through of the order 1042 exchange preview starts its own app on port 8090:

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
(`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`). The local model runs on Ollama. Then, from the repo
root:

```bash
mvn -q -pl benchmark package -DskipTests
B="java -jar benchmark/target/benchmark-0.1.0-SNAPSHOT.jar"
$B --command=check                                     # app, both catalogs, one request per model
$B --command=run --phase=pilot --models=qwen --splits=dev --reps=3 --seed=20261106
$B --command=run --phase=scratch --models=qwen --tasks=C1,H1 --reps=1
$B --command=run --phase=pilot --batch=scenarios --models=qwen --splits=scenario --reps=3
$B --command=verify --phase=pilot --batch=pilot-qwen      # grade a batch
$B --command=export --phase=pilot --batches=pilot-qwen,scenarios --name=pilot
```

A batch is named `<phase>-<models>` unless `--batch` names it, and writes
`results/<phase>/<batch>/manifest.json`, which records the commit, versions,
hashes, model settings, exact tool definitions, and seeded run order, plus `runs.jsonl` with
one record per run. Starting the same batch again skips finished runs and refuses to continue
if anything in the manifest changed. `--command=catalog --arms=workflow --force` rewrites a
catalog snapshot, which is only allowed before the catalogs are frozen.

`verify` grades each run against its task and writes `verification.jsonl` beside `runs.jsonl`,
then logs the success count for each model, arm, and split and every failed run. `export` grades
the batches it's given and writes `results/<phase>/<name>/export/`: `runs.csv`, `failures.csv`,
`tokens-order-1042.csv`, `summary.json`, and `summary.md`. `docs/decisions.md` says how runs are
graded and what the token counts include.
