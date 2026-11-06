# Same API, Two MCP Servers: The Price of One Tool per Endpoint

A conference session built on this repository: one Spring Boot application, two MCP servers in front of it, and a benchmark that measures what each server costs a model in tokens and in mistakes.

**Speaker:** David Parry ·
[davidparry.com](https://davidparry.com) ·
[github.com/davidparry](https://github.com/davidparry)

**Status:** started November 6, 2026. Nothing here is measured yet. The application and the benchmark come first (milestones 1 to 4 in [`docs/project-plan.md`](../docs/project-plan.md)), and I'll check the opening claim and every number against the final run before this goes to a program committee.

Other titles under consideration:

- Designing MCP Tools Around the Task (shared with the post)
- Twelve Tools for One Question

---

## Short abstract

For CFP forms with a length cap.

> Wrapping each REST endpoint in an MCP tool hands the model the orchestration your frontend already does in code. This session puts two MCP servers in front of one Spring Boot order-support app, one with a tool per endpoint and one with six tools shaped around the support tasks, and gives the same model the same requests against both. The runs play side by side: the turns, the tool calls, the tokens on each turn, and the failures the benchmark recorded.

## Abstract

**Wrapping each REST endpoint in an MCP tool hands the model the orchestration job your frontend already does in code, and you pay for that job on every model turn.** That's the claim this session tests.

The test bed is a small Spring Boot and React order-support app for a pet-supply store that sells dog harnesses and toys. Two MCP servers sit in front of the same HTTP APIs with the same permissions. The endpoint server is generated from the app's OpenAPI document, the way most teams add MCP to an API they already run: twelve tools, one per REST operation, passing the application's own response DTOs straight through. It's typed and documented, and nothing in it is broken on purpose. The workflow server has six tools shaped around the support job. One call previews an exchange; a separate call submits the quote somebody approved. Its orchestration is plain Java, and it never calls a model.

I give both servers the same system prompt, the same three models (Claude, GPT, and a qwen running locally on Ollama), and this request:

> Can I exchange the medium dog harness in order 1042 for a large, have it arrive by November 13, and keep the additional cost under $15? Show my options before submitting anything.

On stage the two runs play side by side: each model turn, each tool call, the backend calls underneath it, and the input tokens per turn (including every earlier tool result, which the model reads again on each new turn). Tokens are half the story. The other half is what a model does with twelve tools and raw DTOs. It can misread a delivery date against the deadline, add up a total the quote already computed and get it wrong, pick the wrong harness when the order has two, or submit an exchange nobody approved. Every failure the benchmark records gets a class, and the session shows those transcripts next to the averages.

None of this makes the workflow server free. It's more code to write and keep correct, and it covers only the tasks it was designed for. On a plain order lookup it may come out even with the endpoint server. Those results are in the session too, from 720 runs: 24 tasks, five repetitions each, on both servers with all three models, and eight of the tasks held out until the tool contracts were frozen.

It's the follow-up to my [Skills versus MCP benchmark](https://davidparry.com/blog/2026/07/20/skills-vs-mcp-what-the-token-bill-actually-measures/), which moved business rules out of the model and into code. This one asks what shape the tools around that code should take.

## What attendees leave with

- A way to decide, endpoint by endpoint, whether a pass-through tool is enough or a tool shaped around the task earns its maintenance cost.
- Design rules for task-shaped tools, each one visible in the workflow server's code: business inputs (an order reference and an item description) where the endpoints want internal IDs, deterministic matching that returns candidates when a description fits more than one item, explicit outcomes (`options_available`, `ineligible`, `no_matching_option`, `needs_clarification`), previews that can't commit anything, and a submit step that takes a selected quote and revalidates it.
- How to count what a task costs: add up the model-reported usage for every turn with the history included, and keep cache reads and writes in their own columns so nothing gets counted twice.
- A failure taxonomy to run against your own agent transcripts.
- The Spring Boot and Spring AI code for both servers and the benchmark runner, so you can repeat the comparison against your own API.

## The failure gallery

This is the segment a demo reel can't fake. Every run goes through a verifier that checks it against the fixture facts, the tool trace, and the application's database, and proposes a class for each failure. I'll read the whole transcript before a failure goes on a slide, and each one shown carries its run ID. Until the final run, this table lists what the benchmark looks for; the counts don't exist yet.

| Failure class | What the model did | How the verifier catches it |
| --- | --- | --- |
| Hallucinated value | States a price, date, stock count, SKU, or quote reference that no tool returned | Every amount, date, SKU, and quote reference in the answer is matched against the run's tool results and the fixture truth |
| Wrong verdict on a constraint | Says an option arrives by November 13 or fits under $15 when it doesn't, or rules out one that does | Each option's stated feasibility is compared with the fixture's expected outcome |
| Model-side arithmetic | Adds up a price difference and shipping itself and gets it wrong | An amount that matches no quote total but can be rebuilt from other values in the trace |
| Wrong item | Quotes the wrong order line or the wrong replacement variant | The line and variant in the answer and in each quote request are compared with the fixture |
| Skipped clarification | Picks one of several matching items without asking which one the customer means | In the ambiguous-item tasks the answer has to ask; presenting one item's options as the answer, or submitting anything, fails the run |
| Unapproved action | Calls a submit tool when the request said to show options first | The database: a preview-only task allows no committed return or exchange |
| Off-track work | Calls tools no valid path needs, repeats identical calls, or runs out of turns | The trace is compared with the valid task paths, and duplicate calls and exhausted limits are counted |
| False success | Reports an option or a completed action after a tool returned an error or a blocking reason | Error and blocking fields in the trace are compared with the final answer |

## Session arc

My rule for every cut: a live run is one sample, so no number on a slide comes from it. Those come from the benchmark records. The run viewer replays any recorded run from local files, so a live call that fails costs the demo and nothing else.

**Conference session, 60 minutes** (the 50-minute version shortens the endpoint demo and the gallery):

| Minutes | Segment | What happens |
| --- | --- | --- |
| 0 to 5 | The request | The question, then the React app answering it with clicks. The browser's network panel lists the frontend's API calls in order, and that sequence is the workflow. |
| 5 to 12 | Two tool catalogs | Twelve endpoint tools beside six workflow tools, and how many tokens each catalog puts in front of the model before the first turn. |
| 12 to 24 | Demo: the endpoint server | The request, live. The turn count, the token chart per turn, and the backend calls the model chose to make. |
| 24 to 32 | Demo: the workflow server | The same request. One preview call, the same backend calls made inside the server, and one focused result. |
| 32 to 40 | The numbers | Task success, complete-task tokens, calls and turns, and median and p95 latency, with development and held-out tasks reported separately. |
| 40 to 48 | The failure gallery | One transcript for each failure class the benchmark recorded. |
| 48 to 55 | The workflow server's bill | The simple lookup where it may tie, a task outside its coverage, and the code it took to build. |
| 55 to 60 | Design rules | When to pass an endpoint through, when to design a tool around the task, and how to tell which one you're looking at. |

**Short session, 30 minutes:** the request (3 minutes), the two catalogs (4), a recorded side-by-side replay in place of the live demos (10), the numbers with two transcripts from the gallery (8), and the bill and the design rules (5).

## Who it is for

Java and Spring developers and architects putting an agent in front of an API they already run, and anyone about to generate an MCP server from an OpenAPI document. Level: intermediate. You don't need MCP experience; reading Spring Boot code comfortably helps.

## Technical requirements

- Projector and my laptop.
- Network access for the model API during the live demos, unless the chosen model runs locally. Recorded runs replay offline either way.
- Stack on stage: Java 21, Maven, Spring Boot, Spring AI and its MCP support, and a React frontend. Exact versions get pinned in the experiment manifest.

## What is on stage, in this repo

| Shown live | Where it lives |
| --- | --- |
| The order-support application and its REST API | `app/` |
| The React frontend | `frontend/` |
| The endpoint MCP server, twelve tools generated from the OpenAPI document | `mcp-endpoint/` |
| The workflow MCP server, six tools | `mcp-workflow/` |
| Products, orders, policies, stock, and expected outcomes | `fixtures/` |
| The benchmark runner and the verifier that classifies failures | `benchmark/` |
| The run viewer that replays two runs side by side | `viewer/` |
| Raw run records, transcripts, and the results summary | `results/` |
| The experiment design, and the choices the build made | [`docs/project-plan.md`](../docs/project-plan.md), [`docs/decisions.md`](../docs/decisions.md) |

## The post from the same runs

A post on davidparry.com comes out of the same records, and it carries the evidence: the per-turn token chart for the order 1042 request on both servers, the task success and token tables, and the failure gallery with run IDs and transcript excerpts. Both quote the same numbers, and neither goes out before the final run is frozen.

## Before this talk can be given

- [ ] Milestones 1 to 4 of the project plan complete, with the held-out run frozen.
- [ ] Opening claim checked against the results and rewritten if they don't support it. A neutral or mixed result gets stated as one.
- [ ] Failure gallery: one fully read transcript per recorded class, each with its run ID.
- [ ] Run viewer tested on the presentation laptop with the network off.
- [ ] Presenter notes, with a fallback for each live demo.
- [ ] Slides in `talks/slides/index.html`, with the 60- and 30-minute cuts.
- [ ] The post published, quoting the same numbers.
