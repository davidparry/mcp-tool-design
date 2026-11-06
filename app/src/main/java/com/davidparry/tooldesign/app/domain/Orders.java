package com.davidparry.tooldesign.app.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Customer orders and their lines. */
public final class Orders {

    private Orders() {
    }

    public enum OrderStatus {
        PLACED, SHIPPED, DELIVERED, CANCELLED
    }

    public record Address(String line1, String city, String state, String postalCode) {
    }

    /** {@code shippedOn}, {@code deliveredOn}, and {@code estimatedDelivery} are null until they apply. */
    public record Order(String reference, String customerName, String customerEmail, Address shipTo,
            OrderStatus status, OffsetDateTime placedAt, LocalDate shippedOn, LocalDate deliveredOn,
            LocalDate estimatedDelivery, Money shippingCharge) {
    }

    /** One purchased line. The item ID is the order reference and the line number, such as {@code 1042-1}. */
    public record OrderLine(String itemId, String orderReference, int lineNo, String sku, int quantity,
            Money unitPrice, int returnedQuantity, int exchangedQuantity) {

        public int openQuantity() {
            return this.quantity - this.returnedQuantity - this.exchangedQuantity;
        }

        public Money lineTotal() {
            return this.unitPrice.times(this.quantity);
        }
    }
}
