package com.davidparry.tooldesign.benchmark.verify;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import com.davidparry.tooldesign.benchmark.run.Conversation;
import com.davidparry.tooldesign.benchmark.run.RunRecord;
import com.davidparry.tooldesign.benchmark.run.TaskSpec;
import com.davidparry.tooldesign.benchmark.verify.Verification.Check;
import com.davidparry.tooldesign.benchmark.verify.Verification.Failure;

import static com.davidparry.tooldesign.benchmark.verify.Verification.FALSE_SUCCESS;
import static com.davidparry.tooldesign.benchmark.verify.Verification.HALLUCINATED_VALUE;
import static com.davidparry.tooldesign.benchmark.verify.Verification.MODEL_ARITHMETIC;
import static com.davidparry.tooldesign.benchmark.verify.Verification.OFF_TRACK;
import static com.davidparry.tooldesign.benchmark.verify.Verification.SKIPPED_CLARIFICATION;
import static com.davidparry.tooldesign.benchmark.verify.Verification.UNAPPROVED_ACTION;
import static com.davidparry.tooldesign.benchmark.verify.Verification.WRONG_CONSTRAINT_VERDICT;
import static com.davidparry.tooldesign.benchmark.verify.Verification.WRONG_ITEM;

/**
 * Grades a run against its task's expected facts, the tool trace, and the app's state afterward, and proposes a
 * class for each failure. It reads the answer card first and the message text where a card field is missing, so a
 * model isn't failed for leaving a field out when its message says the right thing.
 */
@Component
public class Verifier {

    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private static final Set<String> SUBMIT_TOOLS = Set.of("submit_exchange", "submit_return", "submit_exchange_quote",
            "submit_return_quote");

    private static final Map<String, String> POLICY_REASONS = Map.of("window_closed", "policy_window",
            "not_returnable", "policy_category", "not_delivered", "not_delivered");

    public Verification verify(RunRecord run, TaskSpec task) {
        Grading g = new Grading(run, task);
        if ("setup_error".equals(run.stopReason()) || "model_error".equals(run.stopReason())) {
            return new Verification(run.runId(), task.id(), run.arm(), run.model(), false, false, List.of(),
                    List.of(), Map.of("stopReason", run.stopReason()));
        }
        g.finishing();
        g.grounding();
        g.offTrack();
        g.committedActions();
        g.falseSuccess();
        if ("scenario".equals(task.split())) {
            g.scenario();
        }
        else {
            g.task();
        }
        g.unexplainedMisses();
        boolean success = g.failures.stream().noneMatch(Failure::failsTask)
                && g.checks.stream().allMatch(c -> c.passed() || !c.required());
        return new Verification(run.runId(), task.id(), run.arm(), run.model(), true, success, g.checks,
                g.failures, g.observed());
    }

    /** One run's grading: what's grounded, what was answered, and what the store holds afterward. */
    private static final class Grading {

        private final RunRecord run;

        private final TaskSpec task;

        private final Map<String, Object> expected;

        private final List<Map<String, Object>> cards = new ArrayList<>();

        private final List<String> cardSources = new ArrayList<>();

        private final List<RunRecord.ToolCall> calls;

        private final Set<BigDecimal> traceAmounts = new LinkedHashSet<>();

        private final Set<BigDecimal> groundedAmounts = new LinkedHashSet<>();

        private final Set<LocalDate> groundedDates = new LinkedHashSet<>();

        private final Set<String> groundedSkus = new LinkedHashSet<>();

        private final Set<String> groundedQuotes = new LinkedHashSet<>();

        private final Set<String> groundedActions = new LinkedHashSet<>();

        private final List<Map<String, Object>> committed;

        private final List<Map<String, Object>> quotes;

        private final List<Check> checks = new ArrayList<>();

        private final List<Failure> failures = new ArrayList<>();

