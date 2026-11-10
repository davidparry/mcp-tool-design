# MCP Tool Design Experiment: Pet Supply Order Support

**Status:** The original plan, not edited since the build began. Where the build departed from it, and the results so far, are in [decisions.md](decisions.md).  
**Created:** November 6, 2026.  
**Deliverable:** A reproducible Spring Boot application and benchmark supporting a technical post about designing MCP tools for LLMs.

## 1. Objective and hypothesis

Build one working application with a JavaScript web frontend, then expose its capabilities through two MCP interfaces:

1. **Endpoint interface:** One MCP tool per existing REST operation, passing through its arguments and response.
2. **Workflow interface:** Tools designed around the business tasks an LLM needs to complete, with server-side coordination and focused results.

Test whether the workflow interface reduces complete-task token consumption, unnecessary tool calls, and selection errors while maintaining or improving task success.

Both interfaces use MCP. The experiment measures the effect of tool design, including schemas, task boundaries, orchestration, and response shape. Lower token use is a hypothesis, not a promised result.

## 2. Application choice

Create a small pet-supply retailer's order-support application. Customers buy harnesses, toys, and other supplies; support staff look up orders and handle returns or exchanges.

The classic Swagger Petstore provides a familiar API example, but its basic pet, inventory, order, and user operations offer limited multi-step business workflows. An order-support application creates a natural reason to combine purchased items, eligibility, replacement variants, warehouse stock, shipping estimates, and pricing.

Keep the project focused on order support. A complete storefront, checkout system, real payments, and carrier integrations are outside the initial scope.

### Main demonstration

> Can I exchange the medium dog harness in order 1042 for a large, have it arrive by November 13, and keep the additional cost under $15? Show my options before submitting anything.

Use a fixed simulated business date of November 6, 2026, and a delivery deadline of November 13, 2026. Shipping estimates and stock come from local fixtures.

### Supporting tasks

- Look up an order and explain its delivery status.
- Check whether an item qualifies for a return.
- Preview an exchange against delivery and price constraints.
- Explain an unavailable or ineligible exchange.
- Request clarification when an item reference is ambiguous.
- Submit an explicitly selected return or exchange in a separate step.

## 3. Architecture and implementation boundaries

| Component | Responsibility |
| --- | --- |
| Spring Boot application | REST endpoints, shared domain services, persistence, validation, and business rules. |
| React JavaScript frontend | Order lookup, order details, return and exchange previews, and submission. |
| Endpoint MCP server | Expose each application REST operation as a separate tool. |
| Workflow MCP server | Resolve business inputs, coordinate existing REST operations, and return focused results. |
| Java benchmark runner | Run the same tasks against each MCP server, capture model usage and traces, and verify outcomes. |
| Local fixtures | Products, orders, policies, stock, delivery estimates, and expected outcomes. |

Use a small Maven project with clear module boundaries. Start with Java 21 as the project baseline. Select and pin compatible Spring Boot, Spring AI, and MCP Java support versions during setup; record the exact versions in the experiment manifest.

Both MCP servers must call the same backend HTTP APIs with the same permissions. They must not bypass application services or access the database directly. Give each run a correlation ID so internal API activity remains visible.

The workflow server contains deterministic Java orchestration. It does not invoke another model. Policy decisions, monetary calculations, quote validation, and submission rules remain in shared backend services.

## 4. Minimum application scope

### Data and rules

| Area | Minimum data or behavior |
| --- | --- |
| Customers and orders | Order reference, customer, delivery address snapshot, order status, and purchase date. |
| Order lines | Purchased variant, quantity, paid amount, and item description. |
| Products and variants | Product name, size, SKU, current price, and return category. |
| Warehouse stock | Available quantity for each variant at three simulated warehouses. |
| Delivery options | Deterministic estimates and shipping charges for the relevant stock locations. |
| Eligibility | Shared service enforcing purchase age, item category, quantity, and order state. |
| Quotes | Validated replacement or return, item-price difference, shipping, fees, total, and expiry. |
| Committed actions | Return or exchange record, status, and relevant stock changes. |

