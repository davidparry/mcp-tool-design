# Decisions

Each entry says what was decided, why, and what it changes in the measurement. The project plan is
[project-plan.md](project-plan.md); this file records where the build had to choose.

## Coding standards

Qodo's org rules weren't loaded. `qodo read whoami` answered `invalid_token` three times on
November 6, 2026, and again on November 9, so no ERROR or WARNING rules were applied, and the final
run was frozen without them. The code follows the conventions of the `factory-springai` repo
instead: Boot 4 and Spring AI 2.0 idioms, records for values, constructor injection, and comments
only for constraints the code can't show. Run the `qodo-get-rules` skill after `qodo login` to
check the code against them.

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
  git-ignored `.env` at the repo root, and the hosted arms run once valid keys are in place. Keys
  that work arrived on November 10; [Hosted runs](#hosted-runs) has what happened next.
- `qwen3:30b` isn't installed on this machine, so the local model is its successor in the same
  mixture-of-experts family, chosen in [the pilot section](#pilot) below.
- **The hosted models stay as the plan set them**, `claude-opus-4-8` and `gpt-5`, so the results
  line up with the Skills vs MCP benchmark. Both providers still list them on November 6. Opus 4.8
  won't be retired before May 28, 2027. GPT-5 is deprecated, with `gpt-5.6-sol` as its replacement,
  and leaves the API on December 11, 2026, so its runs have to finish before then. OpenAI's newer
  models aren't a drop-in swap: GPT-6 Astra and GPT-6.1 Sol call tools only through the Responses
  API, and the GPT-5.6 models refuse tools with reasoning on Chat Completions, which is what the
  runner's OpenAI adapter uses.
- **List prices** on November 6, per million tokens: Opus 4.8 is $5 input, $25 output, $0.50 for
  cache reads, and $6.25 for five-minute cache writes. GPT-5 is $1.25 input, $10 output, and $0.125
  for cached input. They're set in `bench.models.<key>.prices` for the export's cost columns. qwen
  runs locally and has none.

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
  isn't needed for qwen. The conversation is frozen with the reminder and without the fallback, so
  the hosted models run the same way.

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
  model requests are retried 3 times, after 10, 20, and 30 seconds, on rate limits, overloads,
  server errors, and timeouts, never on bad keys, bad requests, a spent quota, or a reached spend
  cap. The pilot set the model-call limit; the rule is under "Pilot".
- **One retry policy.** The Anthropic and OpenAI clients would otherwise retry 2 and 3 times on
  their own, where the record can't see it, so their retries are off and each turn's `retries` is
  the whole count. Their timeout is 10 minutes rather than Spring AI's 60 seconds, because a GPT-5
  turn with reasoning and a 16,000-token budget can run past a minute.
- **Provider problems stop the batch.** A request that's still failing after its retries, or an
  account that can't make requests (a bad key, a missing permission, a spent quota, or a reached
  spend cap), says nothing about the model or the arm. The run stops as a setup error, the batch
  stops with it, and starting the batch again runs it again. A request the provider refuses, such
  as a malformed one, ends the run as a model error, and the run is invalid.
- **Preflight.** Before a batch records anything, each model gets one short request per arm that
  carries that arm's tool definitions, so a bad key, a wrong model ID, or a tool schema the
  provider won't accept stops the batch first. `check` sends the same requests.
- **A budget for the hosted runs.** The hosted keys have spending limits, so every call that
  returns usage, preflights included, goes into `results/logs/spend.jsonl` priced at the list
  prices under "Three models", and every runner process appends to that one file. With
  `--budget=20`, a batch pauses before any run that could take the total past $20, meaning the spend
  so far plus the costliest run so far, and exits with status 3. Nothing is cut short, so starting
  the same batch again with a higher budget goes on from the next run. It's an estimate: a request
  that times out after the provider did the work costs money and returns no usage, and two batches
  side by side can each start one run near the limit. `spend` prints the total by model and by
  phase, model, and arm.
- **Credentials.** Keys come from the environment or `.env` and never reach a log line or a
  record. `ANTHROPIC_API_KEY` and `OPENAI_API_KEY` win over Spring's own
  `SPRING_AI_ANTHROPIC_API_KEY` and `SPRING_AI_OPENAI_API_KEY`, which Spring would otherwise read
  first, so a key left over from another project can't be sent instead. Provider errors echo keys back, sometimes masked with a few real characters left, so the
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
  | `skipped_clarification` | an answer picks one of several matching items: it doesn't ask which, and it doesn't cite a quote for each of them | task |
  | `unapproved_action` | more commits than the task allows, or a commit on terms the customer didn't approve | task |
  | `off_track` | unknown tools, bad arguments, tools outside the task's valid paths, or identical repeated calls | process |
  | `off_track` | a run limit is hit, a message gets no answer, or a required check fails with no other explanation | task |
  | `false_success` | an answer reports a submission the store doesn't have, or offers a refund or option that doesn't exist | task |

- One run can record several failures. The exports count runs per class, not failures.

## Exports

- `--command=export` writes one row per run, one row per failure, the per-turn input tokens for
  order 1042 (task C1), and a summary grouped by model, arm, and split, as JSON and Markdown. It
  also writes `frontend-steps.csv`, one row per task run comparing what the run asked the store for
  with what the support screens ask for (see "Against the frontend's screens" under the final run).
- **Tokens.** A task's tokens are every input token plus every output token across all of its model
  calls, the reminder turns included. Anthropic's input count leaves out cache reads and writes, so
  they're added back. Medians and means are reported over all valid runs and over successful runs
  only.
- **Cost** per 1,000 tasks uses each model's list prices from `bench.models.<key>.prices` (see
  "Three models"), with and without the cache-read discount. OpenAI's prompt tokens include its
  cached tokens, so those come out before the input price applies. qwen has no prices, so its cost
  is left empty.

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

