package com.davidparry.tooldesign.app.service;

import org.springframework.stereotype.Service;

import com.davidparry.tooldesign.app.domain.Catalog.Product;
import com.davidparry.tooldesign.app.domain.Catalog.Variant;
import com.davidparry.tooldesign.app.domain.Orders.Order;
import com.davidparry.tooldesign.app.domain.Orders.OrderLine;
import com.davidparry.tooldesign.app.store.CatalogStore;
import com.davidparry.tooldesign.app.store.OrderStore;

/** Resolves references to orders, lines, products, and variants, or fails with the matching not-found code. */
@Service
public class OrderLookup {

    private final OrderStore orders;

    private final CatalogStore catalog;

    public OrderLookup(OrderStore orders, CatalogStore catalog) {
        this.orders = orders;
        this.catalog = catalog;
    }

    public Order order(String reference) {
        return this.orders.order(reference).orElseThrow(
                () -> BusinessException.notFound("order_not_found", "There is no order with reference " + reference));
    }

    public OrderLine line(String reference, String itemId) {
        order(reference);
        return this.orders.line(reference, itemId).orElseThrow(() -> BusinessException.notFound("item_not_found",
                "Order " + reference + " has no item " + itemId));
    }

    public Variant variant(String sku) {
        return this.catalog.variant(sku)
                .orElseThrow(() -> BusinessException.notFound("variant_not_found", "There is no variant " + sku));
    }

    public Product product(String productId) {
        return this.catalog.product(productId).orElseThrow(
                () -> BusinessException.notFound("product_not_found", "There is no product " + productId));
    }

    public Product productOf(OrderLine line) {
        return product(variant(line.sku()).productId());
    }
}