Seed approximately 12 products and 40 orders, including named fixtures for every benchmark case. Use enough realistic data to exercise selection and filtering. Do not inflate payloads or tool counts to manufacture a difference.

Use explicit currency and decimal monetary values. Freeze the clock during evaluation and make quote identifiers and other generated values available to the verifier without requiring exact identifier matches.

### Web screens

1. Order search and order details.
2. Purchased item selection and return or exchange eligibility.
3. Replacement selection, delivery options, and quote preview.
4. Selected quote submission and resulting action status.

The normal frontend must exercise the application APIs before MCP evaluation begins. This establishes a working application with legitimate endpoint boundaries.

## 5. Endpoint MCP interface

Expose the actual REST operations used by the application. The following is the initial contract proposal; finalize it while implementing the frontend.

| REST operation | Pass-through MCP tool |
| --- | --- |
| `GET /api/orders?query=...` | `search_orders` |
| `GET /api/orders/{reference}` | `get_order` |
| `GET /api/orders/{reference}/items` | `get_order_items` |
| `GET /api/products/{productId}/variants` | `get_product_variants` |
| `GET /api/orders/{reference}/items/{itemId}/exchange-eligibility` | `check_exchange_eligibility` |
| `GET /api/orders/{reference}/items/{itemId}/return-eligibility` | `check_return_eligibility` |
| `GET /api/inventory?variantId=...` | `get_inventory` |
| `GET /api/delivery-options?...` | `get_delivery_options` |
| `POST /api/exchange-quotes` | `create_exchange_quote` |
| `POST /api/return-quotes` | `create_return_quote` |
| `POST /api/exchanges` | `submit_exchange_quote` |
| `POST /api/returns` | `submit_return_quote` |

Provide clear descriptions, typed schemas, useful errors, and normal filtering or pagination where the REST application supports them. Pass through ordinary application response DTOs. The baseline should be usable and reasonably designed.

Eligibility and quote endpoints already enforce business rules. The model never needs to recreate return policies or perform financial calculations to use this interface correctly.

## 6. Workflow MCP interface

| Tool | Purpose |
| --- | --- |
| `find_orders` | Find relevant orders from a reference or customer search. |
| `get_order_context` | Return order status, purchased items, and relevant delivery information together. |
| `preview_exchange` | Find and quote eligible replacements against size, quantity, delivery, and cost constraints. |
| `preview_return` | Check return eligibility and produce a refund and fee preview. |
| `submit_exchange` | Submit the selected exchange quote. |
| `submit_return` | Submit the selected return quote. |

### Example exchange input

```json
{
  "order_reference": "1042",
  "item_description": "medium dog harness",
  "replacement_size": "large",
  "quantity": 1,
  "deliver_by": "2026-11-13",
  "max_additional_cost": {
    "amount": "15.00",
    "currency": "USD"
  }
}
```

The tool resolves the order line and replacement variant, checks eligibility, obtains stock and delivery options, and requests validated quotes through the existing REST APIs.

Return structured facts covering the matched item, eligibility and reasons, feasible options, delivery estimates, price breakdowns, quote references, and expiry. Label all sample responses as illustrative until the benchmark runs.

### Tool behavior

- Use distinct purposes, concise descriptions, typed inputs, and structured output schemas.
- Resolve item descriptions using deterministic matching. If several order lines match, return candidates and request clarification.
- Return explicit outcomes such as `options_available`, `ineligible`, `no_matching_option`, or `needs_clarification`.
- Preserve information required to answer the task, including failed constraints and blocking reasons.
- Keep result limits explicit and expose whether additional matches exist.
- Preview operations may create draft quotes. They cannot commit a return or exchange, reserve stock, or charge a customer. Describe actual side effects accurately in tool metadata.
- Submission requires an explicit selected quote. The backend revalidates eligibility, expiry, and stock and supports idempotent retries.

The workflow server may make several internal API calls even when the model makes one tool call. Report those internal calls separately.

## 7. Benchmark design

