package com.davidparry.tooldesign.app.api;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.davidparry.tooldesign.app.domain.Catalog.DeliveryMethod;
import com.davidparry.tooldesign.app.domain.Money;
import com.davidparry.tooldesign.app.domain.Orders.OrderStatus;

/** The request and response bodies of the order-support API. Every field is shown by the frontend or is an ID. */
public final class ApiModels {

    private ApiModels() {
    }

    @Schema(description = "One order in a search result.")
    public record OrderSummary(
            @Schema(example = "1042") String reference,
            String customerName,
            String customerEmail,
            OrderStatus status,
            OffsetDateTime placedAt,
            @Schema(description = "Number of units across all of the order's items.") int itemCount,
            Money total) {
    }

    @Schema(description = "A page of orders, newest first.")
    public record OrderSearchPage(List<OrderSummary> items, int page, int size, long totalItems, int totalPages) {
    }

    public record Customer(String name, String email) {
    }

    @Schema(description = "The address the order shipped to, as it was when the order was placed.")
    public record ShippingAddress(String line1, String city, String state, String postalCode) {
    }

    public record OrderDetail(
            @Schema(example = "1042") String reference,
            OrderStatus status,
            OffsetDateTime placedAt,
            @Schema(description = "Null until the order ships.") LocalDate shippedOn,
            @Schema(description = "Null until the order is delivered.") LocalDate deliveredOn,
            @Schema(description = "The carrier's estimate while the order is in transit; null otherwise.") LocalDate estimatedDelivery,
            Customer customer,
            ShippingAddress shipTo,
            Money subtotal,
            Money shippingCharge,
            Money total) {
    }

    @Schema(description = "One purchased line of an order.")
    public record OrderItem(
            @Schema(description = "Identifies this line in eligibility checks and quotes.", example = "1042-1") String itemId,
            @Schema(example = "TRB") String productId,
            String productName,
            @Schema(example = "TRB-M-BLU") String sku,
            @Schema(description = "Null for products sold in one size.") String size,
            @Schema(description = "Null for products sold in one color.") String color,
            int quantity,
            @Schema(description = "The price paid per unit.") Money unitPrice,
            Money lineTotal,
            int returnedQuantity,
            int exchangedQuantity) {
    }

    public record OrderItems(String orderReference, List<OrderItem> items) {
    }

    public record VariantView(
            @Schema(example = "TRB-L-BLU") String sku,
            String size,
            String color,
            @Schema(description = "Today's price.") Money price) {
    }

    public record ProductVariants(String productId, String productName, String category, List<VariantView> variants) {
    }

    public record PolicySummary(
            @Schema(example = "STANDARD") String returnCategory,
            String description) {
    }

    public record EligibilityView(
            String orderReference,
            String itemId,
            boolean eligible,
            @Schema(description = "Why the item isn't eligible: order_cancelled, not_delivered, not_returnable, not_exchangeable, window_closed, or nothing_left. Null when eligible.")
            String reasonCode,
            @Schema(description = "A sentence explaining the reason code. Null when eligible.") String reason,
            @Schema(description = "The last day of the window, inclusive. Null when the item has no window yet.") LocalDate windowEndsOn,
            @Schema(description = "How many units can be returned or exchanged now.") int maxQuantity,
            PolicySummary policy) {
    }

    public record WarehouseStock(String warehouseId, String name, String city, String state, int available) {
    }

    public record InventoryView(@Schema(example = "TRB-L-BLU") String sku, int totalAvailable,
            List<WarehouseStock> warehouses) {
    }

    public record DeliveryOptionView(
            DeliveryMethod method,
            int businessDays,
            @Schema(description = "Arrival date for a shipment leaving on shipDate.") LocalDate estimatedArrival,
            Money charge) {
    }

    public record DeliveryOptions(String warehouseId, String postalCode, LocalDate shipDate,
            List<DeliveryOptionView> options) {
    }

    @Schema(description = "A proposed exchange of an order item for another variant of the same product.")
    public record ExchangeQuoteRequest(
            @Schema(example = "1042", requiredMode = RequiredMode.REQUIRED) @NotBlank String orderReference,
            @Schema(example = "1042-1", requiredMode = RequiredMode.REQUIRED) @NotBlank String itemId,
            @Schema(description = "The variant to send instead.", example = "TRB-L-BLU", requiredMode = RequiredMode.REQUIRED) @NotBlank String replacementSku,
            @Schema(example = "1", requiredMode = RequiredMode.REQUIRED) @NotNull @Min(1) Integer quantity,
            @Schema(description = "The warehouse to ship the replacement from.", example = "WH-CMH", requiredMode = RequiredMode.REQUIRED) @NotBlank String warehouseId,
            @Schema(requiredMode = RequiredMode.REQUIRED) @NotNull DeliveryMethod deliveryMethod) {
    }

    @Schema(description = "A draft exchange quote. It expires at expiresAt and commits nothing until it's submitted.")
    public record ExchangeQuote(
            @Schema(example = "EQ-5001") String quoteId,
            String status,
            String orderReference,
            String itemId,
            @Schema(description = "The purchased variant.") String sku,
            String replacementSku,
            int quantity,
            String warehouseId,
            DeliveryMethod deliveryMethod,
            LocalDate estimatedArrival,
            @Schema(description = "Replacement price minus the price paid, times the quantity. Negative when the replacement costs less.") Money priceDifference,
            Money shippingCharge,
            @Schema(description = "What the customer pays: the price difference plus shipping.") Money additionalCost,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt) {
    }

    @Schema(description = "A proposed return of some or all units of an order item.")
    public record ReturnQuoteRequest(
            @Schema(example = "1019", requiredMode = RequiredMode.REQUIRED) @NotBlank String orderReference,
            @Schema(example = "1019-1", requiredMode = RequiredMode.REQUIRED) @NotBlank String itemId,
            @Schema(example = "1", requiredMode = RequiredMode.REQUIRED) @NotNull @Min(1) Integer quantity) {
    }

    @Schema(description = "A draft return quote. It expires at expiresAt and commits nothing until it's submitted.")
    public record ReturnQuote(
            @Schema(example = "RQ-6001") String quoteId,
            String status,
            String orderReference,
            String itemId,
            String sku,
            int quantity,
            @Schema(description = "The price paid for the returned units.") Money itemRefund,
            Money returnShippingFee,
            Money restockingFee,
            @Schema(description = "What the customer gets back: the item refund minus the fees.") Money refundAmount,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt) {
    }

    public record SubmitQuoteRequest(
            @Schema(example = "EQ-5001", requiredMode = RequiredMode.REQUIRED) @NotBlank String quoteId) {
    }

    @Schema(description = "A committed exchange.")
    public record ExchangeView(
            @Schema(example = "EX-7001") String exchangeId,
            String quoteId,
            String status,
            String orderReference,
            String itemId,
            String sku,
            String replacementSku,
            int quantity,
            String warehouseId,
            DeliveryMethod deliveryMethod,
            LocalDate estimatedArrival,
            @Schema(description = "The amount charged to the customer.") Money additionalCost,
            OffsetDateTime submittedAt) {
    }

    @Schema(description = "A committed return.")
    public record ReturnView(
            @Schema(example = "RT-8001") String returnId,
            String quoteId,
            String status,
            String orderReference,
            String itemId,
            String sku,
            int quantity,
            @Schema(description = "The amount refunded once the item is received.") Money refundAmount,
            OffsetDateTime submittedAt) {
    }
}
