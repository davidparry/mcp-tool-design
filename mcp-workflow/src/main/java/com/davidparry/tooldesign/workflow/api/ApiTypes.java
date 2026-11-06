package com.davidparry.tooldesign.workflow.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The order API's shapes, written from its OpenAPI document. This server shares no code with the app. */
public final class ApiTypes {

    private ApiTypes() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Money(String amount, String currency) {

        public BigDecimal value() {
            return new BigDecimal(this.amount);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderSummary(String reference, String customerName, String customerEmail, String status,
            OffsetDateTime placedAt, int itemCount, Money total) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderSearchPage(List<OrderSummary> items, int page, int size, long totalItems, int totalPages) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Customer(String name, String email) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ShippingAddress(String line1, String city, String state, String postalCode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderDetail(String reference, String status, OffsetDateTime placedAt, LocalDate shippedOn,
            LocalDate deliveredOn, LocalDate estimatedDelivery, Customer customer, ShippingAddress shipTo,
            Money subtotal, Money shippingCharge, Money total) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderItem(String itemId, String productId, String productName, String sku, String size,
            String color, int quantity, Money unitPrice, Money lineTotal, int returnedQuantity,
            int exchangedQuantity) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderItems(String orderReference, List<OrderItem> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Variant(String sku, String size, String color, Money price) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductVariants(String productId, String productName, String category, List<Variant> variants) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Policy(String returnCategory, String description) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Eligibility(String orderReference, String itemId, boolean eligible, String reasonCode,
            String reason, LocalDate windowEndsOn, int maxQuantity, Policy policy) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WarehouseStock(String warehouseId, String name, String city, String state, int available) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Inventory(String sku, int totalAvailable, List<WarehouseStock> warehouses) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeliveryOption(String method, int businessDays, LocalDate estimatedArrival, Money charge) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeliveryOptions(String warehouseId, String postalCode, LocalDate shipDate,
            List<DeliveryOption> options) {
    }

    public record ExchangeQuoteRequest(String orderReference, String itemId, String replacementSku, int quantity,
            String warehouseId, String deliveryMethod) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExchangeQuote(String quoteId, String status, String orderReference, String itemId, String sku,
            String replacementSku, int quantity, String warehouseId, String deliveryMethod,
            LocalDate estimatedArrival, Money priceDifference, Money shippingCharge, Money additionalCost,
            OffsetDateTime createdAt, OffsetDateTime expiresAt) {
    }

    public record ReturnQuoteRequest(String orderReference, String itemId, int quantity) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReturnQuote(String quoteId, String status, String orderReference, String itemId, String sku,
            int quantity, Money itemRefund, Money returnShippingFee, Money restockingFee, Money refundAmount,
            OffsetDateTime createdAt, OffsetDateTime expiresAt) {
    }

    public record SubmitQuoteRequest(String quoteId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExchangeView(String exchangeId, String quoteId, String status, String orderReference,
            String itemId, String sku, String replacementSku, int quantity, String warehouseId,
            String deliveryMethod, LocalDate estimatedArrival, Money additionalCost, OffsetDateTime submittedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReturnView(String returnId, String quoteId, String status, String orderReference, String itemId,
            String sku, int quantity, Money refundAmount, OffsetDateTime submittedAt) {
    }
}