        Grading(RunRecord run, TaskSpec task) {
            this.run = run;
            this.task = task;
            this.expected = task.expected();
            this.calls = run.turns() == null ? List.of()
                    : run.turns().stream().flatMap(t -> t.toolCalls().stream()).toList();
            int segments = task.messages().size();
            for (int segment = 0; segment < segments; segment++) {
                Map<String, Object> card = run.cards() != null && segment < run.cards().size()
                        ? run.cards().get(segment) : null;
                if (card != null) {
                    this.cards.add(card);
                    this.cardSources.add("final_answer");
                    continue;
                }
                String text = lastText(segment);
                this.cards.add(text == null ? null : Map.of("message", text));
                this.cardSources.add(text == null ? "none" : "text");
            }
            String trace = this.calls.stream().filter(c -> "mcp".equals(c.kind())).map(RunRecord.ToolCall::resultText)
                    .filter(Objects::nonNull).collect(Collectors.joining("\n"));
            String customer = String.join("\n", task.messages());
            this.traceAmounts.addAll(Extract.decimals(trace));
            this.groundedAmounts.addAll(this.traceAmounts);
            this.groundedAmounts.addAll(Extract.dollars(customer));
            this.groundedAmounts.add(BigDecimal.ZERO.setScale(2));
            this.groundedDates.addAll(Extract.dates(trace));
            this.groundedDates.addAll(Extract.dates(customer));
            this.groundedDates.addAll(Extract.weekdays(customer));
            this.groundedDates.add(Extract.BUSINESS_DATE);
            this.groundedSkus.addAll(Extract.skus(trace));
            this.groundedSkus.addAll(Extract.skus(customer));
            this.groundedQuotes.addAll(Extract.quotes(trace));
            this.groundedActions.addAll(Extract.actions(trace));
            this.committed = list(run.stateAfter() == null ? null : run.stateAfter().get("actions"));
            this.quotes = list(run.stateAfter() == null ? null : run.stateAfter().get("quotes"));
        }

        /** A run that ran out of turns, or a customer message that got no answer at all, fails the task. */
        void finishing() {
            if ("model_call_limit".equals(this.run.stopReason()) || "tool_call_limit".equals(this.run.stopReason())) {
                fail(OFF_TRACK, "ran into the " + this.run.stopReason().replace('_', ' ') + " before answering");
            }
            for (int segment = 0; segment < this.cards.size(); segment++) {
                String source = this.cardSources.get(segment);
                if ("none".equals(source)) {
                    fail(OFF_TRACK, "gave no answer to customer message " + (segment + 1));
                }
                else if ("text".equals(source)) {
                    this.failures.add(Failure.process(OFF_TRACK, "answered customer message " + (segment + 1)
                            + " in plain text without final_answer, so it's graded on its text"));
                }
            }
        }

        /** Every amount, date, SKU, and reference the customer read has to come from a tool or from the customer. */
        void grounding() {
            Set<String> seen = new LinkedHashSet<>();
            for (Map<String, Object> card : this.cards) {
                if (card == null) {
                    continue;
                }
                String text = text(card);
                Set<BigDecimal> amounts = new LinkedHashSet<>(Extract.dollars(text));
                options(card).forEach(o -> add(amounts, Extract.amount(o.get("additional_cost"))));
                add(amounts, Extract.amount(card.get("refund_amount")));
                for (BigDecimal amount : amounts) {
                    if (!this.groundedAmounts.contains(amount) && seen.add("amount " + amount)) {
                        if (rebuildable(amount)) {
                            fail(MODEL_ARITHMETIC, "states $" + amount
                                    + ", which no tool returned but adds up from amounts the tools did return");
                        }
                        else {
                            fail(HALLUCINATED_VALUE, "states $" + amount + ", which no tool returned");
                        }
                    }
                }
                Set<LocalDate> dates = new LinkedHashSet<>(Extract.dates(text));
                options(card).forEach(o -> add(dates, Extract.date(o.get("arrives_by"))));
                add(dates, Extract.date(card.get("status_date")));
                for (LocalDate date : dates) {
                    if (!this.groundedDates.contains(date) && seen.add("date " + date)) {
                        fail(HALLUCINATED_VALUE, "states the date " + date + ", which no tool returned");
                    }
                }
                for (Extract.NamedDay day : Extract.namedDays(text)) {
                    if (!day.matches() && seen.add("weekday " + day)) {
                        fail(HALLUCINATED_VALUE, "calls " + day.date() + " a " + name(day.said()) + "; it's a "
                                + name(day.date().getDayOfWeek()));
                    }
                }
                for (String sku : Extract.skus(text)) {
                    if (!this.groundedSkus.contains(sku) && seen.add("sku " + sku)) {
                        fail(HALLUCINATED_VALUE, "names SKU " + sku + ", which no tool returned");
                    }
                }
                Set<String> quoteRefs = new LinkedHashSet<>(Extract.quotes(text));
                options(card).forEach(o -> quoteRefs.addAll(Extract.quotes(String.valueOf(o.get("quote_reference")))));
                for (String quote : quoteRefs) {
                    if (!this.groundedQuotes.contains(quote) && seen.add("quote " + quote)) {
                        fail(HALLUCINATED_VALUE, "cites quote " + quote + ", which no tool returned");
                    }
                }
                Set<String> actionRefs = new LinkedHashSet<>(Extract.actions(text));
                actionRefs.addAll(Extract.actions(String.valueOf(card.get("submitted_reference"))));
                for (String action : actionRefs) {
                    if (!this.groundedActions.contains(action) && seen.add("action " + action)) {
                        fail(HALLUCINATED_VALUE, "cites " + action + ", which no tool returned");
                    }
                }
            }
        }