## Final run

### What ran

The plan's final run is 720 runs: the 24 tasks in both arms, five repetitions each, with three
models. The hosted keys were still rejected, so qwen ran its 240 as `final-qwen`, and the five
scenarios ran in both arms five times as `final-qwen-scenarios`, 50 more. The 480 Claude and GPT
runs, and 50 scenario runs for each, waited on keys that work. The hashes and limits are frozen, so
those runs can join these results without rerunning qwen.

The first start of `final-qwen` recorded a dirty tree, because the scenario results weren't
committed yet. I stopped it after 7 runs, deleted it, and started it again on `0dd4143`, the commit
that adds the scenario runs. Its manifest records that commit, a clean tree, and the frozen limits
of 50 model calls and 60 tool calls.

All 290 runs were valid. 282 ended with a card. 8 stopped as `missing_card` (6 endpoint, 2
workflow) and were graded on their text. 209 needed the reminder, and the schema check sent 16
cards back. No run came near a limit: the most was 26 model calls and 23 tool calls.

### Before the hosted runs

With no working keys, both hosted adapters ran the whole loop on November 6 against a local
stand-in for the Anthropic Messages and OpenAI Chat Completions APIs. The stand-in replayed passing
qwen runs call for call (C1 and the S2 scenario, in both arms) through the real Spring AI clients,
app, and servers. All eight runs graded the same as the qwen runs they replayed. Every tool result
matched, and every tool call ID came back with its result, through a turn with two calls, the
reminder turns, and S2's second customer message after the clock moved. The stand-in's usage
numbers came through both clients into the right fields, and the cost columns matched a hand count.

The same stand-in checked the failure paths. An overload was retried once after 10 seconds and the
run went on. A 65-second answer didn't time out, and with the timeout cut to 30 seconds for the
test, the request that timed out was retried. A spend-cap error from Anthropic and a quota error
from OpenAI each stopped the batch after one request, and starting it again ran that run again.
That check also found that a stopped batch could refuse to resume: the manifest wrote its
conversation section in an order that can change from one JVM to the next, and the resume check
compared text. It compares values now. The bug could only refuse a resume, never change a record,
so the frozen results stand.

The qwen manifests record the Anthropic and OpenAI SDK versions as `unknown`: those jars carry no
Maven metadata, and the runner read only Maven's. Since November 9 it falls back to the jar
manifest, so the hosted batches record 2.72.0 and 4.80.0. Qwen's runs never touch either SDK.

