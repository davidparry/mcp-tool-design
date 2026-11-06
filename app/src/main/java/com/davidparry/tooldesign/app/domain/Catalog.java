package com.davidparry.tooldesign.app.domain;

/** The product catalog, warehouses, delivery rates, and return policies. */
public final class Catalog {

    private Catalog() {
    }

    public enum ReturnCategory {
        STANDARD, BULKY, ELECTRONICS, CONSUMABLE
    }

    public enum Region {
        NORTHEAST, SOUTHEAST, MIDWEST, SOUTH, WEST
    }

    public enum DeliveryMethod {
        GROUND, EXPRESS
    }

    public record Product(String id, String name, String category, ReturnCategory returnCategory) {
    }

    /** {@code size} and {@code color} are null for products sold in one size or one color. */
    public record Variant(String sku, String productId, String size, String color, Money price) {
    }

    public record Warehouse(String id, String name, String city, String state, Region region) {
    }

    public record DeliveryRate(String warehouseId, Region region, DeliveryMethod method, int businessDays,
            Money charge) {
    }

    public record ReturnPolicy(ReturnCategory returnCategory, String description, boolean returnable,
            boolean exchangeable, int returnWindowDays, int exchangeWindowDays, Money returnShippingFee,
            int restockingFeePercent) {
    }
}