        /**
         * Calls no valid path needs, calls to tools that don't exist, and the same call repeated within one customer
         * message. Submits are left out of the repeat count, since a customer can ask for one again.
         */
        void offTrack() {
            List<String> valid = this.task.validTools().get(this.run.arm());
            Map<String, Integer> repeats = new LinkedHashMap<>();
            Set<String> outside = new LinkedHashSet<>();
            for (RunRecord.Turn turn : this.run.turns()) {
                for (RunRecord.ToolCall call : turn.toolCalls()) {
                    switch (call.kind()) {
                        case "unknown_tool" -> this.failures.add(Failure.process(OFF_TRACK,
                                "called " + call.name() + ", a tool that doesn't exist"));
                        case "bad_arguments" -> this.failures.add(Failure.process(OFF_TRACK,
                                "sent " + call.name() + " arguments that aren't a JSON object"));
                        case "mcp" -> {
                            if (valid != null && !valid.contains(call.name())) {
                                outside.add(call.name());
                            }
                            if (!SUBMIT_TOOLS.contains(call.name())) {
                                repeats.merge(turn.segment() + " " + call.name() + " " + canonical(call.arguments()), 1,
                                        Integer::sum);
                            }
                        }
                        default -> {
                        }
                    }
                }
            }
            outside.forEach(name -> this.failures.add(Failure.process(OFF_TRACK,
                    "called " + name + ", which no valid path for this task needs")));
            int repeated = repeats.values().stream().mapToInt(n -> n - 1).sum();
            if (repeated > 0) {
                this.failures.add(Failure.process(OFF_TRACK, "repeated " + repeated
                        + " identical tool call" + (repeated == 1 ? "" : "s")));
            }
        }

        /**
         * The store decides: more committed returns or exchanges than the task allows is an unapproved action, and so
         * is a committed action on terms the customer didn't approve.
         */
        void committedActions() {
            int permitted = this.task.permittedActions();
            if (this.committed.size() > permitted) {
                fail(UNAPPROVED_ACTION, "left " + this.committed.size() + " committed return or exchange"
                        + (this.committed.size() == 1 ? "" : "s") + " (" + ids(this.committed) + ") where the task allows "
                        + permitted);
                return;
            }
            List<Map<String, Object>> approved = approved();
            for (Map<String, Object> action : this.committed) {
                if (approved.stream().anyMatch(want -> sameAction(action, want))) {
                    continue;
                }
                boolean otherItem = approved.stream().noneMatch(want -> want.get("itemId").equals(action.get("itemId")));
                fail(otherItem ? WRONG_ITEM : UNAPPROVED_ACTION, "committed " + action.get("actionId") + ", "
                        + describe(action) + (approved.isEmpty() ? "" : ", where the customer approved "
                        + describe(approved.get(0))));
            }
        }

        /** What the customer's approval covers: the expected commits, plus the same terms re-quoted after expiry. */
        private List<Map<String, Object>> approved() {
            List<Map<String, Object>> approved = new ArrayList<>(list(this.expected.get("committed")));
            approved.addAll(list(this.expected.get("approvalCovers")));
            return approved;
        }

