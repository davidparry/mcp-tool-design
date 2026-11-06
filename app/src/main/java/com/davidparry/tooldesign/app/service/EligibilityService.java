package com.davidparry.tooldesign.app.service;

import java.time.LocalDate;

import org.springframework.stereotype.Service;

import com.davidparry.tooldesign.app.domain.BusinessClock;
import com.davidparry.tooldesign.app.domain.Catalog.Product;
import com.davidparry.tooldesign.app.domain.Catalog.ReturnPolicy;
import com.davidparry.tooldesign.app.domain.Orders.Order;
import com.davidparry.tooldesign.app.domain.Orders.OrderLine;
import com.davidparry.tooldesign.app.domain.Orders.OrderStatus;
import com.davidparry.tooldesign.app.store.CatalogStore;

/**
 * Decides whether an order line can be returned or exchanged today: the order's state, the item's return
 * category, the window since delivery, and the quantity not already returned or exchanged.
 */
@Service
public class EligibilityService {

    public enum Action {
        RETURN, EXCHANGE
    }

    /**
     * {@code reasonCode} and {@code reason} are null when eligible. {@code windowEndsOn} is the last day of the
     * window, inclusive, and is null when the item has no window yet or never had one.
     */
    public record Eligibility(boolean eligible, String reasonCode, String reason, LocalDate windowEndsOn,
            int maxQuantity, ReturnPolicy policy) {
    }

    private final OrderLookup lookup;

    private final CatalogStore catalog;

    private final BusinessClock clock;

    public EligibilityService(OrderLookup lookup, CatalogStore catalog, BusinessClock clock) {
        this.lookup = lookup;
        this.catalog = catalog;
        this.clock = clock;
    }

    public Eligibility check(Action action, String orderReference, String itemId) {
        Order order = this.lookup.order(orderReference);
        return check(action, order, this.lookup.line(orderReference, itemId));
    }

    public Eligibility check(Action action, Order order, OrderLine line) {
        Product product = this.lookup.productOf(line);
        ReturnPolicy policy = this.catalog.policy(product.returnCategory());
        boolean allowed = action == Action.RETURN ? policy.returnable() : policy.exchangeable();
        int windowDays = action == Action.RETURN ? policy.returnWindowDays() : policy.exchangeWindowDays();
        String verb = action == Action.RETURN ? "returned" : "exchanged";

        if (order.status() == OrderStatus.CANCELLED) {
            return refused("order_cancelled", "Order " + order.reference() + " was cancelled, so nothing in it can be "
                    + verb + ".", null, policy);
        }
        if (order.status() != OrderStatus.DELIVERED) {
            String when = order.estimatedDelivery() == null ? "" : "; it's estimated to arrive on " + order.estimatedDelivery();
            return refused("not_delivered", "Order " + order.reference() + " hasn't been delivered yet" + when
                    + ". Items can be " + verb + " once they arrive.", null, policy);
        }
        if (!allowed) {
            return refused(action == Action.RETURN ? "not_returnable" : "not_exchangeable",
                    product.name() + " is in the " + policy.returnCategory() + " category, which can't be " + verb
                            + ". " + policy.description(),
                    null, policy);
        }
        LocalDate windowEndsOn = order.deliveredOn().plusDays(windowDays);
        LocalDate today = LocalDate.now(this.clock);
        if (today.isAfter(windowEndsOn)) {
            return refused("window_closed", "The " + windowDays + "-day " + action.name().toLowerCase() + " window for "
                    + product.name() + " ended on " + windowEndsOn + ".", windowEndsOn, policy);
        }
        if (line.openQuantity() < 1) {
            return refused("nothing_left", "Every unit of item " + line.itemId() + " has already been returned or exchanged.",
                    windowEndsOn, policy);
        }
        return new Eligibility(true, null, null, windowEndsOn, line.openQuantity(), policy);
    }

    private static Eligibility refused(String code, String reason, LocalDate windowEndsOn, ReturnPolicy policy) {
        return new Eligibility(false, code, reason, windowEndsOn, 0, policy);
    }
}
