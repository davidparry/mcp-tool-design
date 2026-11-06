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
