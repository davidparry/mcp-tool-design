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

The generator had to make a few choices that any generator makes:

- Tools are listed in operationId order, so the catalog doesn't change when springdoc reorders paths.
- A tool's description is the summary, a blank line, and the description. Parameter descriptions
  move onto their argument's schema.
- The JSON request body's properties become top-level arguments next to the path and query
  parameters, which is how most generators flatten a body. The forwarder sends everything that isn't
  a path or query parameter as the body.
- `readOnlyHint` is true for GET and false otherwise. The generator can't know whether a POST is
  destructive, so `destructiveHint` stays unset.
- The document's `servers` entry is ignored. The base URL comes from configuration.

The catalog is frozen by SHA-256 over canonical JSON (keys sorted, no whitespace) of what
`tools/list` returns. The snapshot is `fixtures/catalogs/endpoint-tools.json`, and under the
`benchmark` profile the server won't start if the generated catalog's hash differs from it. Two tests
keep the chain honest: the app checks that the committed copy of its OpenAPI document matches what it
serves, and the endpoint module checks that the snapshot is what the generator makes from that copy.
`scripts/refresh-endpoint-catalog.sh` refreshes both, and it stops being allowed once the run is frozen.

## The SDK validates arguments for both servers

MCP Java SDK 2.1.0 checks every `tools/call` against the tool's input schema before the handler
runs. A violation comes back as `isError=true` with text like `Tool (create_exchange_quote) input
validation failed: ... [/deliveryMethod: does not have a value in the enumeration ["GROUND",
"EXPRESS"]]`, and the API is never called. Both servers run on the same SDK, so both arms get the
same check. The failure classifier counts these refusals as schema errors.

## Three models

Claude, GPT, and a local qwen through Ollama, as in the Skills vs MCP benchmark. The model IDs are
pinned in `benchmark/src/main/resources/application.yml` and recorded in every run record.

- On November 6 the Anthropic and OpenAI keys in the environment were rejected by both APIs
  (`invalid x-api-key` and `invalid_api_key`). The runner reads keys from the environment or from a
  git-ignored `.env` at the repo root, and the hosted arms run once valid keys are in place.
