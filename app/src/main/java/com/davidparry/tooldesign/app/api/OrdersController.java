package com.davidparry.tooldesign.app.api;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.davidparry.tooldesign.app.api.ApiModels.Customer;
import com.davidparry.tooldesign.app.api.ApiModels.EligibilityView;
import com.davidparry.tooldesign.app.api.ApiModels.OrderDetail;
import com.davidparry.tooldesign.app.api.ApiModels.OrderItem;
import com.davidparry.tooldesign.app.api.ApiModels.OrderItems;
import com.davidparry.tooldesign.app.api.ApiModels.OrderSearchPage;
import com.davidparry.tooldesign.app.api.ApiModels.OrderSummary;
import com.davidparry.tooldesign.app.api.ApiModels.PolicySummary;
import com.davidparry.tooldesign.app.api.ApiModels.ShippingAddress;
import com.davidparry.tooldesign.app.domain.Catalog.Product;
import com.davidparry.tooldesign.app.domain.Catalog.Variant;
import com.davidparry.tooldesign.app.domain.Money;
import com.davidparry.tooldesign.app.domain.Orders.Order;
import com.davidparry.tooldesign.app.domain.Orders.OrderLine;
import com.davidparry.tooldesign.app.domain.Orders.OrderStatus;
import com.davidparry.tooldesign.app.service.BusinessException;
import com.davidparry.tooldesign.app.service.EligibilityService;
import com.davidparry.tooldesign.app.service.EligibilityService.Action;
import com.davidparry.tooldesign.app.service.EligibilityService.Eligibility;
import com.davidparry.tooldesign.app.service.OrderLookup;
import com.davidparry.tooldesign.app.store.OrderStore;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "Order lookup, purchased items, and return and exchange eligibility")
public class OrdersController {

    private static final int MAX_PAGE_SIZE = 50;

    private final OrderStore orders;

    private final OrderLookup lookup;

    private final EligibilityService eligibility;

    public OrdersController(OrderStore orders, OrderLookup lookup, EligibilityService eligibility) {
        this.orders = orders;
        this.lookup = lookup;
        this.eligibility = eligibility;
    }

    @GetMapping
    @Operation(operationId = "search_orders", summary = "Search orders",
            description = "Finds orders by order reference, or by part of the customer's name or email address, newest first. Results are paged. Read-only.")
    public OrderSearchPage search(
            @Parameter(description = "An order reference such as 1042, or part of a customer's name or email address. Leave it out to list every order.")
            @RequestParam(required = false) String query,
            @Parameter(description = "Only return orders in this status.") @RequestParam(required = false) OrderStatus status,
            @Parameter(description = "Zero-based page number.") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Orders per page, from 1 to 50.") @RequestParam(defaultValue = "10") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw BusinessException.invalid("page must be 0 or more and size between 1 and " + MAX_PAGE_SIZE);
        }
        OrderStore.Page result = this.orders.search(query, status, page, size);
        List<OrderSummary> items = result.orders().stream().map(this::summary).toList();
        int totalPages = (int) ((result.totalItems() + size - 1) / size);
        return new OrderSearchPage(items, page, size, result.totalItems(), totalPages);
    }

    @GetMapping("/{reference}")
    @Operation(operationId = "get_order", summary = "Get an order",
            description = "Returns an order's status, dates, customer, shipping address, and totals. The purchased items are at /api/orders/{reference}/items. Read-only.")
    public OrderDetail order(@Parameter(description = "The order reference, such as 1042.") @PathVariable String reference) {
        Order order = this.lookup.order(reference);
        Money subtotal = subtotal(this.orders.lines(reference));
        return new OrderDetail(order.reference(), order.status(), order.placedAt(), order.shippedOn(),
                order.deliveredOn(), order.estimatedDelivery(), new Customer(order.customerName(), order.customerEmail()),
                new ShippingAddress(order.shipTo().line1(), order.shipTo().city(), order.shipTo().state(),
                        order.shipTo().postalCode()),
                subtotal, order.shippingCharge(), subtotal.plus(order.shippingCharge()));
    }

    @GetMapping("/{reference}/items")
    @Operation(operationId = "get_order_items", summary = "List an order's items",
            description = "Returns each purchased line with its item ID, product, variant SKU, size, color, quantity, and the unit price paid, plus how many units were already returned or exchanged. Read-only.")
    public OrderItems items(@Parameter(description = "The order reference, such as 1042.") @PathVariable String reference) {
        this.lookup.order(reference);
        List<OrderItem> items = this.orders.lines(reference).stream().map(this::item).toList();
        return new OrderItems(reference, items);
    }

    @GetMapping("/{reference}/items/{itemId}/exchange-eligibility")
    @Operation(operationId = "check_exchange_eligibility", summary = "Check exchange eligibility",
            description = "Says whether an order item can be exchanged today. The order has to be delivered, the item's category has to allow exchanges, the exchange window has to be open, and units have to remain. Returns the reason code when it can't be exchanged, the last day of the window, and how many units can be exchanged. Read-only.")
    public EligibilityView exchangeEligibility(
            @Parameter(description = "The order reference, such as 1042.") @PathVariable String reference,
            @Parameter(description = "The item ID from the order's items, such as 1042-1.") @PathVariable String itemId) {
        return view(reference, itemId, this.eligibility.check(Action.EXCHANGE, reference, itemId));
    }

    @GetMapping("/{reference}/items/{itemId}/return-eligibility")
    @Operation(operationId = "check_return_eligibility", summary = "Check return eligibility",
            description = "Says whether an order item can be returned today. The order has to be delivered, the item's category has to allow returns, the return window has to be open, and units have to remain. Returns the reason code when it can't be returned, the last day of the window, and how many units can be returned. Refund amounts and fees come from a return quote. Read-only.")
    public EligibilityView returnEligibility(
            @Parameter(description = "The order reference, such as 1042.") @PathVariable String reference,
            @Parameter(description = "The item ID from the order's items, such as 1042-1.") @PathVariable String itemId) {
        return view(reference, itemId, this.eligibility.check(Action.RETURN, reference, itemId));
    }

    private OrderSummary summary(Order order) {
        List<OrderLine> lines = this.orders.lines(order.reference());
        int units = lines.stream().mapToInt(OrderLine::quantity).sum();
        return new OrderSummary(order.reference(), order.customerName(), order.customerEmail(), order.status(),
                order.placedAt(), units, subtotal(lines).plus(order.shippingCharge()));
    }

    private OrderItem item(OrderLine line) {
        Variant variant = this.lookup.variant(line.sku());
        Product product = this.lookup.product(variant.productId());
        return new OrderItem(line.itemId(), product.id(), product.name(), line.sku(), variant.size(), variant.color(),
                line.quantity(), line.unitPrice(), line.lineTotal(), line.returnedQuantity(), line.exchangedQuantity());
    }

    private static EligibilityView view(String reference, String itemId, Eligibility verdict) {
        return new EligibilityView(reference, itemId, verdict.eligible(), verdict.reasonCode(), verdict.reason(),
                verdict.windowEndsOn(), verdict.maxQuantity(),
                new PolicySummary(verdict.policy().returnCategory().name(), verdict.policy().description()));
    }

    private static Money subtotal(List<OrderLine> lines) {
        return lines.stream().map(OrderLine::lineTotal).reduce(Money.ZERO, Money::plus);
    }
}