### Experimental arms

| Arm | Interface | Purpose |
| --- | --- | --- |
| A | Endpoint tools with normal application responses. | Primary baseline. |
| B | Workflow tools with focused structured results. | Primary comparison. |
| C, optional | The same endpoint tools as A with compact responses that preserve required facts. | Help distinguish response-size effects from the broader workflow-interface change. |

Arm C is a follow-up after A and B work. Comparing C with B still changes several interface properties together; it does not isolate tool count alone.

### Task set

Create 24 cases across these categories, including three variations per category:

| Category | Expected behavior |
| --- | --- |
| Simple order lookup | Find the correct order and state its status. |
| Return preview | Correctly state eligibility, refund, and fees. |
| Exchange with several options | Identify options meeting the requested constraints. |
| Delivery deadline cannot be met | Explain the deadline failure without claiming an on-time option exists. |
| Price cap cannot be met | Explain the cost constraint and relevant amounts. |
| Replacement unavailable | Correctly report the stock limitation. |
| Ineligible purchase | Explain the applicable policy outcome. |
| Ambiguous item description | Request clarification before selecting or submitting an action. |

Use 16 cases during tool development and reserve eight cases for final evaluation. Include paraphrases and unseen fixture combinations in the held-out cases. Freeze the final tool contracts before evaluating them.

Keep submission verification separate from the main preview comparison. Add stateful scenarios for approved submission, expired quote, stock changing after preview, duplicate submission, and approval being withheld.

### Controls

- Use the same model identifier, settings, task prompt, system instructions, and reasoning settings for each paired task.
- Start each run with a fresh conversation and identical database fixtures.
- Use the same fixed clock, authentication scope, and simulated external services.
- Keep tool discovery and schema-loading behavior consistent. Record which tool definitions actually enter the model context.
- Use the same conversation-history policy and generous run limits. Set limits after a pilot and freeze them before final evaluation.
- Alternate or randomize arm order to reduce warm-up and ordering effects.
- Run five repetitions per task per primary arm, retaining failures and exhausted runs.
- Measure model and application caches explicitly. Separate cold and warm results if both are tested.
- Supply equivalent capabilities and required facts through both interfaces.

Twenty-four cases with five repetitions across A and B produce 240 task runs. Present development and held-out results separately.

## 8. Metrics and correctness

| Metric | Definition |
| --- | --- |
| Task success | Required facts and constraints are correct, and application state matches the permitted action. |
| Complete-task model input tokens | Sum model-reported input usage across every model turn, including history processed again. |
| Complete-task model output tokens | Sum model-reported output usage, preserving provider reasoning-token accounting. |
| Cache usage | Record raw cache-read and cache-write fields and document provider semantics to avoid double counting. |
| Tool-definition tokens | Diagnostic size of descriptions and schemas actually supplied to the model. |
| Tool-result tokens | Diagnostic size of tool outputs exposed to the model, plus the number of repeated exposures when available. |
| Calls and turns | MCP calls, model turns, backend HTTP calls, invalid arguments, and retries. |
| Selection behavior | Unnecessary calls, irrelevant tool choices, and recovery from errors, judged against valid task paths. |
| Latency | End-to-end task duration, model duration, and tool duration. |

Use provider usage as the primary token record. Where a suitable tokenizer is available, use it for component-level diagnostics and label estimates. Component sizes are explanations of usage, not additional totals to add to model-reported input.

Verify outcomes against fixture facts and shared domain services. Check option feasibility, eligibility, monetary values, delivery dates, and committed state. Accept multiple valid tool sequences and equivalent answer wording.

Report success rates, token distributions, median and p95 latency, and failure categories. Show token consumption for all attempts as well as successful tasks. Describe uncertainty and sample sizes; do not infer a universal percentage saving from one workflow.

## 9. Implementation milestones

### Milestone 1: Working application

- [ ] Define fixtures, rules, and expected outcomes.
- [ ] Implement shared services and REST contracts.
- [ ] Build the frontend order-support screens.
- [ ] Verify eligibility, totals, quote expiry, stock revalidation, and idempotent submission with focused tests.

