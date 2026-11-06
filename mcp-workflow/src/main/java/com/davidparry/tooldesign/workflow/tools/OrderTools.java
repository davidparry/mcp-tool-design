package com.davidparry.tooldesign.workflow.tools;

import java.util.List;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.davidparry.tooldesign.workflow.WorkflowProperties;
import com.davidparry.tooldesign.workflow.api.ApiProblem;
import com.davidparry.tooldesign.workflow.api.ApiTypes.OrderDetail;
import com.davidparry.tooldesign.workflow.api.ApiTypes.OrderItem;
import com.davidparry.tooldesign.workflow.api.ApiTypes.OrderSearchPage;
import com.davidparry.tooldesign.workflow.api.OrderApi;
import com.davidparry.tooldesign.workflow.tools.Results.Eligibility;
import com.davidparry.tooldesign.workflow.tools.Results.ItemContext;
import com.davidparry.tooldesign.workflow.tools.Results.OrderBrief;
import com.davidparry.tooldesign.workflow.tools.Results.OrderContext;
import com.davidparry.tooldesign.workflow.tools.Results.OrderSearch;
import com.davidparry.tooldesign.workflow.tools.Results.ShipTo;

@Component
public class OrderTools {

    private final OrderApi api;

    private final WorkflowProperties properties;

    public OrderTools(OrderApi api, WorkflowProperties properties) {
        this.api = api;
        this.properties = properties;
    }

    @McpTool(name = "find_orders", title = "Find orders", generateOutputSchema = true,
            description = "Find a customer's orders by order reference, customer name, or email. Returns up to "
                    + "`limit` orders, newest first, each with its status, key dates, items, and total, and says "
                    + "whether more matched. Read-only.",
            annotations = @McpTool.McpAnnotations(title = "Find orders", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public OrderSearch findOrders(
            @McpToolParam(description = "An order reference such as 1042, or part of a customer's name or email") String query,
            @McpToolParam(description = "Only orders with this status: PLACED, SHIPPED, DELIVERED, or CANCELLED",
                    required = false) String status,
            @McpToolParam(description = "How many orders to return, 1 to 10. Defaults to 5.",
                    required = false) Integer limit) {
        int size = limit == null ? this.properties.orderLimit() : Math.max(1, Math.min(limit, 10));
        OrderSearchPage page = this.api.searchOrders(query, status == null ? null : status.trim().toUpperCase(),
                size);
        List<OrderBrief> orders = page.items().stream().map(summary -> brief(summary.reference())).toList();
        boolean more = page.totalItems() > orders.size();
        String message = orders.isEmpty() ? "No orders match '" + query + "'."
                : page.totalItems() + " order" + (page.totalItems() == 1 ? "" : "s") + " match '" + query + "'"
                        + (more ? "; showing the newest " + orders.size() + "." : ".");
        return new OrderSearch(orders.isEmpty() ? "no_orders_found" : "orders_found", message, orders,
                page.totalItems(), more, size);
    }

    @McpTool(name = "get_order_context", title = "Get order context", generateOutputSchema = true,
            description = "Get what's needed to answer a customer about one order: its status and dates (placed, "
                    + "shipped, delivered or expected), where it ships, and each item with its size, color, "
                    + "quantity, and price paid, plus whether the item can still be returned or exchanged, with the "
                    + "last day to do it or the reason it can't. Read-only.",
            annotations = @McpTool.McpAnnotations(title = "Get order context", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public OrderContext getOrderContext(
            @McpToolParam(description = "The order reference, such as 1042") String orderReference) {
        OrderDetail order;
        try {
            order = this.api.order(orderReference);
        }
        catch (ApiProblem problem) {
            if (problem.status() == 404) {
                return new OrderContext("order_not_found", "There's no order " + orderReference + ".",
                        orderReference, null, null, null, null, null, null, null, null, null);
            }
            throw problem;
        }
        List<ItemContext> items = this.api.items(orderReference).items().stream()
                .map(item -> context(orderReference, item)).toList();
        String message = "Order " + order.reference() + " is " + order.status().toLowerCase()
                + switch (order.status()) {
                    case "DELIVERED" -> "; it was delivered on " + order.deliveredOn() + ".";
                    case "SHIPPED" -> "; it shipped on " + order.shippedOn() + " and should arrive by "
                            + order.estimatedDelivery() + ".";
                    case "PLACED" -> " and hasn't shipped yet.";
                    default -> ".";
                };
        return new OrderContext("found", message, order.reference(), order.status(), order.customer().name(),
                order.placedAt().toLocalDate(), order.shippedOn(), order.deliveredOn(), order.estimatedDelivery(),
                new ShipTo(order.shipTo().city(), order.shipTo().state(), order.shipTo().postalCode()), items,
                order.total());
    }

    private OrderBrief brief(String reference) {
        OrderDetail order = this.api.order(reference);
        List<String> items = this.api.items(reference).items().stream().map(OrderLines::describe).toList();
        return new OrderBrief(order.reference(), order.customer().name(), order.status(),
                order.placedAt().toLocalDate(), order.shippedOn(), order.deliveredOn(), order.estimatedDelivery(),
                items, order.total());
    }

    private ItemContext context(String reference, OrderItem item) {
        return new ItemContext(item.itemId(), item.productName(), item.sku(), item.size(), item.color(),
                item.quantity(), item.unitPrice(), item.returnedQuantity(), item.exchangedQuantity(),
                eligibility(this.api.returnEligibility(reference, item.itemId())),
                eligibility(this.api.exchangeEligibility(reference, item.itemId())));
    }

    private static Eligibility eligibility(com.davidparry.tooldesign.workflow.api.ApiTypes.Eligibility e) {
        return e.eligible() ? new Eligibility(true, e.windowEndsOn(), null, null, e.maxQuantity())
                : new Eligibility(false, e.windowEndsOn(), e.reasonCode(), e.reason(), null);
    }
}