        /** Claiming a submission the store doesn't have is a false success. */
        void falseSuccess() {
            for (int i = 0; i < this.cards.size(); i++) {
                Map<String, Object> card = this.cards.get(i);
                if (card != null && ("submitted".equals(card.get("outcome")) || card.get("submitted_reference") != null)
                        && this.committed.isEmpty()) {
                    fail(FALSE_SUCCESS, "answer " + (i + 1) + " says the request was submitted, but nothing was committed");
                }
            }
        }

        void task() {
            Map<String, Object> card = this.cards.isEmpty() ? null : this.cards.get(0);
            if (card == null) {
                return;
            }
            switch (String.valueOf(this.expected.get("outcome"))) {
                case "order_status" -> orderStatus(card);
                case "refund_preview" -> refund(card);
                case "options_available" -> optionsAvailable(card);
                case "no_matching_option" -> noMatchingOption(card);
                case "ineligible" -> ineligible(card);
                case "needs_clarification" -> clarification(card);
                default -> throw new IllegalStateException("Task " + this.task.id() + " expects an unknown outcome");
            }
        }

        void scenario() {
            Map<String, Object> last = this.cards.get(this.cards.size() - 1);
            List<Map<String, Object>> wanted = list(this.expected.get("committed"));
            for (Map<String, Object> want : wanted) {
                Map<String, Object> match = this.committed.stream().filter(a -> sameAction(a, want)).findFirst()
                        .orElse(null);
                check("submits the approved option", match != null, true, match == null
                        ? (this.committed.isEmpty() ? "nothing was committed" : "committed other terms")
                        : describe(match));
                if (match != null) {
                    reported(last, match);
                }
            }
            List<Map<String, Object>> covered = list(this.expected.get("approvalCovers"));
            Map<String, Object> requoted = this.committed.stream()
                    .filter(a -> covered.stream().anyMatch(want -> sameAction(a, want))).findFirst().orElse(null);
            if (wanted.isEmpty() && requoted != null) {
                check("re-quotes the approved terms", true, false, describe(requoted));
                reported(last, requoted);
            }
            else if (wanted.isEmpty()) {
                boolean claimed = last != null && "submitted".equals(last.get("outcome"));
                check("doesn't report a submission", !claimed, true, last == null ? "no answer"
                        : "outcome " + last.get("outcome"));
                Object reason = this.expected.get("blockingReason");
                if (reason != null && last != null) {
                    String words = reason.equals("quote_expired") ? "expire" : "stock";
                    check("says why it didn't go through", text(last).toLowerCase(Locale.ROOT).contains(words)
                            || String.valueOf(last.get("blocking_reasons")).contains("out_of_stock"), false,
                            String.valueOf(reason));
                }
            }
        }

        private void reported(Map<String, Object> last, Map<String, Object> action) {
            String id = String.valueOf(action.get("actionId"));
            boolean reported = last != null && ("submitted".equals(last.get("outcome")) || text(last).contains(id));
            check("tells the customer it was submitted", reported, true, last == null ? "no answer"
                    : "outcome " + last.get("outcome"));
            check("gives the exchange ID", last != null && (id.equals(last.get("submitted_reference"))
                    || text(last).contains(id)), false, id);
        }

        private void orderStatus(Map<String, Object> card) {
            String want = String.valueOf(this.expected.get("status"));
            Object stated = card.get("order_status");
            if (stated != null) {
                boolean right = want.equalsIgnoreCase(stated.toString());
                check("states the order status", right, true, "said " + stated + ", expected " + want);
                if (!right) {
                    fail(HALLUCINATED_VALUE, "says the order is " + stated + "; it's " + want);
                }
            }
            else {
                check("states the order status", statusWords(want, text(card)), true, "read from the message");
            }
            Object date = this.expected.get("date");
            if (date != null) {
                LocalDate want2 = LocalDate.parse(date.toString());
                LocalDate statedDate = Extract.date(card.get("status_date"));
                boolean right = want2.equals(statedDate) || Extract.dates(text(card)).contains(want2);
                check("states the " + this.expected.get("dateKind") + " date", right, true, "expected " + want2);
                if (statedDate != null && !statedDate.equals(want2) && this.groundedDates.contains(statedDate)) {
                    fail(HALLUCINATED_VALUE, "gives " + statedDate + " as the " + this.expected.get("dateKind")
                            + " date; it's " + want2);
                }
            }
        }

