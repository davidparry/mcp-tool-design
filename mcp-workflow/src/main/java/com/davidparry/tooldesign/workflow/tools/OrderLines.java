package com.davidparry.tooldesign.workflow.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.davidparry.tooldesign.workflow.api.ApiTypes.OrderItem;
import com.davidparry.tooldesign.workflow.api.ApiTypes.ProductVariants;
import com.davidparry.tooldesign.workflow.api.OrderApi;
import com.davidparry.tooldesign.workflow.matching.ItemMatcher;

/** An order's lines with their products' categories and variants, read once per tool call. */
final class OrderLines {

    private final Map<String, ProductVariants> products = new HashMap<>();

    private final OrderApi api;

    OrderLines(OrderApi api) {
        this.api = api;
    }

    List<ItemMatcher.Line> lines(List<OrderItem> items) {
        return items.stream().map(item -> new ItemMatcher.Line(item, product(item.productId()).category())).toList();
    }

    ProductVariants product(String productId) {
        return this.products.computeIfAbsent(productId, this.api::variants);
    }

    static Results.Item item(OrderItem item) {
        return new Results.Item(item.itemId(), item.productName(), item.sku(), item.size(), item.color(),
                item.quantity(), item.unitPrice());
    }

    static List<Results.Item> items(List<ItemMatcher.Line> lines) {
        return lines.stream().map(line -> item(line.item())).toList();
    }

    static String describe(OrderItem item) {
        String variant = item.size() == null && item.color() == null ? ""
                : " (" + String.join(", ", java.util.stream.Stream.of(item.size(), item.color())
                        .filter(java.util.Objects::nonNull).toList()) + ")";
        return item.quantity() + " × " + item.productName() + variant;
    }
}