- `qwen3:30b` isn't installed on this machine, so the local model is its successor in the same
  mixture-of-experts family, chosen in [the pilot section](#pilot) below.
- The hosted IDs stay as the plan set them, `claude-opus-4-8` and `gpt-5`, until a valid key lets the
  `check` command confirm them.

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

## The workflow server's choices

- Six tools, each one a support task: `find_orders`, `get_order_context`, `preview_exchange`,
  `preview_return`, `submit_exchange`, and `submit_return`. They call the same REST API the frontend
  calls, through the server's own client records. Nothing is shared with the app's code.
- Item matching is deterministic. Each line scores 3 for a category word ("harness", "jacket"), 2 per
  product-name word ("trailblazer", "chew"), and 2 for a mentioned size or color it has. A mentioned
  size or color it doesn't have costs 10. A tie at the top returns `needs_clarification` with the
  candidates, and a best score of 0 or less returns `item_not_found` with the order's items. "Toy" is
  only a category word, so "the toy from order 1022" is ambiguous between the rope toy and the balls.
- A cost limit is `{amount, comparison}` with `under` (strict) or `at_most`. The tool compares, so the
  model never does arithmetic on money.
- A preview quotes every in-stock warehouse and method and returns up to 3 options that meet every
  limit, cheapest first, and up to 3 that don't. Each rejected option says which limit it misses, by
  how much, and they're ordered by fewest misses, then earliest arrival when there's a deadline,
  otherwise lowest cost. `earliestArrival` and `lowestAdditionalCost` cover every quote, including
  those not listed, and `moreAvailable` says when more options met the limits than are listed.
- Quantity defaults to 1 for both previews. An omitted replacement size or color keeps the current
  one.
- Expected refusals are results with an outcome, not MCP errors: `quote_expired`,
  `stock_unavailable`, `ineligible`, and so on, each with a `nextStep`. Only unexpected failures come
  back with `isError`.
- Annotations state the real side effects. Lookups are read-only and idempotent. Previews aren't
  read-only, since they create draft quotes, and they aren't destructive. Submits are destructive and
  idempotent. Every tool is `openWorldHint=false`. The runner doesn't pass annotations to the models,
  so they describe the design and don't change the measurement.

## Workflow tool parameters are camelCase

Spring AI names `@McpTool` parameters after the Java parameters, and `@McpToolParam` has no name
attribute. The workflow tools take `orderReference`, `itemDescription`, and so on, which matches the
camelCase JSON the endpoint tools pass through, so neither arm gets a naming advantage.

## Structured results reach the model as JSON text

With `@McpTool(generateOutputSchema = true)`, Spring AI 2.0.2 returns `structuredContent` and a text
block holding the same object as compact JSON. The runner turns a tool result into the model's tool
message the same way for both arms: the text blocks if there are any, otherwise the
`structuredContent` serialized as compact JSON. Both arms' results reach the model as JSON, and the
record keeps the exact string the model saw.

The SDK also validates every structured result against the tool's output schema. Spring AI marks a
record component required unless it's `@Nullable`, so the workflow results mark every field that an
outcome can leave out, and leave it out rather than send `null`.

## Workflow results keep the store's time zone

Jackson shifts parsed `OffsetDateTime` values to UTC by default, which turned the API's
`2026-11-06T09:30:00-06:00` into `15:30Z`. The workflow server turns that off, so both arms see the
same `-06:00` times and neither has to convert time zones.

## The answer card

Both arms finish by calling a `final_answer` tool that the runner supplies with a fixed schema. The
verifier grades the card, plus the app's state, rather than parsing prose.

- The runner answers `final_answer` with `{"status":"delivered"}` and never forwards it to a
  server. In a scenario, the next customer message follows that acknowledgement.
- A card that breaks the schema isn't delivered. The runner answers with the validator's error, the
  same one the MCP SDK gives for bad tool arguments, and the model can call again.
- A plain reply gets one reminder per customer message, sent as a user turn in the same words for
  both arms. A second plain reply ends that message with no card, recorded as `null` and graded on
  its text where it can be. The reminder turn's tokens count against the run that needed it, and
  `totals.reminders` says how many it took.
- In smoke runs before the pilot, qwen replied in plain text first in 7 of 8 runs. Saying that the
  customer never sees text outside a tool call cut that to 4 of 8, and a reminder that names the
  tool and the field got a card in all 8. The plan's fallback, a fenced JSON block in the reply,
  isn't needed for qwen; the pilot decides for the hosted models.

## The runner's choices

- **One request per turn.** Spring AI 2.0's `ChatModel` sends tool definitions but doesn't run tools
  (tool execution moved to `ChatClient`), so calling `ChatModel` directly gives the runner every tool
  call. The plan's `internalToolExecutionEnabled(false)` no longer exists and isn't needed.
- **What the model is sent.** Each MCP tool's name, description, and input schema, exactly as the
  server lists them and in its order, plus `final_answer`. Titles, output schemas, and annotations
  aren't sent, so neither arm gets hints the other doesn't. The manifest keeps the definitions and
  every record carries their hash.
- **One run at a time against one app.** Each run resets the store, tags the call log with its run
  ID, and opens its own MCP session. Backend calls are matched to the tool call whose start and end
  bracket the moment the app logged them.
- **Catalogs are checked twice.** Before a batch, each arm's live catalog must match its snapshot in
  `fixtures/catalogs/`. During the batch, every session's catalog must match the one the batch was
  planned with, or the run stops as a setup error and the batch stops with it.
- **Order and seeds.** Each model's runs are shuffled together across arms, tasks, and repetitions
  with the batch seed, so neither arm gets the warmer cache or the quieter hour. Each run's sampling
  seed comes from the batch seed and the run ID, so repetitions differ and a rerun repeats.
- **Limits.** 50 model calls and 60 tool calls per run, with `final_answer` not counted. Failed
  model requests are retried 3 times on rate limits, overloads, server errors, and timeouts, never
  on bad keys or bad requests. The pilot set the model-call limit; the rule is under "Pilot".
- **Credentials.** Keys come from the environment or `.env` and never reach a log line or a
  record. Provider errors echo keys back, sometimes masked with a few real characters left, so the
  runner redacts anything key-shaped before it logs or records an error.
- **Usage.** Each turn keeps Spring AI's normalized counts and the provider's own fields. Anthropic's
  input tokens leave out cache reads and writes, OpenAI's prompt tokens include cached tokens, and
  Ollama reports no native usage, so its prompt and eval counts and timings come from the response
  metadata.

## The task catalog

- 24 preview tasks, 16 dev and 8 held out, each with its expected outcome. Every task also names
  `permittedActions` (0 for all of them, since none asks for a submission) and, for each arm, the
  tools a valid path through the task needs. A call outside that list is recorded as off-track work
  but doesn't fail the task on its own.
- 5 submission scenarios sit in their own split and are reported apart from the preview comparison,
  as the plan says. Each starts with C1's request, then the customer answers: S1 approves the
  Columbus option, S2 approves it after 31 minutes, S3 approves it after Columbus runs out of the
  large harness, S4 approves and then asks for a second submission "to be sure", and S5 holds off.
  A scenario's world change (the clock or the stock) happens just before the message it belongs to.
- **What an approval covers.** The customer approves terms: warehouse, method, amount, and arrival
  date. In S2 the agent may re-quote and submit if those terms are unchanged, or explain that the
  quote expired and show the option again. Committing anything else is an unapproved action. The
  first draft said nothing could be committed after the expiry, and the first smoke run broke it:
  qwen in the endpoint arm re-quoted Columbus ground at the same $11.99 and November 12 arrival and
  submitted it. Counting that as unapproved would have charged the endpoint arm for a choice a
  reader could fairly defend. S3 is the scenario where the terms do change.

## The verifier

- **What it grades.** The card for each customer message, plus the store's state after the run. A
  message with no card is graded on the reply's text where it can be, and recorded as off-track; a
  message with no reply at all fails the task.
- **Valid runs.** A setup error or a model error that outlasts the retries makes a run invalid. It's
  counted, but it isn't a failure and doesn't enter a success rate.
- **Success** means no task-level failure and every required check passing. Soft checks, such as
  naming the cheapest option, the outcome label, the blocking reason, and the exchange ID, are
  recorded but don't decide success.
- **Grounding.** Every amount, date, SKU, quote ID, and exchange or return ID in an answer has to
  appear in a tool result from that run, or in what the customer said. A weekday the customer names
  grounds its next date, so "by Saturday" covers November 7. A weekday written beside a date has to
  be that date's weekday. The tools return ISO dates only, so a weekday is the model's own calendar
  math in both arms; the first scratch batch had an endpoint answer with the right dates and three
  wrong weekdays, one of them beside the customer's Monday deadline.
- **Arithmetic.** An amount no tool returned that equals a sum, a difference, or a double or triple
  of amounts from the tools or the customer is `model_arithmetic`. It fails the task when it
  contradicts a refund or an option's cost; otherwise it's recorded, since a correct "misses your
  limit by $15" is no error. Any other unsupported amount is a `hallucinated_value`.
- **The eight classes.** Each failure is task-level, which fails the run, or process-level, which
  is recorded only.

  | Class | Recorded when | Level |
  | --- | --- | --- |
  | `hallucinated_value` | an amount, date, weekday, SKU, ID, status, or refund isn't what the tools returned | task |
  | `wrong_constraint_verdict` | an option is marked as meeting every limit when it doesn't, or the reverse, or "no option" is given when one exists | task |
  | `model_arithmetic` | an unsupported amount is the model's own sum or difference | task if it contradicts a refund or an option's cost, otherwise process |
  | `wrong_item` | a quote or commit is for a different item or replacement than the task asks about | task if the answer rests on it, otherwise process |
  | `skipped_clarification` | an answer picks one of several matching items instead of asking | task |
  | `unapproved_action` | more commits than the task allows, or a commit on terms the customer didn't approve | task |
  | `off_track` | unknown tools, bad arguments, tools outside the task's valid paths, or identical repeated calls | process |
  | `off_track` | a run limit is hit, a message gets no answer, or a required check fails with no other explanation | task |
  | `false_success` | an answer reports a submission the store doesn't have, or offers a refund or option that doesn't exist | task |

- One run can record several failures. The exports count runs per class, not failures.

## Exports

- `--command=export` writes one row per run, one row per failure, the per-turn input tokens for
  order 1042 (task C1), and a summary grouped by model, arm, and split, as JSON and Markdown.
- **Tokens.** A task's tokens are every input token plus every output token across all of its model
  calls, the reminder turns included. Anthropic's input count leaves out cache reads and writes, so
  they're added back. Medians and means are reported over all valid runs and over successful runs
  only.
- **Cost** per 1,000 tasks uses each model's prices from `bench.models.<key>.prices`, with and
  without the cache-read discount. It's left empty until prices are set.

## Pilot

### What ran

The plan's pilot is 96 runs: the 16 dev tasks in both arms with all three models. Both hosted keys
were rejected on the day (401 from Anthropic and from OpenAI), so the pilot ran qwen alone with three
repetitions, which keeps the count and adds a read on run-to-run variation. That leaves two of the
plan's pilot checks open until the keys work: that the raw usage fields arrive from all three
providers, and that `claude-opus-4-8` and `gpt-5` are the right IDs. For qwen, Spring AI's normalized
counts arrive, Ollama sends no native usage, and the prompt and eval counts and timings come from the
response metadata.

The first pass was 96 dev runs and 30 scenario runs (5 scenarios, both arms, three repetitions), all
valid and all answered. Regraded with the verifier as it stands now:

| Arm | Dev tasks | Scenarios | Median tokens per dev task | Median model calls |
| --- | --- | --- | --- | --- |
| endpoint | 33 of 48 | 15 of 15 | 20,550 | 6 |
| workflow | 44 of 48 | 15 of 15 | 11,729 | 4 |

96 of the 126 runs needed the reminder once: qwen often writes its reply as text before it calls
`final_answer`. Every run still ended with a card, so the plan's fallback isn't needed for qwen.

### What the pilot changed

Every failed run was read in full before anything changed. Five changes came out of that, all before
the freeze. The held-out tasks weren't run in the pilot.

- **The workflow server named limits it hadn't checked.** In E2 the customer asks for an exchange "at
  no extra cost". In all three workflow runs the model left out `costLimit`, and `preview_exchange`
  answered "6 of 6 quoted options meet every limit", which the model repeated, though every option
  costs at least $4.99 in shipping. The message now names the limits it checked, or says none was
  given, and the next step tells the model to preview again if the customer gave one. The
  `costLimit` description now says that "no extra cost" is 0.00 `at_most`. The workflow catalog's
  hash moved from `2c2d4c9e…` to `585bb142…`. The endpoint server isn't tuned the same way: by
  design it's the OpenAPI document as the generator renders it.
- **`final_answer` is checked against its schema.** The runner checks each card with the MCP SDK's
  own validator, the one that checks MCP tool arguments, so a malformed card gets the validator's
  error and another try, just as a malformed MCP call does. 4 of 162 pilot cards broke the schema:
  three in the workflow arm left out `outcome`, and one in the endpoint arm sent `options` as a
  string, which hid an invented option from the verifier.
- **Model-side arithmetic fails a task only when it's wrong.** The talk defines it as adding amounts
  up yourself "and getting it wrong". The first verifier failed any amount the model worked out, so
  "misses your $30 limit by $15" failed two correct workflow answers. A worked-out amount now fails
  the task when it contradicts a refund or an option's cost, and is recorded otherwise. The
  customer's own amounts count when the verifier rebuilds one.
- **A weekday beside a date has to be that date's weekday,** from the first scratch batch, and
  **S2's approval covers the same terms,** from the first scenario smoke run. Both are described
  above.

### The confirmation run

The same 126 runs ran again on the same seed after those changes, as `pilot-qwen-v2` and
`pilot-qwen-v2-scenarios`:

| Arm | Dev tasks | Scenarios | Median tokens per dev task | Median model calls |
| --- | --- | --- | --- | --- |
| endpoint | 32 of 48 | 15 of 15 | 21,144 | 6 |
| workflow | 46 of 48 | 15 of 15 | 11,832 | 4 |

- **E2 passes in the workflow arm.** All three runs sent "no extra cost" as 0.00 `at_most` on the
  first call, and `preview_exchange` answered that no option costs at most $0.00.
- **The schema check sent 7 cards back,** one each in 7 runs, and every one came back valid on the
  next try.
- **The workflow arm's two failures are H2,** "the toy from order 1022", where the order has a rope
  toy and a ball 3-pack. Across both pilots, 3 of the 6 H2 workflow runs read the order first and
  then passed "Squeaky Rope Toy" to `preview_return` instead of the customer's "toy", so the server
  matched one item and never saw the ambiguity. The parameter already asks for the item "in the
  customer's words". I left it alone: another round of tuning on a dev task, after the
  confirmation run, starts to fit the workflow server to the benchmark, and a task-shaped tool that
  can still be talked around belongs in the talk.
- **One verifier change, which moves no success count.** In F1 one endpoint run quoted the XL in
  blue when the XL in red was out of stock everywhere, then offered the blue quotes as meeting every
  limit. The verifier called those options invented, but the tools had returned them. An offered
  option that matches a quote the store holds is no longer a `hallucinated_value`, and an
  out-of-stock task now checks the quotes behind an offered option, so the run records
  `false_success` and `wrong_item`.
- **One run ended without a card.** In G2 qwen answered in text, got the reminder, and sent back
  107 tokens with no text and no tool call, most likely a tool call Ollama couldn't parse. The run
  stopped as `missing_card`, and the verifier graded the text, which was right. It's 1 of 252
  pilot runs.

**The limits.** 2.5 times the most that any answered pilot run needed, rounded up to the next 10,
and never below the starting limits. The most was 17 model calls and 14 tool calls, so the final
limits are 50 model calls and 60 tool calls.

**The freeze.** `fixtures/freeze.json` holds those limits and the hashes of the seed data, the
tasks, the conversation, the OpenAPI document, and both catalogs, taken on the commit after this
run. The held-out tasks hadn't run before it.

### The local model

`qwen3:30b` isn't installed, so the local model is `qwen3.6:35b-a3b-nvfp4`, the same
mixture-of-experts family with 3B active parameters. Two builds were installed, and both completed
the same 8 smoke runs (A1, C1, D1, and H1 in both arms) with a card every time. The `nvfp4` build was
1.5 to 2 times faster, so it runs the benchmark. Thinking is off, with Qwen's suggested non-thinking
sampling: temperature 0.7, top-p 0.8, top-k 20, and a 32K context.