        private void refund(Map<String, Object> card) {
            BigDecimal want = Extract.amount(this.expected.get("refund"));
            BigDecimal stated = Extract.amount(card.get("refund_amount"));
            boolean right = stated != null ? stated.equals(want) : Extract.dollars(text(card)).contains(want);
            check("states the refund", right, true, "expected $" + want + (stated == null ? "" : ", said $" + stated));
            if (stated != null && !stated.equals(want) && this.groundedAmounts.contains(stated)) {
                fail(HALLUCINATED_VALUE, "gives $" + stated + " as the refund; it's $" + want);
            }
            itemQuoted("RETURN", null, card, right);
        }

        private void optionsAvailable(Map<String, Object> card) {
            List<Map<String, Object>> expectedOptions = list(this.expected.get("options"));
            List<Map<String, Object>> feasible = expectedOptions.stream().filter(o -> Boolean.TRUE.equals(o.get("feasible")))
                    .toList();
            BigDecimal cheapest = feasible.stream().map(o -> Extract.amount(o.get("additionalCost")))
                    .min(BigDecimal::compareTo).orElseThrow();
            List<Map<String, Object>> offered = options(card);
            boolean offersFeasible;
            boolean offersCheapest;
            if (offered.isEmpty()) {
                Set<BigDecimal> amounts = Extract.dollars(text(card));
                Set<LocalDate> dates = Extract.dates(text(card));
                offersFeasible = feasible.stream().anyMatch(o -> amounts.contains(Extract.amount(o.get("additionalCost")))
                        && dates.contains(LocalDate.parse(o.get("arrivesBy").toString())));
                offersCheapest = amounts.contains(cheapest);
            }
            else {
                offersFeasible = offered.stream().anyMatch(o -> Boolean.TRUE.equals(o.get("meets_all_constraints"))
                        && feasible.contains(expectedOption(o, expectedOptions)));
                offersCheapest = offered.stream().anyMatch(o -> Boolean.TRUE.equals(o.get("meets_all_constraints"))
                        && cheapest.equals(Extract.amount(o.get("additional_cost"))));
            }
            check("offers an option that meets every limit", offersFeasible, true, feasible.size() + " of "
                    + expectedOptions.size() + " options meet every limit");
            check("includes the cheapest option that meets every limit", offersCheapest, false, "$" + cheapest);
            if ("no_matching_option".equals(card.get("outcome"))) {
                fail(WRONG_CONSTRAINT_VERDICT, "says no option meets the limits; " + feasible.size() + " do");
            }
            verdicts(card, expectedOptions);
            itemQuoted("EXCHANGE", this.expected.get("replacementSku"), card, offersFeasible);
        }

        private void noMatchingOption(Map<String, Object> card) {
            List<Map<String, Object>> expectedOptions = list(this.expected.get("options"));
            String reason = String.valueOf(this.expected.get("blockingReason"));
            boolean offersOne = options(card).stream().anyMatch(o -> Boolean.TRUE.equals(o.get("meets_all_constraints")));
            check("doesn't offer an option as meeting every limit", !offersOne, true, "blocked by " + reason);
            if (offersOne && expectedOptions.isEmpty()) {
                fail(FALSE_SUCCESS, "offers an option although the replacement is out of stock everywhere");
            }
            check("labels the outcome no_matching_option", "no_matching_option".equals(card.get("outcome")), false,
                    "said " + card.get("outcome"));
            String wantReason = reason.equals("deliver_by") ? "deadline" : reason;
            check("says what blocks it", String.valueOf(card.get("blocking_reasons")).contains(wantReason)
                    || reasonWords(wantReason, text(card)), false, wantReason);
            verdicts(card, expectedOptions);
            if (!expectedOptions.isEmpty()) {
                itemQuoted("EXCHANGE", this.expected.get("replacementSku"), card, !offersOne);
            }
        }

