# Decisions

Each entry says what was decided, why, and what it changes in the measurement. The project plan is
[project-plan.md](project-plan.md); this file records where the build had to choose.

## Coding standards

Qodo's org rules weren't loaded. `qodo read whoami` answered `invalid_token` on November 6, 2026,
three times, so no ERROR or WARNING rules were applied. The code follows the conventions of the
`factory-springai` repo instead: Boot 4 and Spring AI 2.0 idioms, records for values, constructor
injection, and comments only for constraints the code can't show. Rerun the `qodo-get-rules` skill
after `qodo login` and fix anything it flags before the final run is frozen.

## Versions

| Component | Version | Note |
| --- | --- | --- |
| Java | 21 | Maven runs on JDK 21.0.2 |
| Spring Boot | 4.1.2 | latest GA on Maven Central, November 6, 2026 |
| Spring AI | 2.0.2 | latest GA; 2.1.0 is still a release candidate |
| MCP Java SDK | 2.1.0 | pinned by Spring AI 2.0.2 |
| MCP protocol revision | 2025-11-25 | the newest revision SDK 2.1.0 negotiates |
| springdoc-openapi | 3.2.0 | built against Spring Boot 4.1.2 |
| Anthropic Java SDK | 2.72.0 | pinned by Spring AI 2.0.2 |
| OpenAI Java SDK | 4.80.0 | pinned by Spring AI 2.0.2 |
| React / Vite | 19.3.1 / 8.3.6 | |
| Playwright | 1.62.1 | matches the Chromium build already installed on this machine |

The post and the run manifest cite protocol revision 2025-11-25, not the 2026-07-28 spec the project
plan links, because that's what the client and both servers actually speak.

## The endpoint server is generated

`mcp-endpoint` reads `/v3/api-docs` from the app at startup and registers one tool per operation.
Tool names are the operationIds, descriptions are the operation's summary and description, and input
schemas are the operation's parameters and request body with `$ref`s inlined. Results are the raw
response body as text, and a 4xx or 5xx response comes back with `isError=true` and the
`ProblemDetail` body. The generator has no code for any particular operation.

This is the baseline teams actually ship when they "add MCP" to an existing API, and nothing in it
is broken on purpose. Whatever quality it has comes from the OpenAPI document, which is written for
the frontend's developers and holds the same descriptions a careful team would write.

## Three models

Claude, GPT, and a local qwen through Ollama, as in the Skills vs MCP benchmark. The model IDs are
pinned in `benchmark/src/main/resources/application.yml` and recorded in every run record.

- On November 6 the Anthropic and OpenAI keys in the environment were rejected by both APIs
  (`invalid x-api-key` and `invalid_api_key`). The runner reads keys from the environment or from a
  git-ignored `.env` at the repo root, and the hosted arms run once valid keys are in place.
- `qwen3:30b` isn't installed on this machine, so the local model is its successor in the same
  mixture-of-experts family, chosen in [the pilot section](#pilot) below.

## Persistence and money

- H2 in memory, `schema.sql`, and a loader for `fixtures/seed/*.json`. Reset truncates and reloads
  in milliseconds and needs no Docker on stage. The database isn't what's being tested.
- `Money(BigDecimal amount, String currency)` at scale 2, rounded half up, serialized as
  `{"amount":"15.00","currency":"USD"}`. Every total a customer sees comes from `QuoteService`.

## Dates and delivery

- The business clock is fixed at 2026-11-06T09:00-06:00 (America/Chicago). The benchmark profile can
  advance it, which is how the expired-quote scenario works.
- A replacement ships on the business date. Arrival is that date plus the rate's business days,
  Monday to Friday, with no holiday calendar.
- A delivery deadline is inclusive: "arrive by November 13" accepts November 13.
- Return and exchange windows count calendar days from delivery and include the last day.

## The "exactly at the limit" fixture

The plan asks for an option at exactly $15.00 to test "under $15". Held-out task E3 does the same job
with an option at exactly $25.00 against "under $25". A cost limit carries its comparison
(`under` or `at_most`), so the workflow server and the verifier both treat "under" as strict.

## Workflow tool parameters are camelCase

Spring AI names `@McpTool` parameters after the Java parameters, and `@McpToolParam` has no name
attribute. The workflow tools take `orderReference`, `itemDescription`, and so on, which matches the
camelCase JSON the endpoint tools pass through, so neither arm gets a naming advantage.

## Structured results reach the model as JSON text

`@McpTool(generateOutputSchema = true)` makes Spring AI return `structuredContent` with no text
block. The runner turns a tool result into the model's tool message the same way for both arms: the
text blocks if there are any, otherwise the `structuredContent` serialized as compact JSON. Both arms'
results reach the model as JSON, and the record keeps the exact string the model saw.

## The answer card

Both arms finish by calling a `final_answer` tool that the runner supplies with a fixed schema. The
verifier grades the card, plus the app's state, rather than parsing prose. A run that ends with a
plain reply and no card is recorded with `card = null` and graded on its text where it can be.
