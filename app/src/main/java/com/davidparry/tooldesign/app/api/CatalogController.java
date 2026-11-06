package com.davidparry.tooldesign.app.api;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.davidparry.tooldesign.app.api.ApiModels.DeliveryOptionView;
import com.davidparry.tooldesign.app.api.ApiModels.DeliveryOptions;
import com.davidparry.tooldesign.app.api.ApiModels.InventoryView;
import com.davidparry.tooldesign.app.api.ApiModels.ProductVariants;
import com.davidparry.tooldesign.app.api.ApiModels.VariantView;
import com.davidparry.tooldesign.app.api.ApiModels.WarehouseStock;
import com.davidparry.tooldesign.app.domain.Catalog.Product;
import com.davidparry.tooldesign.app.domain.Catalog.Warehouse;
import com.davidparry.tooldesign.app.service.DeliveryEstimator;
import com.davidparry.tooldesign.app.service.DeliveryEstimator.DeliveryOption;
import com.davidparry.tooldesign.app.service.OrderLookup;
import com.davidparry.tooldesign.app.store.CatalogStore;
import com.davidparry.tooldesign.app.store.InventoryStore;
import com.davidparry.tooldesign.app.store.InventoryStore.StockLevel;

@RestController
@RequestMapping("/api")
@Tag(name = "Catalog", description = "Products, warehouse stock, and delivery options")
public class CatalogController {

    private final OrderLookup lookup;

    private final CatalogStore catalog;

    private final InventoryStore inventory;

    private final DeliveryEstimator delivery;

    public CatalogController(OrderLookup lookup, CatalogStore catalog, InventoryStore inventory,
            DeliveryEstimator delivery) {
        this.lookup = lookup;
        this.catalog = catalog;
        this.inventory = inventory;
        this.delivery = delivery;
    }

    @GetMapping("/products/{productId}/variants")
    @Operation(operationId = "get_product_variants", summary = "List a product's variants",
            description = "Returns every size and color a product is sold in, with each variant's SKU and today's price. Stock per warehouse is at /api/inventory. Read-only.")
    public ProductVariants variants(
            @Parameter(description = "The product ID from an order item, such as TRB.") @PathVariable String productId) {
        Product product = this.lookup.product(productId);
        List<VariantView> variants = this.catalog.variants(productId).stream()
                .map(v -> new VariantView(v.sku(), v.size(), v.color(), v.price())).toList();
        return new ProductVariants(product.id(), product.name(), product.category(), variants);
    }

    @GetMapping("/inventory")
    @Operation(operationId = "get_inventory", summary = "Get stock for a variant",
            description = "Returns how many units of a variant are available at each warehouse. Reading stock doesn't reserve it. Read-only.")
    public InventoryView inventory(@Parameter(description = "The variant SKU, such as TRB-L-BLU.") @RequestParam String sku) {
        this.lookup.variant(sku);
        Map<String, Warehouse> warehouses = this.catalog.warehouses().stream()
                .collect(Collectors.toMap(Warehouse::id, Function.identity()));
        List<WarehouseStock> stock = this.inventory.stock(sku).stream().map(level -> stock(level, warehouses))
                .toList();
        return new InventoryView(sku, stock.stream().mapToInt(WarehouseStock::available).sum(), stock);
    }

    @GetMapping("/delivery-options")
    @Operation(operationId = "get_delivery_options", summary = "Get delivery options",
            description = "Returns the ground and express options for a shipment from a warehouse to a postal code: the shipping charge, the business days in transit, and the estimated arrival for a shipment leaving on the ship date. Read-only.")
    public DeliveryOptions deliveryOptions(
            @Parameter(description = "The warehouse to ship from, such as WH-CMH.") @RequestParam String warehouseId,
            @Parameter(description = "The destination postal code, such as 78704.") @RequestParam String postalCode) {
        List<DeliveryOption> options = this.delivery.options(warehouseId, postalCode);
        return new DeliveryOptions(warehouseId, postalCode, this.delivery.shipDate(),
                options.stream()
                        .map(o -> new DeliveryOptionView(o.method(), o.businessDays(), o.estimatedArrival(), o.charge()))
                        .toList());
    }

    private static WarehouseStock stock(StockLevel level, Map<String, Warehouse> warehouses) {
        Warehouse w = warehouses.get(level.warehouseId());
        return new WarehouseStock(w.id(), w.name(), w.city(), w.state(), level.available());
    }
}