**Completion:** A user can complete the representative workflows through the web application.

### Milestone 2: Endpoint MCP server

- [ ] Wrap the finalized REST operations one to one.
- [ ] Document tool schemas, errors, permissions, and side effects.
- [ ] Verify protocol behavior and representative tool calls.
- [ ] Confirm traces show the underlying REST operations.

**Completion:** The benchmark runner can complete representative tasks using the endpoint tools.

### Milestone 3: Workflow MCP server

- [ ] Implement the six task-oriented tools.
- [ ] Reuse the same HTTP APIs and shared rule decisions.
- [ ] Implement compact outputs and ambiguity handling.
- [ ] Verify that previews cannot commit business actions.
- [ ] Confirm traces expose the internal API work.

**Completion:** Both MCP interfaces can satisfy the same benchmark tasks and submission rules.

### Milestone 4: Reproducible evaluation

- [ ] Build the runner, usage capture, transcript capture, and outcome verifier.
- [ ] Pilot with development cases and fix implementation defects.
- [ ] Freeze contracts, configuration, fixtures, and run limits.
- [ ] Run the paired repetitions and separate held-out evaluation.
- [ ] Export raw records and aggregate results.

**Completion:** Results can be reproduced from the recorded commit, configuration, fixtures, and task set.

### Milestone 5: Technical post and demonstration

- [ ] Show the application and the two tool catalogs.
- [ ] Walk through the same request using both interfaces.
- [ ] Display model turns, tool calls, backend calls, and token usage together.
- [ ] Present task success and representative failures alongside savings, if observed.
- [ ] Explain implementation effort and workflow coverage tradeoffs.
- [ ] Include a simple lookup where the endpoint interface may perform equally well.
- [ ] Publish the repository instructions, benchmark method, and measured results.

**Completion:** Readers can distinguish measured outcomes, the proposed explanation, and the experiment's limits.

## 10. Planned deliverables

- Spring Boot application and React JavaScript frontend.
- Endpoint and workflow MCP server implementations.
- Local setup instructions and deterministic seed/reset procedure.
- Versioned task catalog, fixture expectations, and benchmark configuration.
- Raw per-run records and readable transcripts.
- Results summary with correctness, tokens, calls, latency, and failures.
- Technical post explaining the experiment and its findings.

Suggested run records include the run ID, arm, task ID, repetition, commit, model, dependency versions, fixture version, clock, settings, exposed tool schemas, cache fields, model usage, tool/API timings, final answer, verification outcome, and errors. Keep credentials out of exported records.

## 11. Interpretation and post angle

The proposed argument is that tools organized around business intent can reduce how much coordination the model performs and how much intermediate information it processes.

The benchmark must determine whether that happens here and whether task success is preserved. If an existing REST endpoint already completes a workflow efficiently, its pass-through MCP tool may perform just as well. If the workflow interface fails on tasks outside its intended coverage, report that boundary.

Treat schema design, orchestration, and result shaping as engineering choices with implementation and maintenance costs. The project succeeds when it produces an honest, reproducible comparison, including a neutral or mixed result.

**Working post title:** Designing MCP Tools Around the Task: A Spring Boot Experiment

## 12. Background references

These sources motivate the experiment. They are not measurements from this project.

- [Swagger Petstore sample](https://petstore3.swagger.io/): Reference for the classic sample API and its operations.
- [Anthropic: Writing effective tools for agents](https://www.anthropic.com/research/writing-tools-for-agents): Workflow-oriented tools, relevant outputs, token efficiency, and evaluation practices.
- [AWS: MCP tool design strategy](https://docs.aws.amazon.com/prescriptive-guidance/latest/mcp-strategies/mcp-tool-strategy.html): Tool scope and granularity considerations.
- [MCP specification: Tools](https://modelcontextprotocol.io/specification/2026-07-28/server/tools): Tool schemas, structured output, metadata, and error behavior. Record the protocol revision actually implemented.