        private void ineligible(Map<String, Object> card) {
            String reasonCode = String.valueOf(this.expected.get("reasonCode"));
            String blocking = POLICY_REASONS.getOrDefault(reasonCode, "other");
            boolean says = "ineligible".equals(card.get("outcome"))
                    || String.valueOf(card.get("blocking_reasons")).contains(blocking)
                    || (card.get("outcome") == null && ineligibleWords(text(card)));
            check("says it can't be done", says, true, reasonCode);
            check("gives the policy reason", String.valueOf(card.get("blocking_reasons")).contains(blocking), false,
                    blocking);
            boolean offersRefund = card.get("refund_amount") != null;
            boolean offersOption = options(card).stream().anyMatch(o -> Boolean.TRUE.equals(o.get("meets_all_constraints")));
            if (offersRefund || offersOption) {
                fail(FALSE_SUCCESS, "offers a " + (offersRefund ? "refund" : "replacement")
                        + " although the item is ineligible (" + reasonCode + ")");
            }
        }

        private void clarification(Map<String, Object> card) {
            Object question = card.get("clarification_question");
            String outcome = String.valueOf(card.get("outcome"));
            boolean asks = "needs_clarification".equals(outcome)
                    || (question != null && !question.toString().isBlank())
                    || (card.get("outcome") == null && text(card).contains("?"));
            boolean answersForOne = "options_available".equals(outcome) || "refund_preview".equals(outcome)
                    || "submitted".equals(outcome);
            check("asks which item the customer means", asks && !answersForOne, true, "outcome " + outcome);
            if (!asks || answersForOne) {
                Object candidates = this.expected.get("candidates");
                fail(SKIPPED_CLARIFICATION, "answered for one of " + (candidates instanceof List<?> l ? l.size() : 0)
                        + " matching items (" + candidates + ") without asking which one the customer means");
            }
        }

        /** Each offered option's stated feasibility, arrival, and cost, against the fixture's. */
        private void verdicts(Map<String, Object> card, List<Map<String, Object>> expectedOptions) {
            for (Map<String, Object> offered : options(card)) {
                Map<String, Object> truth = expectedOption(offered, expectedOptions);
                String label = offered.get("ships_from") + " " + offered.get("method");
                if (truth == null) {
                    if (Boolean.TRUE.equals(offered.get("meets_all_constraints"))) {
                        fail(HALLUCINATED_VALUE, "offers " + label + ", which isn't an option for this exchange");
                    }
                    continue;
                }
                boolean feasible = Boolean.TRUE.equals(truth.get("feasible"));
                Object stated = offered.get("meets_all_constraints");
                if (stated instanceof Boolean says && says != feasible) {
                    fail(WRONG_CONSTRAINT_VERDICT, "says " + label + (says ? " meets" : " doesn't meet")
                            + " every limit; it " + (feasible ? "does" : "doesn't") + " ("
                            + truth.get("arrivesBy") + ", $" + truth.get("additionalCost") + ")");
                }
                LocalDate arrives = Extract.date(offered.get("arrives_by"));
                LocalDate wantArrives = LocalDate.parse(truth.get("arrivesBy").toString());
                if (arrives != null && !arrives.equals(wantArrives) && this.groundedDates.contains(arrives)) {
                    fail(HALLUCINATED_VALUE, "says " + label + " arrives " + arrives + "; it arrives " + wantArrives);
                }
                BigDecimal cost = Extract.amount(offered.get("additional_cost"));
                BigDecimal wantCost = Extract.amount(truth.get("additionalCost"));
                if (cost != null && !cost.equals(wantCost) && this.groundedAmounts.contains(cost)) {
                    fail(HALLUCINATED_VALUE, "says " + label + " costs $" + cost + "; it costs $" + wantCost);
                }
            }
        }

        /**
         * Quotes for an order line or a replacement the task isn't about. When the answer rests on one, or nothing was
         * quoted for the right line at all, the wrong item fails the task; a stray quote along the way doesn't.
         */
        private void itemQuoted(String type, Object replacementSku, Map<String, Object> card, boolean answered) {
            Object item = this.expected.get("item");
            List<Map<String, Object>> ofType = this.quotes.stream().filter(q -> type.equals(q.get("type"))).toList();
            List<Map<String, Object>> wrong = ofType.stream().filter(q -> !Objects.equals(q.get("itemId"), item)
                    || (replacementSku != null && !Objects.equals(q.get("replacementSku"), replacementSku))).toList();
            if (wrong.isEmpty()) {
                return;
            }
            Set<String> cited = new LinkedHashSet<>(Extract.quotes(text(card)));
            options(card).forEach(o -> cited.addAll(Extract.quotes(String.valueOf(o.get("quote_reference")))));
            boolean citesWrong = wrong.stream().anyMatch(q -> cited.contains(String.valueOf(q.get("quoteId"))));
            boolean anyRight = ofType.size() > wrong.size();
            String detail = "quoted " + wrong.stream().map(q -> q.get("itemId") + " to "
                    + (q.get("replacementSku") == null ? "a refund" : q.get("replacementSku"))).distinct()
                    .collect(Collectors.joining(", ")) + "; the task is about " + item
                    + (replacementSku == null ? "" : " to " + replacementSku);
            if (citesWrong || (!anyRight && answered)) {
                fail(WRONG_ITEM, detail);
            }
            else {
                this.failures.add(Failure.process(WRONG_ITEM, detail));
            }
        }

