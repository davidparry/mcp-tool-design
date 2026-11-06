package com.davidparry.tooldesign.workflow.tools;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.davidparry.tooldesign.workflow.WorkflowProperties;
import com.davidparry.tooldesign.workflow.api.ApiProblem;
import com.davidparry.tooldesign.workflow.api.ApiTypes.DeliveryOption;
import com.davidparry.tooldesign.workflow.api.ApiTypes.Eligibility;
import com.davidparry.tooldesign.workflow.api.ApiTypes.ExchangeQuote;
import com.davidparry.tooldesign.workflow.api.ApiTypes.ExchangeQuoteRequest;
import com.davidparry.tooldesign.workflow.api.ApiTypes.ExchangeView;
import com.davidparry.tooldesign.workflow.api.ApiTypes.Inventory;
import com.davidparry.tooldesign.workflow.api.ApiTypes.OrderDetail;
import com.davidparry.tooldesign.workflow.api.ApiTypes.OrderItem;
import com.davidparry.tooldesign.workflow.api.ApiTypes.ProductVariants;
import com.davidparry.tooldesign.workflow.api.ApiTypes.Variant;
import com.davidparry.tooldesign.workflow.api.ApiTypes.WarehouseStock;
import com.davidparry.tooldesign.workflow.api.OrderApi;
import com.davidparry.tooldesign.workflow.matching.ItemMatcher;
import com.davidparry.tooldesign.workflow.matching.Sizes;
import com.davidparry.tooldesign.workflow.tools.Inputs.CostLimit;
import com.davidparry.tooldesign.workflow.tools.Inputs.InvalidInput;
import com.davidparry.tooldesign.workflow.tools.Results.Constraints;
import com.davidparry.tooldesign.workflow.tools.Results.ExchangePreview;
import com.davidparry.tooldesign.workflow.tools.Results.ExchangeSubmission;
import com.davidparry.tooldesign.workflow.tools.Results.Ineligible;
import com.davidparry.tooldesign.workflow.tools.Results.Item;
import com.davidparry.tooldesign.workflow.tools.Results.Miss;
import com.davidparry.tooldesign.workflow.tools.Results.Option;
import com.davidparry.tooldesign.workflow.tools.Results.RejectedOption;
import com.davidparry.tooldesign.workflow.tools.Results.Replacement;
import com.davidparry.tooldesign.workflow.tools.Results.Stock;

@Component
public class ExchangeTools {

    private static final Set<String> SAME = Set.of("same", "same color", "same colour", "current", "unchanged");

    private final OrderApi api;

    private final WorkflowProperties properties;

    public ExchangeTools(OrderApi api, WorkflowProperties properties) {
        this.api = api;
        this.properties = properties;
    }

    /** A quote and the limits it misses. */
    private record Quoted(ExchangeQuote quote, String shipsFrom, List<Miss> misses) {

        BigDecimal cost() {
            return this.quote.additionalCost().value();
        }

        LocalDate arrives() {
            return this.quote.estimatedArrival();
        }
    }

