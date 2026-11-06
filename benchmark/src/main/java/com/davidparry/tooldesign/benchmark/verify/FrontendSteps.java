package com.davidparry.tooldesign.benchmark.verify;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.davidparry.tooldesign.benchmark.run.RunRecord;
import com.davidparry.tooldesign.benchmark.run.TaskSpec;

/**
 * What the support screens in {@code frontend/} ask the store for on a task, against what the store's call log and
 * quotes show a run asked for. The screens check eligibility before a replacement can be chosen, check the
 * replacement's stock, and quote every delivery method at every warehouse that has it. They make the person pick
 * the item, so a task whose words fit two items has no single path. Both arms are measured from the app's records,
 * whether the model or the server's code made the calls.
 *
 * @param screenCalls the API calls the screens make for the task as asked, from the search box to the last quote;
 *        null when the customer's words fit more than one item
 * @param eligibilityChecked whether the store was asked if the item can be returned or exchanged; null when the task
 *        has no single item
 * @param stockChecked whether the store was asked for the replacement's stock; null when there's no replacement
 * @param optionsQuoted how many of the options the screens would quote were quoted
 * @param optionsOnScreen how many options the screens would quote
 * @param fitsNotQuoted options that meet every limit and were never quoted
 * @param refusedCalls calls the store answered with a 4xx
 * @param unaskedQuotes quotes for an item or a replacement the customer didn't ask about; null when the customer's
 *        words fit more than one item
 * @param cardOutcome the outcome on the last card, empty when there's none
 */
public record FrontendSteps(Integer screenCalls, int backendCalls, Boolean eligibilityChecked, Boolean stockChecked,
        int optionsQuoted, int optionsOnScreen, int fitsNotQuoted, int refusedCalls, Integer unaskedQuotes,
        String cardOutcome, String expectedOutcome) {

    static FrontendSteps of(RunRecord run, TaskSpec task) {
        Map<String, Object> expected = task.expected();
        String item = string(expected.get("item"));
        String replacement = string(expected.get("replacementSku"));
        boolean ambiguous = "ambiguous".equals(task.category());
        boolean oneItem = item != null && !ambiguous;
        boolean exchange = "exchange".equals(task.kind());
        List<Map<String, Object>> options = list(expected.get("options"));
        List<Map<String, Object>> calls = run.backendCalls() == null ? List.of() : run.backendCalls();
        List<Map<String, Object>> quotes = run.stateAfter() == null ? List.of() : list(run.stateAfter().get("quotes"));

        Boolean eligibility = oneItem && !"lookup".equals(task.kind()) ? calls.stream().anyMatch(
                c -> String.valueOf(c.get("path")).endsWith("/items/" + item + "/" + task.kind() + "-eligibility"))
                : null;
        Boolean stock = oneItem && exchange && replacement != null ? calls.stream().anyMatch(
                c -> "/api/inventory".equals(c.get("path"))
                        && Arrays.asList(String.valueOf(c.get("query")).split("&")).contains("sku=" + replacement))
                : null;

        Set<String> asked = new HashSet<>();
        int unasked = 0;
        for (Map<String, Object> quote : quotes) {
            boolean forTheTask = (exchange ? "EXCHANGE" : "RETURN").equals(quote.get("type"))
                    && Objects.equals(item, quote.get("itemId"))
                    && (!exchange || Objects.equals(replacement, quote.get("replacementSku")));
            if (forTheTask) {
                asked.add(quote.get("warehouseId") + "|" + quote.get("deliveryMethod"));
            }
            else {
                unasked++;
            }
        }
        int quoted = 0;
        int fitsNotQuoted = 0;
        for (Map<String, Object> option : options) {
            if (asked.contains(option.get("warehouseId") + "|" + option.get("method"))) {
                quoted++;
            }
            else if (Boolean.TRUE.equals(option.get("feasible"))) {
                fitsNotQuoted++;
            }
        }
        int refused = (int) calls.stream().filter(c -> c.get("status") instanceof Number n && n.intValue() >= 400).count();
        return new FrontendSteps(screenCalls(task, options, oneItem), calls.size(), eligibility, stock, quoted,
                options.size(), fitsNotQuoted, refused, ambiguous ? null : unasked, lastOutcome(run),
                Objects.toString(expected.get("outcome"), ""));
    }

    /**
     * The search, then the order and its items; for a return or an exchange, the eligibility check, which ends an
     * ineligible task; then the refund quote, or the variants, the stock, and the delivery options and a quote for
     * each method at each warehouse that has the replacement.
     */
    private static Integer screenCalls(TaskSpec task, List<Map<String, Object>> options, boolean oneItem) {
        if ("lookup".equals(task.kind())) {
            return 3;
        }
        if (!oneItem) {
            return null;
        }
        if ("ineligible".equals(task.category())) {
            return 4;
        }
        if (!"exchange".equals(task.kind())) {
            return 5;
        }
        int warehouses = (int) options.stream().map(o -> o.get("warehouseId")).distinct().count();
        return 6 + warehouses + options.size();
    }

    private static String lastOutcome(RunRecord run) {
        List<Map<String, Object>> cards = run.cards() == null ? List.of() : run.cards();
        for (int i = cards.size() - 1; i >= 0; i--) {
            if (cards.get(i) != null) {
                return Objects.toString(cards.get(i).get("outcome"), "");
            }
        }
        return "";
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        return items.stream().filter(Map.class::isInstance).map(i -> (Map<String, Object>) i).toList();
    }
}