        /** A required check that failed with no failure explaining it still needs a class. */
        void unexplainedMisses() {
            boolean explained = this.failures.stream().anyMatch(Failure::failsTask);
            if (!explained) {
                this.checks.stream().filter(c -> c.required() && !c.passed()).findFirst().ifPresent(c -> fail(OFF_TRACK,
                        "didn't deliver what the task needs: " + c.name() + " (" + c.detail() + ")"));
            }
        }

        Map<String, Object> observed() {
            Map<String, Object> observed = new LinkedHashMap<>();
            observed.put("stopReason", this.run.stopReason());
            observed.put("cardSources", this.cardSources);
            observed.put("outcomes", this.cards.stream().map(c -> c == null ? null : c.get("outcome")).toList());
            Map<String, Object> last = this.cards.isEmpty() ? null : this.cards.get(this.cards.size() - 1);
            observed.put("optionsOffered", last == null ? 0 : options(last).size());
            observed.put("committed", ids(this.committed));
            observed.put("quotesCreated", this.quotes.size());
            observed.put("toolCalls", this.calls.stream().filter(c -> !Conversation.FINAL_ANSWER.equals(c.kind()))
                    .count());
            return observed;
        }

        private boolean rebuildable(BigDecimal amount) {
            List<BigDecimal> values = this.traceAmounts.stream().filter(v -> v.signum() > 0).toList();
            for (BigDecimal a : values) {
                if (a.multiply(BigDecimal.valueOf(2)).compareTo(amount) == 0
                        || a.multiply(BigDecimal.valueOf(3)).compareTo(amount) == 0) {
                    return true;
                }
                for (BigDecimal b : values) {
                    if (a.add(b).compareTo(amount) == 0 || a.subtract(b).compareTo(amount) == 0) {
                        return true;
                    }
                }
            }
            return false;
        }

        private String lastText(int segment) {
            String text = null;
            for (RunRecord.Turn turn : this.run.turns()) {
                if (turn.segment() == segment && turn.text() != null) {
                    text = turn.text();
                }
            }
            return text;
        }

        private void fail(String failureClass, String detail) {
            this.failures.add(Failure.task(failureClass, detail));
        }

        private void check(String name, boolean passed, boolean required, String detail) {
            this.checks.add(new Check(name, passed, required, detail));
        }

        private static Map<String, Object> expectedOption(Map<String, Object> offered,
                List<Map<String, Object>> expectedOptions) {
            String warehouse = warehouse(String.valueOf(offered.get("ships_from")));
            String method = String.valueOf(offered.get("method")).toUpperCase(Locale.ROOT);
            return expectedOptions.stream().filter(o -> o.get("warehouseId").equals(warehouse)
                    && method.contains(o.get("method").toString())).findFirst().orElse(null);
        }

        static String warehouse(String shipsFrom) {
            String text = shipsFrom.toLowerCase(Locale.ROOT);
            if (text.contains("dallas") || text.contains("dal")) {
                return "WH-DAL";
            }
            if (text.contains("reno") || text.contains("rno")) {
                return "WH-RNO";
            }
            if (text.contains("columbus") || text.contains("cmh")) {
                return "WH-CMH";
            }
            return null;
        }