Once the keys worked, `check` and a scratch batch closed the two pilot checks still open, the
model IDs and each provider's own usage fields. They're under [Hosted runs](#hosted-runs).

### Results

| Arm | Dev tasks | Held-out tasks | Scenarios | Median tokens per task | Median model calls | Tokens, all 120 task runs |
| --- | --- | --- | --- | --- | --- | --- |
| endpoint | 58 of 80 | 35 of 40 | 25 of 25 | 25,757 | 7 | 3,365,868 |
| workflow | 77 of 80 | 39 of 40 | 25 of 25 | 11,803 | 4 | 1,272,747 |

The token and call columns cover the dev and held-out runs. In the scenarios the medians are
70,491 tokens for the endpoint arm and 23,119 for the workflow arm.

- **The gap comes from the turns, not the catalog.** The first call's median input is 2,916 tokens
  in the endpoint arm and 2,446 in the workflow arm. After that the endpoint arm made 885 model calls and
  679 tool calls, where the workflow arm made 420 and 202.
- **Order 1042 (C1)** took the workflow arm 2 model calls and about 6,100 tokens in all five runs.
  The endpoint arm took 6 to 19 calls and 21,788 to 97,424 tokens, with a median of 51,413.
- **One task went the other way.** A3, a held-out status lookup, cost the workflow arm a median of
  11,285 tokens and the endpoint arm 9,429.

### Every failure was read

Every failed run was read in full before any result was used.

**Two verifier changes, both after the run.** Both fix false failures in the endpoint arm's
held-out runs, and both have tests. Together they move 8 runs from failed to passed and change no
other grade in the final run or in either pilot.

- **G3** asks for an exchange on a jacket that hasn't been delivered. Four endpoint answers said,
  correctly, that the exchange can't be set up until it arrives, but labeled the card
  `order_status`. The check read the text only when a card had no outcome, so the label, which is
  meant to be a soft check, failed them. The text now counts under any outcome that doesn't offer an
  answer, and for an item that hasn't been delivered, saying so counts.
- **H3** has two medium harnesses in one order, a blue one and a red one. Four endpoint answers
  quoted both, each under its own quote ID, and the verifier read that as picking one. An answer
  that cites one of the store's quotes for every matching item hasn't picked one. An answer that
  names both and quotes one still has, and that's what every H1 and H2 failure did.

**What stands.** 27 endpoint runs and 4 workflow runs failed. In the endpoint arm:

- **`skipped_clarification`, 10 runs.** Every H1 and H2 run answered for one of the two matching
  items.
- **`wrong_constraint_verdict`, 8 runs.** In E2, four runs marked a $4.99 option as meeting "no
  extra cost". In E3, four marked the $25.00 option as meeting "under $25", and one of them said it
  "meets your under-$25 limit exactly".
- **`hallucinated_value`, 8 runs.** Four D1 and D2 runs put the wrong weekday beside a date, such as
  "Monday, November 10". F1 r3 gave ground and express arrival dates without ever asking for
  delivery options. F2 r5 and F3 r3 told the customer the large was out of stock everywhere and then
  built cards with options anyway. F3's was a $0.00 Columbus ground option marked as meeting every
  limit, which is also a `false_success`. E2 r4 gave the wrong arrival date as well as the wrong
  verdicts.
- **`unapproved_action`, 1 run.** `final-qwen-E2-endpoint-r3` was asked "Just tell me what it would
  take" for an exchange "at no extra cost". It submitted the exchange, EX-7001, with a $4.99
  shipping charge.
- **`wrong_item`, 1 run.** `final-qwen-C1-endpoint-r4` was asked for "a large" of a blue harness and
  decided to "check both blue and red variants". It made three red quotes and listed a large red
  harness beside the blue one as meeting every limit. The customer never named a color, so it's the
  mildest failure here.

In the workflow arm:

- **`skipped_clarification`, 2 runs.** H2 r2 and r4 passed "Squeaky Rope Toy" to `preview_return`,
  the pattern the pilot found.
- **`hallucinated_value`, 2 runs.** `final-qwen-E3-workflow-r1` got every verdict right and copied
  all three quote IDs wrong: EQ-5003 became EQ-5503. `final-qwen-E2-workflow-r3` said the other
  options "range from $5.99 to $6.99", and no tool returned $6.99.

### What the verifier doesn't count

The deadline tasks give no cost limit, but in 11 of the 15 workflow runs on D1, D2, and D3 the
model sent `preview_exchange` a `costLimit` of 0.00 `at_most` anyway. 10 of those 11 answers then
mention a cost limit or $0.00 to the customer. In `final-qwen-D3-workflow-r1`, for example, "no
option meets both your delivery deadline and cost limit of $0.00 additional cost". It started with
the pilot's fix for E2, which says in the `costLimit` description that "no extra cost" is 0.00
`at_most`. Before that wording, 0 of 6 workflow runs on D1 and D2 sent a limit. In the
confirmation run, 3 of 6 did. Every D task is blocked by its deadline, so the extra limit never
changed an outcome. It also never fails a run: the verifier grounds the $0.00 in the tool's
result, which only echoes what the model sent. I've left the grades alone. A check for it would
be a new requirement written after the results to catch one pattern, which isn't the same as
fixing a check that misread an answer. So it's reported here and in the talk instead, along with
its cause: the wording change that fixed E2.

### Against the frontend's screens

The grades check each answer. They don't check whether a run did the work the support screens do,
so the export measures that apart from them, in `frontend-steps.csv` and the last table in
`summary.md`. Both arms are read from the same records: the app's call log for the run and the
quotes the store holds afterward. It makes no difference whether the model made a call or the
workflow server's code did.

The screens are in `frontend/src/screens/`, and their API client, `frontend/src/api.js`, has the
same twelve operations the generator turns into twelve tools. A lookup is a search, the order, and
its items. A return or an exchange adds the eligibility check, and the screen shows the replacement
picker only when the item is eligible. An exchange then gets the variants and the replacement's
stock, and for each warehouse with enough of it, the delivery options and a quote for each method.
For every exchange task those quotes are the task's expected options, so a run's quotes can be
compared with what the screens would show. Two Playwright tests walk the screens for C1 and E1 and
assert their twelve calls. The ambiguous tasks aren't measured, because the screens make the person
pick the item, and neither are the submission scenarios, which end on a person's click.

On the 120 task runs per arm:

| | Endpoint | Workflow |
| --- | --- | --- |
| Options quoted, of those the screens quote | 103 of 180 | 180 of 180 |
| Runs that quoted fewer options than the screens | 35 of 45 | 0 of 45 |
| Runs that left out an option meeting every limit | 7 | 0 |
| Exchange runs with no stock check for the replacement | 14 of 60 | 0 of 60 |
| Return and exchange runs with no eligibility check | 14 of 90 | 0 of 90 |
| Calls the store refused | 29, in 19 runs | 0 |
| Quotes for an item or a color nobody asked about | 12, in 3 runs | 0 |

- **No stock check, one warehouse.** All 14 runs that skipped the stock check quoted from Columbus
  and nowhere else. With no stock lookup, the only warehouse they had was the one the tool schemas
  name: `create_exchange_quote`'s `warehouseId` has `example: "WH-CMH"`, which springdoc took from
  the `@Schema` annotation written for the API's own documentation, and `get_delivery_options`
  describes its parameter as "such as WH-CMH". In E1 that left all five endpoint answers telling the
  customer the XL bed costs $47.99 and arrives November 16, and run 3 called $47.99 "the cheapest
  option". Reno has it for $45.00 by November 10, which is what the screens and all five workflow
  answers show. Nothing fits under $30 either way, so all five pass. `final-qwen-C1-endpoint-r2`,
  the cheapest endpoint run on order 1042, skipped the stock and delivery checks and passed because
  Columbus is where the option that fits ships from.
- **Warehouses that don't exist.** Three refused calls asked for `WH-ATL`, `WH-ORD`, and `WH-AUS`,
  in the same pattern as `WH-CMH`. The other 26 were quotes at a warehouse without the stock, in
  D3, E3, and the out-of-stock F tasks.
- **No eligibility check did no harm.** B1 and B3 went straight to a return quote, and G3 r1 to r4
  read an order status that says the jacket hasn't been delivered. The quote endpoints check
  eligibility themselves, so none of these runs could have quoted an item that wasn't eligible.
- **Quotes nobody asked for.** `final-qwen-C1-endpoint-r4` made three red quotes (the `wrong_item`
  above), and F1 r2 and r4 made nine quotes for a blue XL harness when the customer asked for red.
- **The 7 runs that left out a fit** each quoted the cheapest option that fits. C2 r2 and r5 showed
  two of C2's four, and C3 r3, r4, and r5 one of two options that cost the same.

None of this changes a grade. Grading it would add requirements written after the results, the
reason the $0.00 limit above isn't graded either. Backend-call counts aren't compared on their own,
because a model that's given an order number doesn't need the search the screens make, and the
workflow server makes more calls than the screens on some tasks, all of them in code.

## Hosted runs

### Checks

- **The first `check` sent the wrong Anthropic key.** The shell also exported
  `SPRING_AI_ANTHROPIC_API_KEY`, holding an old key, and Spring reads it ahead of the
  `${ANTHROPIC_API_KEY}` placeholder, so Anthropic answered `invalid x-api-key`. The documented
  variables win now (see "Credentials"), and `check` passed for both models with both arms' tools.
- **The two open pilot checks are closed.** Anthropic reports `claude-opus-4-8` on every turn, and
  OpenAI reports `gpt-5-2025-08-07`, the snapshot behind `gpt-5`. Every hosted turn so far has the
  provider's raw usage: Anthropic's has cache read and cache creation counts, zero because no prompt
  caching is set, and OpenAI's has cached prompt tokens, and reasoning tokens that its output count
  includes.
- **A scratch batch ran C1 and H1 once per model and arm**, 8 runs, and 7 passed. GPT-5's C1
  endpoint run offered three large red quotes beside the blue one, all four marked as meeting the
  limits, the same `wrong_item` as `final-qwen-C1-endpoint-r4`. GPT-5's H1 endpoint run asked the
  right question but wrote its card as JSON text instead of calling `final_answer`, before and after
  the reminder, so it stopped as `missing_card`, was graded on its text, and passed. Scratch runs
  aren't results, and they aren't committed.

### Cost

The checks and the scratch batch cost $0.74 at list prices: $0.51 for Claude and $0.23 for GPT-5.
Scaled to the full set, 290 runs per model, two ways (the scratch runs' average cost per run, and
their tokens against qwen's on the same tasks), the estimate was $25 to $27 for Claude and $13 to
$14 for GPT-5. Claude's cost is mostly input, sent again at full price on every turn. GPT-5's is
mostly reasoning output, and OpenAI cached about two-thirds of its input without being asked.
Priced as if each turn had read the previous turn's prompt from Anthropic's prompt cache and written
the rest to it, Claude's four scratch runs would have cost 17% to 55% less, the most on the six-turn
endpoint run, which would bring its set to about $16 to $17. Caching isn't set.

The hosted runs have a $20 budget, so GPT-5's 290 run first with `--budget=20`, and Claude's
haven't run. GPT-5 [leaves the API](https://platform.openai.com/docs/deprecations) on December 11,
2026, and Opus 4.8 [stays](https://docs.claude.com/en/docs/about-claude/model-deprecations) until at
least May 28, 2027.

### GPT-5's final runs

`final-gpt-scenarios` started on November 10 at `4724db7` with a clean tree, and `final-gpt`
starts once its results are committed, the order the qwen batches ran in. The batch stopped twice
on a setup error, both times because the laptop's lid was closed while it ran on battery, at 10:31
and again at 11:14. It went to sleep with a request open, and the request failed once it woke:
`final-gpt-S4-endpoint-r3` on its seventh model call and `final-gpt-S3-endpoint-r2` on its third.
Starting the batch again ran each one again from the beginning. The calls before the failures cost
$0.06, which the ledger counts. The rest ran under `caffeinate -i` with the lid open; `caffeinate`
holds off idle sleep but not a closed lid.

GPT-5 succeeded in 17 of 25 endpoint scenario runs and 23 of 25 workflow runs, where qwen
succeeded in all 50. All ten failures were read in full, and they're the same `off_track`: the
customer said "The Columbus option works for me. Please go ahead and submit that exchange," and
GPT-5 asked which Columbus option instead of submitting the one that fits. All ten are in S1 and
S4. In S2, S3, and S5 a run can pass without submitting (the quote has expired, the stock sells
out, the customer wants to think it over), and all 30 passed in both arms.

- **Its own red quotes, in seven endpoint runs.** S1 r1, r4, and r5 and S4 r1, r2, r3, and r5
  quoted large red harnesses beside the blue one, as the scratch C1 run did, and offered a red one
  from Columbus at the blue one's price and arrival. Asked "Did that go through? Please submit it
  again just to be sure," each S4 run said nothing had been submitted and asked again. 18 of the 25
  endpoint scenario runs quoted red. The two blue-only S1 runs submitted.
- **An option that doesn't fit, in three runs.** S4 endpoint r4, which quoted only blue, and S4
  workflow r1 listed Columbus Express at $19.99 as over the limit and then asked whether the
  customer meant Ground or Express. S1 workflow r1 named the right quote but asked the customer to
  reply "Submit EQ-5001" first.
- **No duplicate submission.** No S4 run created a second exchange, so its duplicate check never
  came into play. The four S4 workflow runs that submitted each made one.
- **Cards written as text.** Eight endpoint runs ended as `missing_card`, having written the card
  as JSON text instead of calling `final_answer`, before and after the reminder. Three of them
  passed on their text. No workflow run ended that way.
