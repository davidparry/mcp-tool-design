package com.davidparry.tooldesign.workflow.tools;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.jspecify.annotations.Nullable;

import com.davidparry.tooldesign.workflow.api.ApiTypes.Money;

/**
 * What the workflow tools return. Every result has an explicit outcome. Fields that don't apply to an outcome are
 * left out, and {@code @Nullable} is what keeps them out of the output schema's required list; the SDK validates
 * every result against that schema.
 */
public final class Results {

    private Results() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String itemId, String product, String sku, @Nullable String size, @Nullable String color,
            Integer quantity, Money unitPrice) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrderBrief(String reference, String customerName, String status, LocalDate placedOn,
            @Nullable LocalDate shippedOn, @Nullable LocalDate deliveredOn, @Nullable LocalDate estimatedDelivery,
            List<String> items, Money total) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrderSearch(@JsonPropertyDescription("orders_found or no_orders_found") String outcome,
            String message, List<OrderBrief> orders, long totalMatches, boolean moreAvailable, int limit) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ShipTo(String city, String state, String postalCode) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Eligibility(boolean eligible,
            @JsonPropertyDescription("The last day it can be done, inclusive") @Nullable LocalDate lastDay,
            @Nullable String reasonCode, @Nullable String reason, @Nullable Integer maxQuantity) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemContext(String itemId, String product, String sku, @Nullable String size,
            @Nullable String color, int quantity, Money unitPrice, int returnedQuantity, int exchangedQuantity,
            Eligibility returnEligibility, Eligibility exchangeEligibility) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrderContext(@JsonPropertyDescription("found or order_not_found") String outcome, String message,
            String reference, @Nullable String status, @Nullable String customerName, @Nullable LocalDate placedOn,
            @Nullable LocalDate shippedOn, @Nullable LocalDate deliveredOn, @Nullable LocalDate estimatedDelivery,
            @Nullable ShipTo shipTo, @Nullable List<ItemContext> items, @Nullable Money total) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Constraints(int quantity, @Nullable LocalDate deliverBy, Inputs.@Nullable CostLimit costLimit) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Replacement(String sku, @Nullable String size, @Nullable String color, Money price) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Ineligible(String reasonCode, String reason, @Nullable LocalDate windowEndedOn,
            @Nullable LocalDate estimatedDelivery) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Option(String quoteId, String shipsFrom, String warehouseId, String method, LocalDate arrives,
            Money priceDifference, Money shipping,
            @JsonPropertyDescription("What the customer pays: the price difference plus shipping") Money additionalCost,
            OffsetDateTime quoteExpiresAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Miss(@JsonPropertyDescription("deadline or cost_limit") String constraint, String limit,
            String actual) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RejectedOption(String quoteId, String shipsFrom, String warehouseId, String method,
            LocalDate arrives, Money additionalCost, List<Miss> misses) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Stock(String warehouseId, String shipsFrom, int available) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExchangePreview(
            @JsonPropertyDescription("options_available, no_matching_option, ineligible, needs_clarification, "
                    + "item_not_found, order_not_found, or invalid_request") String outcome,
            String message, @Nullable String orderReference, @Nullable Item matchedItem,
            @JsonPropertyDescription("The order's items that fit the description, when it fits more than one or none") @Nullable List<Item> candidates,
            @Nullable Replacement replacement, @Nullable Constraints constraints, @Nullable Ineligible ineligible,
            @JsonPropertyDescription("Options that meet every limit, cheapest first") @Nullable List<Option> options,
            @JsonPropertyDescription("The closest options that miss a limit, with what each one misses") @Nullable List<RejectedOption> rejectedOptions,
            @Nullable Integer quotedOptions, @Nullable Integer optionsMeetingLimits,
            @JsonPropertyDescription("True when more options meet every limit than are listed") @Nullable Boolean moreAvailable,
            @Nullable Integer optionLimit, @Nullable LocalDate earliestArrival, @Nullable Money lowestAdditionalCost,
            @JsonPropertyDescription("Why no option works: deadline, cost_limit, out_of_stock, or variant_not_found") @Nullable List<String> blockingReasons,
            @Nullable List<Stock> stock, @Nullable List<Replacement> availableVariants, String nextStep) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReturnPreview(
            @JsonPropertyDescription("refund_preview, ineligible, needs_clarification, item_not_found, "
                    + "order_not_found, or invalid_request") String outcome,
            String message, @Nullable String orderReference, @Nullable Item matchedItem,
            @Nullable List<Item> candidates, @Nullable Ineligible ineligible, @Nullable Integer quantity,
            @Nullable Money itemRefund, @Nullable Money returnShippingFee, @Nullable Money restockingFee,
            @JsonPropertyDescription("What the customer gets back after fees") @Nullable Money refundAmount,
            @Nullable String quoteId, @Nullable OffsetDateTime quoteExpiresAt, @Nullable LocalDate windowEndsOn,
            @Nullable String policy, String nextStep) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExchangeSubmission(
            @JsonPropertyDescription("submitted, already_submitted, quote_expired, stock_unavailable, ineligible, "
                    + "quote_not_found, or invalid_request") String outcome,
            String message, @Nullable String exchangeId, String quoteId, @Nullable String status,
            @Nullable String orderReference, @Nullable String itemId, @Nullable String replacementSku,
            @Nullable Integer quantity, @Nullable String warehouseId, @Nullable String method,
            @Nullable LocalDate estimatedArrival, @Nullable Money additionalCost, @Nullable OffsetDateTime submittedAt,
            String nextStep) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReturnSubmission(
            @JsonPropertyDescription("submitted, already_submitted, quote_expired, ineligible, quote_not_found, or "
                    + "invalid_request") String outcome,
            String message, @Nullable String returnId, String quoteId, @Nullable String status,
            @Nullable String orderReference, @Nullable String itemId, @Nullable Integer quantity,
            @Nullable Money refundAmount, @Nullable OffsetDateTime submittedAt, String nextStep) {
    }
}