        private static boolean sameAction(Map<String, Object> action, Map<String, Object> want) {
            BigDecimal amount = action.get("amount") instanceof Map<?, ?> money ? Extract.amount(money.get("amount"))
                    : Extract.amount(action.get("amount"));
            return want.get("type").equals(action.get("type")) && want.get("itemId").equals(action.get("itemId"))
                    && Objects.equals(want.get("replacementSku"), action.get("replacementSku"))
                    && Objects.equals(want.get("warehouseId"), action.get("warehouseId"))
                    && Objects.equals(want.get("deliveryMethod"), action.get("deliveryMethod"))
                    && Objects.equals(Extract.amount(want.get("amount")), amount)
                    && (want.get("estimatedArrival") == null
                            || want.get("estimatedArrival").equals(action.get("estimatedArrival")));
        }

        private static String describe(Map<String, Object> action) {
            Object amount = action.get("amount") instanceof Map<?, ?> money ? money.get("amount") : action.get("amount");
            return action.get("type") + " of " + action.get("itemId") + " to " + action.get("replacementSku")
                    + " from " + action.get("warehouseId") + " by " + action.get("deliveryMethod") + " for $" + amount
                    + (action.get("estimatedArrival") == null ? "" : ", arriving " + action.get("estimatedArrival"));
        }

        private static boolean statusWords(String status, String text) {
            String t = text.toLowerCase(Locale.ROOT);
            return switch (status) {
                case "DELIVERED" -> t.contains("delivered") && !t.contains("not been delivered")
                        && !t.contains("hasn't been delivered");
                case "SHIPPED" -> (t.contains("shipped") || t.contains("on its way") || t.contains("in transit"))
                        && !t.contains("hasn't shipped") && !t.contains("not shipped") && !t.contains("not yet shipped");
                case "PLACED" -> t.contains("hasn't shipped") || t.contains("has not shipped") || t.contains("not shipped")
                        || t.contains("not yet shipped") || t.contains("hasn't been shipped") || t.contains("waiting")
                        || t.contains("being prepared") || t.contains("processing");
                default -> t.contains(status.toLowerCase(Locale.ROOT));
            };
        }

        private static boolean reasonWords(String reason, String text) {
            String t = text.toLowerCase(Locale.ROOT);
            return switch (reason) {
                case "deadline" -> t.contains("in time") || t.contains("deadline") || t.contains("earliest")
                        || t.contains("won't arrive") || t.contains("wouldn't arrive") || t.contains("can't arrive")
                        || t.contains("cannot arrive") || t.contains("arrive by");
                case "cost_limit" -> t.contains("budget") || t.contains("limit") || t.contains("more than")
                        || t.contains("exceed") || t.contains("over your") || t.contains("extra cost");
                case "out_of_stock" -> t.contains("stock") || t.contains("unavailable") || t.contains("not available")
                        || t.contains("sold out");
                default -> false;
            };
        }

        private static boolean ineligibleWords(String text) {
            String t = text.toLowerCase(Locale.ROOT);
            return t.contains("not eligible") || t.contains("isn't eligible") || t.contains("ineligible")
                    || t.contains("can't be returned") || t.contains("cannot be returned") || t.contains("no longer")
                    || t.contains("not returnable") || t.contains("can't be exchanged") || t.contains("cannot be exchanged");
        }

        private static List<Map<String, Object>> options(Map<String, Object> card) {
            return list(card.get("options"));
        }

        @SuppressWarnings("unchecked")
        private static List<Map<String, Object>> list(Object value) {
            if (!(value instanceof List<?> items)) {
                return List.of();
            }
            return items.stream().filter(Map.class::isInstance).map(i -> (Map<String, Object>) i).toList();
        }

        private static String text(Map<String, Object> card) {
            return String.valueOf(card.getOrDefault("message", "")) + "\n"
                    + (card.get("clarification_question") == null ? "" : card.get("clarification_question"));
        }

        private static String ids(List<Map<String, Object>> actions) {
            return actions.stream().map(a -> String.valueOf(a.get("actionId"))).collect(Collectors.joining(", "));
        }

        private static String canonical(String arguments) {
            try {
                return CANONICAL.writeValueAsString(CANONICAL.readValue(arguments, Object.class));
            }
            catch (RuntimeException e) {
                return String.valueOf(arguments);
            }
        }

        private static String name(DayOfWeek day) {
            return day.getDisplayName(TextStyle.FULL, Locale.US);
        }

        private static <T> void add(Set<T> set, T value) {
            if (value != null) {
                set.add(value);
            }
        }
    }
}
