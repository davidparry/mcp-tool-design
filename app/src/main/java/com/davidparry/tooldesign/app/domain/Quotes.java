package com.davidparry.tooldesign.app.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.davidparry.tooldesign.app.domain.Catalog.DeliveryMethod;

/** Draft quotes and the actions committed from them. */
public final class Quotes {

    private Quotes() {
    }

    public enum QuoteType {
        EXCHANGE, RETURN
    }

    public enum QuoteStatus {
        OPEN, SUBMITTED
    }

    /**
     * A priced, validated proposal that expires. Exchange fields are null on a return quote and return fields are
     * null on an exchange quote. {@code total} is the customer's additional cost for an exchange and the refund for
     * a return.
     */
    public record Quote(String quoteId, QuoteType type, String orderReference, String itemId, String sku,
            String replacementSku, int quantity, String warehouseId, DeliveryMethod deliveryMethod,
            LocalDate estimatedArrival, Money priceDifference, Money shippingCharge, Money itemRefund,
            Money returnShippingFee, Money restockingFee, Money total, OffsetDateTime createdAt,
            OffsetDateTime expiresAt, QuoteStatus status) {

        public boolean expiredAt(OffsetDateTime now) {
            return now.isAfter(this.expiresAt);
        }
    }

    public enum ActionStatus {
        CONFIRMED
    }

    /** A committed exchange or return. There is at most one per quote. */
    public record CommittedAction(String actionId, QuoteType type, String quoteId, String orderReference,
            String itemId, String sku, String replacementSku, int quantity, String warehouseId,
            DeliveryMethod deliveryMethod, LocalDate estimatedArrival, Money amount, ActionStatus status,
            OffsetDateTime submittedAt) {
    }
}
