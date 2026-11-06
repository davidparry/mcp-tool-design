package com.davidparry.tooldesign.app.fixtures;

import java.util.List;
import java.util.Map;

/**
 * The JSON shapes of {@code fixtures/seed}. Amounts and dates stay strings here and are parsed explicitly by the
 * loader, so nothing depends on how the JSON mapper coerces them.
 */
public final class Seed {

    private Seed() {
    }

    public record Product(String id, String name, String category, String returnCategory, List<Variant> variants) {
    }

    public record Variant(String sku, String size, String color, String price) {
    }

    public record Warehouse(String id, String name, String city, String state, String region) {
    }

    public record Delivery(Map<String, String> zones, List<Rate> rates) {
    }

    public record Rate(String warehouseId, String region, String method, int businessDays, String charge) {
    }

    public record Policy(String returnCategory, String description, boolean returnable, boolean exchangeable,
            int returnWindowDays, int exchangeWindowDays, String returnShippingFee, int restockingFeePercent) {
    }

    /** {@code available} maps each SKU to its stock in the order of {@code warehouses}. */
    public record Inventory(List<String> warehouses, Map<String, List<Integer>> available) {
    }

    public record Order(String reference, Customer customer, Address shipTo, String status, String placedAt,
            String shippedOn, String deliveredOn, String estimatedDelivery, List<Line> lines) {
    }

    public record Customer(String name, String email) {
    }

    public record Address(String line1, String city, String state, String postalCode) {
    }

    public record Line(String sku, int quantity, String unitPrice) {
    }
}
