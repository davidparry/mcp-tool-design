package com.davidparry.tooldesign.app.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.davidparry.tooldesign.app.domain.BusinessClock;
import com.davidparry.tooldesign.app.domain.Catalog.DeliveryMethod;
import com.davidparry.tooldesign.app.domain.Catalog.ReturnPolicy;
import com.davidparry.tooldesign.app.domain.Catalog.Variant;
import com.davidparry.tooldesign.app.domain.Money;
import com.davidparry.tooldesign.app.domain.Orders.Order;
import com.davidparry.tooldesign.app.domain.Orders.OrderLine;
import com.davidparry.tooldesign.app.domain.Quotes.Quote;
import com.davidparry.tooldesign.app.domain.Quotes.QuoteStatus;
import com.davidparry.tooldesign.app.domain.Quotes.QuoteType;
import com.davidparry.tooldesign.app.service.DeliveryEstimator.DeliveryOption;
import com.davidparry.tooldesign.app.service.EligibilityService.Action;
import com.davidparry.tooldesign.app.service.EligibilityService.Eligibility;
import com.davidparry.tooldesign.app.store.IdSequence;
import com.davidparry.tooldesign.app.store.InventoryStore;
import com.davidparry.tooldesign.app.store.QuoteStore;

/**
 * Validates and prices a proposed exchange or return, and stores it as a draft quote that expires. A quote changes
 * nothing else: no stock is reserved, nothing is charged, and nothing is committed until it's submitted.
 */
@Service
public class QuoteService {

    public record ExchangeRequest(String orderReference, String itemId, String replacementSku, int quantity,
            String warehouseId, DeliveryMethod deliveryMethod) {
    }

    public record ReturnRequest(String orderReference, String itemId, int quantity) {
    }

    private final OrderLookup lookup;

    private final EligibilityService eligibility;

    private final DeliveryEstimator delivery;

    private final InventoryStore inventory;

    private final QuoteStore quotes;

    private final IdSequence ids;

    private final BusinessClock clock;

    private final Duration lifetime;

    public QuoteService(OrderLookup lookup, EligibilityService eligibility, DeliveryEstimator delivery,
            InventoryStore inventory, QuoteStore quotes, IdSequence ids, BusinessClock clock,
            @Value("${app.quotes.lifetime:30m}") Duration lifetime) {
        this.lookup = lookup;
        this.eligibility = eligibility;
        this.delivery = delivery;
        this.inventory = inventory;
        this.quotes = quotes;
        this.ids = ids;
        this.clock = clock;
        this.lifetime = lifetime;
    }

    @Transactional
    public Quote exchange(ExchangeRequest request) {
        Order order = this.lookup.order(request.orderReference());
        OrderLine line = this.lookup.line(request.orderReference(), request.itemId());
        Variant original = this.lookup.variant(line.sku());
        Variant replacement = this.lookup.variant(request.replacementSku());
        if (request.deliveryMethod() == null) {
            throw BusinessException.invalid("deliveryMethod is required: GROUND or EXPRESS");
        }
        if (!replacement.productId().equals(original.productId())) {
            throw BusinessException.invalid("An exchange has to be another size or color of the same product. "
                    + request.replacementSku() + " isn't a variant of " + original.sku() + "'s product");
        }
        if (replacement.sku().equals(original.sku())) {
            throw BusinessException.invalid("The replacement is the same variant as the purchased item");
        }
        Eligibility verdict = requireEligible(Action.EXCHANGE, order, line);
        requireQuantity(request.quantity(), verdict);
        int available = this.inventory.available(replacement.sku(), request.warehouseId());
        DeliveryOption option = this.delivery.option(request.warehouseId(), order.shipTo().postalCode(),
                request.deliveryMethod());
        if (available < request.quantity()) {
            throw new BusinessException(HttpStatus.CONFLICT, "stock_unavailable",
                    "Warehouse " + request.warehouseId() + " has " + available + " of " + replacement.sku()
                            + " available, and the exchange needs " + request.quantity(),
                    Map.of("sku", replacement.sku(), "warehouseId", request.warehouseId(), "available", available));
        }

        Money priceDifference = replacement.price().minus(line.unitPrice()).times(request.quantity());
        Money total = priceDifference.plus(option.charge());
        OffsetDateTime now = this.clock.now();
        Quote quote = new Quote(this.ids.exchangeQuote(), QuoteType.EXCHANGE, order.reference(), line.itemId(),
                line.sku(), replacement.sku(), request.quantity(), request.warehouseId(), request.deliveryMethod(),
                option.estimatedArrival(), priceDifference, option.charge(), null, null, null, total, now,
                now.plus(this.lifetime), QuoteStatus.OPEN);
        this.quotes.insert(quote);
        return quote;
    }

    @Transactional
    public Quote returnQuote(ReturnRequest request) {
        Order order = this.lookup.order(request.orderReference());
        OrderLine line = this.lookup.line(request.orderReference(), request.itemId());
        Eligibility verdict = requireEligible(Action.RETURN, order, line);
        requireQuantity(request.quantity(), verdict);

        ReturnPolicy policy = verdict.policy();
        Money itemRefund = line.unitPrice().times(request.quantity());
        Money restockingFee = itemRefund.percent(policy.restockingFeePercent());
        Money returnShippingFee = policy.returnShippingFee();
        Money refund = itemRefund.minus(restockingFee).minus(returnShippingFee);
        OffsetDateTime now = this.clock.now();
        Quote quote = new Quote(this.ids.returnQuote(), QuoteType.RETURN, order.reference(), line.itemId(),
                line.sku(), null, request.quantity(), null, null, null, null, null, itemRefund, returnShippingFee,
                restockingFee, refund, now, now.plus(this.lifetime), QuoteStatus.OPEN);
        this.quotes.insert(quote);
        return quote;
    }

    Eligibility requireEligible(Action action, Order order, OrderLine line) {
        Eligibility verdict = this.eligibility.check(action, order, line);
        if (!verdict.eligible()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reasonCode", verdict.reasonCode());
            if (verdict.windowEndsOn() != null) {
                details.put("windowEndsOn", verdict.windowEndsOn().toString());
            }
            throw new BusinessException(HttpStatus.CONFLICT, "not_eligible", verdict.reason(), details);
        }
        return verdict;
    }

    private static void requireQuantity(int quantity, Eligibility verdict) {
        if (quantity < 1 || quantity > verdict.maxQuantity()) {
            throw BusinessException.invalid(
                    "quantity must be between 1 and " + verdict.maxQuantity() + " for this item, not " + quantity);
        }
    }
}
