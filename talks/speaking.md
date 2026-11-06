# Same API, Two MCP Servers: The Price of One Tool per Endpoint

A conference session built on this repository: one Spring Boot application, two MCP servers in front of it, and a benchmark that measures what each server costs a model in tokens and in mistakes.

**Speaker:** David Parry ·
[davidparry.com](https://davidparry.com) ·
[github.com/davidparry](https://github.com/davidparry)

**Status:** measured November 6, 2026, with one model. The local qwen model's 290 final runs are in, graded, and read. The Claude and GPT runs (480 more) are waiting on API keys that work, so every number below is qwen's until they're in. [`docs/decisions.md`](../docs/decisions.md) records how the runs were graded and every change made along the way.

Other titles under consideration:

- Designing MCP Tools Around the Task (shared with the post)
- Twelve Tools for One Question

---

## Short abstract

For CFP forms with a length cap.

> Wrapping each REST endpoint in an MCP tool hands the model the orchestration your frontend already does in code. This session puts two MCP servers in front of one Spring Boot order-support app, one generated with a tool for each of the twelve endpoints its React screens call and one with six tools shaped around the support tasks, and gives the same model the same requests against both. With a local qwen model, the endpoint server used a median of 25,757 tokens per task against 11,803, and it failed 27 of 120 task runs against 4. One of those failures was an exchange it submitted after the customer said "just tell me what it would take". The runs play side by side: the turns, the tool calls, the tokens on each turn, and every failure, with its run ID.

## Abstract

**Wrapping each REST endpoint in an MCP tool hands the model the orchestration job your frontend already does in code, and you pay for that job on every model turn.** That's the claim this session tests, and with the first model measured, it holds.

The test bed is a small Spring Boot and React order-support app for a pet-supply store that sells dog harnesses and toys. Its REST API was built for its four React screens: the screens' API client makes twelve calls, and the screens decide in code which ones to make, in what order, and what to check between them. Two MCP servers sit in front of the same HTTP APIs with the same permissions. The endpoint server is generated from the app's OpenAPI document, the way most teams add MCP to an API they already run: twelve tools, one per REST operation and one per call the screens make, passing the application's own response DTOs straight through. It's typed and documented, and nothing in it is broken on purpose. The workflow server has six tools shaped around the support job. One call previews an exchange; a separate call submits the quote somebody approved. Its orchestration is plain Java, and it never calls a model.

I give both servers the same system prompt, the same model (so far a qwen running locally on Ollama, with Claude and GPT to follow), and this request:

> Can I exchange the medium dog harness in order 1042 for a large, have it arrive by November 13, and keep the additional cost under $15? Show my options before submitting anything.

The workflow server answered it in 2 model calls and about 6,100 tokens, all five times. The endpoint server took 6 to 19 calls and 21,788 to 97,424 tokens. In its longest run it decided to check red harnesses as well as blue, and offered a large red one the customer never asked for.

On stage the two runs play side by side: each model turn, each tool call, the backend calls underneath it, and the input tokens per turn (including every earlier tool result, which the model reads again on each new turn). Tokens are half the story. The other half is what a model does with twelve tools and raw DTOs. Across the final run it called a $25.00 option "under $25", put the wrong weekday beside a delivery date, answered for one of two matching items, offered an exchange on a collar that was out of stock everywhere, and once submitted an exchange the customer had only asked about. Every failure the benchmark records gets a class, and the session shows those transcripts next to the averages. It also skipped steps the screens never skip. Measured from the app's own call log, 14 of its 60 exchange runs never checked the replacement's stock and quoted only the warehouse the tool schema names as an example. In one task that left five passing answers telling the customer $47.99 when the store had the bed for $45.00.

None of this makes the workflow server free. It's more code to write and keep correct, and it covers only the tasks it was designed for. On a plain order-status lookup it cost more than the endpoint server, a median of 11,285 tokens against 9,429. It isn't foolproof either: two of its runs talked past the ambiguity it was built to catch, and two told the customer a quote ID or a price that no tool returned. Those results are in the session too, from 290 runs so far: 24 tasks, five repetitions each, on both servers, plus five submission scenarios, with eight of the tasks held out until the tool contracts were frozen.

It's the follow-up to my [Skills versus MCP benchmark](https://davidparry.com/blog/2026/07/20/skills-vs-mcp-what-the-token-bill-actually-measures/), which moved business rules out of the model and into code. This one asks what shape the tools around that code should take.

## What attendees leave with

- A way to decide, endpoint by endpoint, whether a pass-through tool is enough or a tool shaped around the task earns its maintenance cost.
- Design rules for task-shaped tools, each one visible in the workflow server's code: business inputs (an order reference and an item description) where the endpoints want internal IDs, deterministic matching that returns candidates when a description fits more than one item, explicit outcomes (`options_available`, `ineligible`, `no_matching_option`, `needs_clarification`), previews that can't commit anything, and a submit step that takes a selected quote and revalidates it.
- How to count what a task costs: add up the model-reported usage for every turn with the history included, and keep cache reads and writes in their own columns so nothing gets counted twice.
- A failure taxonomy to run against your own agent transcripts.
- A check the grades miss: compare what an agent asked your API for with what your own frontend asks for on the same task, using the call log you already keep.
- The Spring Boot and Spring AI code for both servers and the benchmark runner, so you can repeat the comparison against your own API.

## What the final run measured

`qwen3.6:35b-a3b-nvfp4` on Ollama, thinking off, 50 model calls and 60 tool calls per run at most. The batches are `results/final/final-qwen` and `results/final/final-qwen-scenarios`, and the export is `results/final/qwen/export/`.

| | Endpoint server | Workflow server |
| --- | --- | --- |
| Dev task runs that succeeded | 58 of 80 | 77 of 80 |
| Held-out task runs that succeeded | 35 of 40 | 39 of 40 |
| Submission scenarios that succeeded | 25 of 25 | 25 of 25 |
| Median tokens per task | 25,757 | 11,803 |
| Tokens across the 120 task runs | 3,365,868 | 1,272,747 |
| Model calls and tool calls across the 120 task runs | 885 and 679 | 420 and 202 |
| Median input tokens on the first model call | 2,916 | 2,446 |
| Median tokens per submission scenario | 70,491 | 23,119 |
| Dev task latency, median and p95 | 5.1 s and 11.1 s | 3.2 s and 6.8 s |

- **The catalogs cost about the same; the turns don't.** The first call's input differs by about 470 tokens. The gap comes after it, because every turn reads every earlier result again. In order 1042's first run the endpoint server's input climbed from 2,929 tokens on the first call to 5,701 on the twelfth.
- **Where the claim is weaker.** A3, a status lookup, cost the workflow server more. Both servers got all 25 submission scenarios right, and the endpoint server took three times the tokens to do it. H3 passed on both servers, and the endpoint server got there by quoting both matching harnesses, at a median of 44,434 tokens against 11,865.
- **Dev and held-out are reported apart.** No held-out task ran before the freeze. The endpoint server did better on them than on the dev tasks, and the workflow server did about the same.
- **A tool description is code too.** The pilot fixed E2 by telling the workflow server's `costLimit` parameter that "no extra cost" means 0.00. After that, 11 of 15 workflow runs on the deadline tasks sent a $0.00 limit the customer never gave, one held-out task included, and most answers passed it on. The deadline decided every one of those tasks, so no grade changed, and the verifier doesn't count it. Before the wording change, 0 of 6 runs did it.

## The failure gallery

This is the segment a demo reel can't fake. Every run goes through a verifier that checks it against the fixture facts, the tool trace, and the application's database, and proposes a class for each failure. Every failed run in the final run was read in full, and each failure shown carries its run ID.

| Failure class | What the model did | How the verifier catches it |
| --- | --- | --- |
| Hallucinated value | States a price, date, stock count, SKU, or quote reference that no tool returned | Every amount, date, SKU, and quote reference in the answer is matched against the run's tool results and the fixture truth |
| Wrong verdict on a constraint | Says an option arrives by November 13 or fits under $15 when it doesn't, or rules out one that does | Each option's stated feasibility is compared with the fixture's expected outcome |
| Model-side arithmetic | Adds up a price difference and shipping itself and gets it wrong | An amount that matches no quote total but can be rebuilt from other values in the trace |
| Wrong item | Quotes the wrong order line or the wrong replacement variant | The line and variant in the answer and in each quote request are compared with the fixture |
| Skipped clarification | Picks one of several matching items without asking which one the customer means | In the ambiguous-item tasks the answer has to ask, or quote every matching item; answering for one, or submitting anything, fails the run |
| Unapproved action | Calls a submit tool when the request said to show options first | The database: a preview-only task allows no committed return or exchange |
| Off-track work | Calls tools no valid path needs, repeats identical calls, or runs out of turns | The trace is compared with the valid task paths, and duplicate calls and exhausted limits are counted |
| False success | Reports an option or a completed action after a tool returned an error or a blocking reason | Error and blocking fields in the trace are compared with the final answer |

What the final run recorded, in runs out of 120 per server on the dev and held-out tasks:

| Failure class | Endpoint | Workflow | The transcript on the slide |
| --- | --- | --- | --- |
| Hallucinated value | 8 | 2 | `final-qwen-D1-endpoint-r3` puts "Monday, November 10" beside a Tuesday. `final-qwen-E3-workflow-r1` gets every verdict right and copies quote EQ-5003 as EQ-5503. |
| Wrong verdict on a constraint | 8 | 0 | `final-qwen-E3-endpoint-r3` says a $25.00 option "meets your under-$25 limit exactly". |
| Model-side arithmetic | 0 | 0 | Recorded twice in the endpoint arm; neither amount contradicted a quote, so neither failed a task. |
| Wrong item | 1 | 0 | `final-qwen-C1-endpoint-r4` offers a large red harness beside the blue one. The customer never named a color, so it's the mildest failure here. |
| Skipped clarification | 10 | 2 | `final-qwen-H2-endpoint-r2` and `final-qwen-H2-workflow-r2` both answer for the rope toy when the order has two toys. |
| Unapproved action | 1 | 0 | `final-qwen-E2-endpoint-r3` hears "Just tell me what it would take" and submits exchange EX-7001 with a $4.99 shipping charge. |
| Off-track work | 0 | 0 | Recorded 12 times in the endpoint arm and 16 in the workflow arm, none failing a task: plain-text answers, cards the schema check sent back, three repeated calls, and one unneeded submit. |
| False success | 1 | 0 | `final-qwen-F3-endpoint-r3` says no large collar is in stock anywhere and sends a card offering one. |

Reading every failure also found two verifier mistakes, and fixing them helped the endpoint server: four G3 answers said the right thing under the wrong label, and four H3 answers quoted both matching harnesses. Both fixes and their tests are described in [`docs/decisions.md`](../docs/decisions.md).

## Against the frontend's screens

The grades check the answer. This checks the work. The support screens in `frontend/` are the yardstick: they search, open the order and its items, check eligibility before they offer a replacement, check the replacement's stock, and quote every delivery method at every warehouse that has it. Their API client, `frontend/src/api.js`, has the same twelve operations as the endpoint server's twelve tools, and two Playwright tests walk the screens for order 1042 and order 1025 and count their twelve calls. Both servers' runs are read from the same records, the app's call log and the quotes the store holds afterward, so it doesn't matter whether the model made a call or the workflow server's Java did. It's in `results/final/qwen/export/frontend-steps.csv`, and none of it changes a grade.

| What the screens do | Endpoint | Workflow |
| --- | --- | --- |
| Quote each method at each warehouse with stock | 103 of 180 options | 180 of 180 |
| Check the replacement's stock before quoting | Skipped in 14 of 60 runs | Never skipped |
| Ask only about warehouses that exist | 3 calls to warehouses that don't | 0 |
| Quote only what the customer asked about | 12 extra quotes in 3 runs | 0 |
| Calls the store refused | 29, in 19 runs | 0 |

- **The model used the schema's example as a warehouse.** All 14 runs that skipped the stock check quoted from Columbus and nowhere else. `create_exchange_quote`'s `warehouseId` carries `example: "WH-CMH"`, which springdoc took from an annotation written for the API's Swagger page. Three other calls asked for `WH-ATL`, `WH-ORD`, and `WH-AUS`, which don't exist.
- **Five passes with the wrong price.** In E1 every endpoint answer told the customer the XL bed costs $47.99 by ground and arrives November 16. `final-qwen-E1-endpoint-r3` called it "the cheapest option". Reno has it for $45.00 by November 10, which is what the screens and all five workflow answers show. Nothing fits under $30 either way, so all ten runs pass. The endpoint runs took a median of 29,482 tokens, the workflow runs 13,008.
- **The cheapest run on order 1042 skipped the work.** `final-qwen-C1-endpoint-r2`, at 21,788 tokens, never checked stock or delivery options and quoted 2 of the 4 options. It passed because Columbus is where the option that fits ships from.
- **The store caught what the model skipped.** 14 of 90 return and exchange runs never checked eligibility, and none of them could quote an ineligible item, because the quote endpoints check it again. 26 of the 29 refused calls were quotes at warehouses without the stock.

The viewer has both runs, as "Order 1042, run 2" and "E1, run 3". The method and the per-run rows are in [`docs/decisions.md`](../docs/decisions.md) under "Against the frontend's screens".

## Session arc

My rule for every cut: a live run is one sample, so no number on a slide comes from it. Those come from the benchmark records. The run viewer replays any recorded run from local files, so a live call that fails costs the demo and nothing else.

**Conference session, 60 minutes** (the 50-minute version shortens the endpoint demo and the gallery):

| Minutes | Segment | What happens |
| --- | --- | --- |
| 0 to 5 | The request | The question, then the React app answering it with clicks. The browser's network panel lists the frontend's API calls in order, and that sequence is the workflow. Then the four screens beside their twelve calls, which are the endpoint server's twelve tools, and what the React code does between the calls that the generator couldn't copy. |
| 5 to 12 | Two tool catalogs | Twelve endpoint tools beside six workflow tools, and how many tokens each catalog puts in front of the model before the first turn. |
| 12 to 24 | Demo: the endpoint server | The request, live. The turn count, the token chart per turn, and the backend calls the model chose to make. |
| 24 to 32 | Demo: the workflow server | The same request. One preview call, the same backend calls made inside the server, and one focused result. |
| 32 to 40 | The numbers | Task success, complete-task tokens, calls and turns, and median and p95 latency, with development and held-out tasks reported separately. |
| 40 to 48 | The failure gallery | One transcript for each failure class the benchmark recorded. |
| 48 to 52 | What the grades don't show | The two verifier fixes, then every run measured against the screens: the skipped stock checks that quoted only the schema's example warehouse, the warehouses that don't exist, and E1's five passes at a higher price than the store had, beside the screenshot of the same request in the React app. |
| 52 to 55 | The workflow server's bill | The status lookup where it cost more, the two H2 runs that talked past its matching, the $0.00 limit one description fix taught the model to invent, and the code it took to build: 1,069 lines of Java against the generator's 347, not counting blank lines, comments, or imports. |
| 55 to 60 | Design rules | When to pass an endpoint through, when to design a tool around the task, and how to tell which one you're looking at. |

**Short session, 30 minutes:** the request and the screens' twelve calls (4 minutes), the two catalogs (3), a recorded side-by-side replay in place of the live demos (9), the numbers with two transcripts from the gallery and the steps the model skipped (9), and the bill and the design rules (5).

The slides are in [`slides/index.html`](slides/index.html), with both cuts, presenter notes, and a recorded fallback for each live demo.

## Who it is for

Java and Spring developers and architects putting an agent in front of an API they already run, and anyone about to generate an MCP server from an OpenAPI document. Level: intermediate. You don't need MCP experience; reading Spring Boot code comfortably helps.

## Technical requirements

- Projector and my laptop.
- Network access for the model API during the live demos, unless the chosen model runs locally. Recorded runs replay offline either way.
- Stack on stage: Java 21, Maven, Spring Boot, Spring AI and its MCP support, and a React frontend. Exact versions are pinned in each batch's manifest.

## What is on stage, in this repo

| Shown live | Where it lives |
| --- | --- |
| The order-support application and its REST API | `app/` |
| The React frontend, and its Playwright walk-throughs of order 1042 and order 1025 | `frontend/`, `frontend/e2e/` |
| The endpoint MCP server, twelve tools generated from the OpenAPI document | `mcp-endpoint/` |
| The workflow MCP server, six tools | `mcp-workflow/` |
| Products, orders, policies, stock, and expected outcomes | `fixtures/` |
| The benchmark runner and the verifier that classifies failures | `benchmark/` |
| The run viewer that replays two runs side by side | `viewer/` |
| Raw run records, transcripts, and the results summary | `results/` |
| What each run asked the store for, against what the screens ask for | `results/final/qwen/export/frontend-steps.csv` |
| The experiment design, and the choices the build made | [`docs/project-plan.md`](../docs/project-plan.md), [`docs/decisions.md`](../docs/decisions.md) |

## The post from the same runs

A post on davidparry.com comes out of the same records, and it carries the evidence: the per-turn token chart for the order 1042 request on both servers, the task success and token tables, and the failure gallery with run IDs and transcript excerpts. Both quote the same numbers. The draft is `content/blog/one-tool-per-endpoint.md` in the site's repository, and it stays a draft until the repository it links to is public.

## Before this talk can be given

- [x] Milestones 1 to 4 of the project plan complete for the local model, with the held-out run frozen.
- [ ] The Claude and GPT final runs, 480 of them, and every number here checked again with all three models.
- [x] Opening claim checked against the results. It holds for qwen; the status lookup and the scenarios are where it's weaker, and they're in the session.
- [x] Failure gallery: one fully read transcript per recorded class, each with its run ID.
- [ ] Run viewer tested on the presentation laptop with the network off.
- [x] Presenter notes, with a fallback for each live demo.
- [x] Slides in `talks/slides/index.html`, with the 60- and 30-minute cuts.
- [ ] The post published, quoting the same numbers.