    @McpTool(name = "preview_exchange", title = "Preview an exchange", generateOutputSchema = true,
            description = "Find and price replacements for an item in an order. Matches the item from the "
                    + "customer's description, checks that it can be exchanged, finds the replacement in the size "
                    + "and color asked for, and quotes every in-stock warehouse and delivery method against the "
                    + "delivery deadline and cost limit. Returns an explicit outcome, up to 3 options that meet "
                    + "every limit (cheapest first) with quote IDs, and the closest options that don't, with the "
                    + "limit each one misses. Creates draft quotes that expire in 30 minutes. It never submits an "
                    + "exchange, reserves stock, or charges the customer.",
            annotations = @McpTool.McpAnnotations(title = "Preview an exchange", readOnlyHint = false,
                    destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public ExchangePreview previewExchange(
            @McpToolParam(description = "The order reference, such as 1042") String orderReference,
            @McpToolParam(description = "The item the customer has now, in their words, such as \"medium dog "
                    + "harness\"") String itemDescription,
            @McpToolParam(description = "The size they want instead, such as \"large\", \"XL\", or \"42 in\". "
                    + "Leave out to keep the current size.", required = false) String replacementSize,
            @McpToolParam(description = "The color they want instead, such as \"red\". Leave out to keep the "
                    + "current color.", required = false) String replacementColor,
            @McpToolParam(description = "How many units to exchange. Defaults to 1.", required = false) Integer quantity,
            @McpToolParam(description = "The latest acceptable arrival date, YYYY-MM-DD. Arriving on that date "
                    + "counts as on time.", required = false) String deliverBy,
            @McpToolParam(description = "The most the customer will pay on top of what they paid: the price "
                    + "difference plus shipping.", required = false) CostLimit costLimit) {
        int units;
        LocalDate deadline;
        CostLimit limit;
        try {
            units = Inputs.quantity(quantity);
            deadline = Inputs.date("deliverBy", deliverBy);
            limit = Inputs.costLimit(costLimit);
            if (blank(replacementSize) && blank(replacementColor)) {
                throw new InvalidInput("Say what to exchange the item for: replacementSize, replacementColor, or both");
            }
        }
        catch (InvalidInput e) {
            return refusal("invalid_request", e.getMessage(), orderReference, null, null);
        }
        Constraints constraints = new Constraints(units, deadline, limit);

        OrderDetail order;
        try {
            order = this.api.order(orderReference);
        }
        catch (ApiProblem problem) {
            if (problem.status() == 404) {
                return refusal("order_not_found", "There's no order " + orderReference + ".", orderReference, null,
                        null);
            }
            throw problem;
        }
        OrderLines orderLines = new OrderLines(this.api);
        List<ItemMatcher.Line> lines = orderLines.lines(this.api.items(orderReference).items());
        ItemMatcher.Result match = ItemMatcher.match(itemDescription, lines);
        if (match instanceof ItemMatcher.Ambiguous(List<ItemMatcher.Line> candidates)) {
            return refusal("needs_clarification", "\"" + itemDescription + "\" fits " + candidates.size()
                    + " items in order " + orderReference + ". Ask the customer which one they mean.",
                    orderReference, null, OrderLines.items(candidates));
        }
        if (match instanceof ItemMatcher.NotFound(List<ItemMatcher.Line> all)) {
            return refusal("item_not_found", "Nothing in order " + orderReference + " fits \"" + itemDescription
                    + "\". These are the order's items.", orderReference, null, OrderLines.items(all));
        }
        OrderItem item = ((ItemMatcher.Matched) match).line().item();
        Item matched = OrderLines.item(item);

        Eligibility eligibility = this.api.exchangeEligibility(orderReference, item.itemId());
        if (!eligibility.eligible()) {
            return new ExchangePreview("ineligible", eligibility.reason(), orderReference, matched, null, null,
                    constraints, ineligible(eligibility, order), null, null, null, null, null, null, null, null,
                    null, null, null, "Tell the customer why this item can't be exchanged. Nothing was quoted.");
        }
        if (units > eligibility.maxQuantity()) {
            return refusal("invalid_request", "Only " + eligibility.maxQuantity() + " of this item can be exchanged.",
                    orderReference, matched, null);
        }

        ProductVariants product = orderLines.product(item.productId());
        String size = blank(replacementSize) ? item.size() : replacementSize;
        String color = blank(replacementColor) || SAME.contains(replacementColor.trim().toLowerCase(Locale.ROOT))
                ? item.color() : replacementColor;
        Variant target = product.variants().stream().filter(v -> sameSize(v.size(), size) && sameColor(v.color(), color))
                .findFirst().orElse(null);
        List<Replacement> variants = product.variants().stream()
                .map(v -> new Replacement(v.sku(), v.size(), v.color(), v.price())).toList();
        if (target == null) {
            return noOption(orderReference, matched, null, constraints, List.of("variant_not_found"), null, variants,
                    product.productName() + " doesn't come in " + describe(size, color) + ".");
        }
        Replacement replacement = new Replacement(target.sku(), target.size(), target.color(), target.price());
        if (target.sku().equals(item.sku())) {
            return refusal("invalid_request", "The customer already has " + target.sku() + "; ask what they want "
                    + "instead.", orderReference, matched, null);
        }

        Inventory inventory = this.api.inventory(target.sku());
        Map<String, String> cities = inventory.warehouses().stream()
                .collect(Collectors.toMap(WarehouseStock::warehouseId, w -> w.city() + ", " + w.state()));
        List<WarehouseStock> stocked = inventory.warehouses().stream().filter(w -> w.available() >= units).toList();
        if (stocked.isEmpty()) {
            List<Stock> stock = inventory.warehouses().stream()
                    .map(w -> new Stock(w.warehouseId(), cities.get(w.warehouseId()), w.available())).toList();
            return noOption(orderReference, matched, replacement, constraints, List.of("out_of_stock"), stock, null,
                    "No warehouse has " + units + " of " + target.sku() + " in stock.");
        }

        List<Quoted> quoted = new ArrayList<>();
        for (WarehouseStock warehouse : stocked) {
            for (DeliveryOption option : this.api.deliveryOptions(warehouse.warehouseId(), order.shipTo().postalCode())
                    .options()) {
                try {
                    ExchangeQuote quote = this.api.createExchangeQuote(new ExchangeQuoteRequest(orderReference,
                            item.itemId(), target.sku(), units, warehouse.warehouseId(), option.method()));
                    quoted.add(new Quoted(quote, cities.get(warehouse.warehouseId()), misses(quote, deadline, limit)));
                }
                catch (ApiProblem problem) {
                    if (!"stock_unavailable".equals(problem.code())) {
                        throw problem;
                    }
                }
            }
        }
        return judged(orderReference, matched, replacement, constraints, quoted, deadline, limit);
    }

    @McpTool(name = "submit_exchange", title = "Submit an exchange", generateOutputSchema = true,
            description = "Submit one exchange quote from preview_exchange after the customer explicitly approves "
                    + "that option. Commits the exchange, takes the replacement from stock, and charges the "
                    + "additional cost. The quote is checked again first: an expired quote, missing stock, or an "
                    + "item that can no longer be exchanged is refused with the reason, and nothing is committed. "
                    + "Submitting the same quote again returns the original exchange and changes nothing.",
            annotations = @McpTool.McpAnnotations(title = "Submit an exchange", readOnlyHint = false,
                    destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public ExchangeSubmission submitExchange(
            @McpToolParam(description = "The quoteId of the option the customer approved, such as EQ-5001") String quoteId) {
        try {
            OrderApi.Submitted<ExchangeView> submitted = this.api.submitExchange(quoteId);
            ExchangeView exchange = submitted.body();
            String message = submitted.created()
                    ? "Exchange " + exchange.exchangeId() + " is confirmed. " + exchange.replacementSku() + " ships "
                            + exchange.deliveryMethod().toLowerCase(Locale.ROOT) + " and arrives by "
                            + exchange.estimatedArrival() + "; the customer is charged $"
                            + exchange.additionalCost().amount() + "."
                    : "Quote " + quoteId + " was already submitted as exchange " + exchange.exchangeId()
                            + ". Nothing new was committed or charged.";
            return new ExchangeSubmission(submitted.created() ? "submitted" : "already_submitted", message,
                    exchange.exchangeId(), exchange.quoteId(), exchange.status(), exchange.orderReference(),
                    exchange.itemId(), exchange.replacementSku(), exchange.quantity(), exchange.warehouseId(),
                    exchange.deliveryMethod(), exchange.estimatedArrival(), exchange.additionalCost(),
                    exchange.submittedAt(), submitted.created() ? "Tell the customer the exchange is confirmed."
                            : "Tell the customer the exchange was already in place. Don't submit again.");
        }
        catch (ApiProblem problem) {
            String outcome = switch (problem.code()) {
                case "quote_expired", "stock_unavailable", "quote_not_found" -> problem.code();
                case "not_eligible" -> "ineligible";
                default -> "invalid_request";
            };
            String nextStep = switch (outcome) {
                case "quote_expired", "stock_unavailable" -> "Nothing was committed. Run preview_exchange again for "
                        + "current options, show them, and get the customer's approval again before submitting.";
                default -> "Nothing was committed. Tell the customer why.";
            };
            return new ExchangeSubmission(outcome, problem.detail(), null, quoteId, null, null, null, null, null,
                    null, null, null, null, null, nextStep);
        }
    }

    private ExchangePreview judged(String reference, Item matched, Replacement replacement, Constraints constraints,
            List<Quoted> quoted, LocalDate deadline, CostLimit limit) {
        int optionLimit = this.properties.optionLimit();
        Comparator<Quoted> cheapest = Comparator.comparing(Quoted::cost).thenComparing(Quoted::arrives);
        List<Quoted> feasible = quoted.stream().filter(q -> q.misses().isEmpty()).sorted(cheapest).toList();
        Comparator<Quoted> closest = Comparator.comparingInt((Quoted q) -> q.misses().size())
                .thenComparing(deadline != null ? Comparator.comparing(Quoted::arrives).thenComparing(Quoted::cost)
                        : cheapest);
        List<Quoted> rejected = quoted.stream().filter(q -> !q.misses().isEmpty()).sorted(closest).toList();

        List<Option> options = feasible.stream().limit(optionLimit).map(ExchangeTools::option).toList();
        List<RejectedOption> rejectedOptions = rejected.stream().limit(optionLimit).map(ExchangeTools::rejected)
                .toList();
        LocalDate earliest = quoted.stream().map(Quoted::arrives).min(Comparator.naturalOrder()).orElse(null);
        Quoted lowest = quoted.stream().min(cheapest).orElse(null);
        List<String> blocking = feasible.isEmpty() ? rejected.stream().flatMap(q -> q.misses().stream())
                .map(Miss::constraint).distinct().sorted(Comparator.comparing(c -> c.equals("deadline") ? 0 : 1))
                .toList() : null;

        String message;
        String nextStep;
        if (!feasible.isEmpty()) {
            Quoted best = feasible.getFirst();
            message = feasible.size() + " of " + quoted.size() + " quoted options "
                    + (feasible.size() == 1 ? "meets" : "meet") + " every limit. The cheapest is $"
                    + best.quote().additionalCost().amount() + " from " + best.shipsFrom() + " by "
                    + best.quote().deliveryMethod().toLowerCase(Locale.ROOT) + ", arriving " + best.arrives() + ".";
            nextStep = "Show the customer these options. Call submit_exchange with the chosen quoteId only after the "
                    + "customer approves that option. The quotes expire at "
                    + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(best.quote().expiresAt()) + ".";
        }
        else {
            message = noOptionMessage(blocking, deadline, limit, earliest, lowest);
            nextStep = "Tell the customer no option meets their limits and what the closest options are. Nothing was "
                    + "submitted.";
        }
        return new ExchangePreview(feasible.isEmpty() ? "no_matching_option" : "options_available", message,
                reference, matched, null, replacement, constraints, null, options,
                rejectedOptions.isEmpty() ? null : rejectedOptions, quoted.size(), feasible.size(),
                feasible.size() > optionLimit, optionLimit, earliest, lowest == null ? null : lowest.quote().additionalCost(),
                blocking, null, null, nextStep);
    }

    private static String noOptionMessage(List<String> blocking, LocalDate deadline, CostLimit limit,
            LocalDate earliest, Quoted lowest) {
        String earliestText = "the earliest any option arrives is " + earliest;
        String lowestText = "the lowest additional cost is $" + lowest.quote().additionalCost().amount();
        if (blocking.equals(List.of("deadline"))) {
            return "No option arrives by " + deadline + "; " + earliestText + ".";
        }
        if (blocking.equals(List.of("cost_limit"))) {
            return "No option costs " + limit.describe() + "; " + lowestText + ".";
        }
        return "No option both arrives by " + deadline + " and costs " + limit.describe() + ". Of all options, "
                + earliestText + " and " + lowestText + ".";
    }

    private static List<Miss> misses(ExchangeQuote quote, LocalDate deadline, CostLimit limit) {
        List<Miss> misses = new ArrayList<>();
        if (deadline != null && quote.estimatedArrival().isAfter(deadline)) {
            misses.add(new Miss("deadline", deadline.toString(), quote.estimatedArrival().toString()));
        }
        if (limit != null && !limit.allows(quote.additionalCost().value())) {
            misses.add(new Miss("cost_limit", limit.describe(), "$" + quote.additionalCost().amount()));
        }
        return misses;
    }

    private static Option option(Quoted q) {
        ExchangeQuote quote = q.quote();
        return new Option(quote.quoteId(), q.shipsFrom(), quote.warehouseId(), quote.deliveryMethod(),
                quote.estimatedArrival(), quote.priceDifference(), quote.shippingCharge(), quote.additionalCost(),
                quote.expiresAt());
    }

    private static RejectedOption rejected(Quoted q) {
        ExchangeQuote quote = q.quote();
        return new RejectedOption(quote.quoteId(), q.shipsFrom(), quote.warehouseId(), quote.deliveryMethod(),
                quote.estimatedArrival(), quote.additionalCost(), q.misses());
    }

    private static ExchangePreview noOption(String reference, Item matched, Replacement replacement,
            Constraints constraints, List<String> blocking, List<Stock> stock, List<Replacement> variants,
            String message) {
        return new ExchangePreview("no_matching_option", message, reference, matched, null, replacement, constraints,
                null, null, null, 0, 0, false, null, null, null, blocking, stock, variants,
                "Tell the customer why no option works. Nothing was quoted or submitted.");
    }

    private static ExchangePreview refusal(String outcome, String message, String reference, Item matched,
            List<Item> candidates) {
        String nextStep = switch (outcome) {
            case "needs_clarification", "item_not_found" -> "Ask the customer which item they mean, then call "
                    + "preview_exchange again with that item's description.";
            default -> "Fix the request and call preview_exchange again.";
        };
        return new ExchangePreview(outcome, message, reference, matched, candidates, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, nextStep);
    }

    static Ineligible ineligible(Eligibility eligibility, OrderDetail order) {
        return new Ineligible(eligibility.reasonCode(), eligibility.reason(),
                "window_closed".equals(eligibility.reasonCode()) ? eligibility.windowEndsOn() : null,
                "not_delivered".equals(eligibility.reasonCode()) ? order.estimatedDelivery() : null);
    }

    private static boolean sameSize(String variantSize, String wanted) {
        if (variantSize == null || wanted == null) {
            return Objects.equals(variantSize, wanted);
        }
        return Sizes.same(variantSize, wanted) || variantSize.equalsIgnoreCase(wanted.trim());
    }

    private static boolean sameColor(String variantColor, String wanted) {
        if (variantColor == null || wanted == null) {
            return Objects.equals(variantColor, wanted);
        }
        return variantColor.equalsIgnoreCase(wanted.trim());
    }

    private static String describe(String size, String color) {
        return new LinkedHashSet<>(java.util.stream.Stream.of(size, color).filter(Objects::nonNull).toList()).stream()
                .collect(Collectors.joining(", "));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
