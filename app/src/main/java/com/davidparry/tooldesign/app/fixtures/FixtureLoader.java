package com.davidparry.tooldesign.app.fixtures;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.davidparry.tooldesign.app.store.IdSequence;

/**
 * Loads {@code fixtures/seed} into H2 at startup, and again on every benchmark reset. A load replaces all state,
 * committed actions and quotes included, and restarts the generated IDs.
 */
@Component
public class FixtureLoader implements InitializingBean {

    /** Orders at or over this subtotal shipped free; the rest paid the flat charge. */
    private static final BigDecimal FREE_SHIPPING_FROM = new BigDecimal("50.00");

    private static final BigDecimal FLAT_SHIPPING = new BigDecimal("5.99");

    private static final List<String> TABLES_IN_DELETE_ORDER = List.of("committed_action", "quote", "inventory",
            "order_line", "customer_order", "delivery_rate", "delivery_zone", "return_policy", "variant", "product",
            "warehouse");

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final IdSequence ids;

    private final List<Seed.Product> products;

    private final List<Seed.Warehouse> warehouses;

    private final Seed.Delivery delivery;

    private final List<Seed.Policy> policies;

    private final Seed.Inventory inventory;

    private final List<Seed.Order> orders;

    public FixtureLoader(JdbcClient jdbc, TransactionTemplate transactions, IdSequence ids, JsonMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.ids = ids;
        this.products = read(json, "products.json", new TypeReference<>() {
        });
        this.warehouses = read(json, "warehouses.json", new TypeReference<>() {
        });
        this.delivery = read(json, "delivery.json", new TypeReference<>() {
        });
        this.policies = read(json, "policies.json", new TypeReference<>() {
        });
        this.inventory = read(json, "inventory.json", new TypeReference<>() {
        });
        this.orders = read(json, "orders.json", new TypeReference<>() {
        });
    }

    @Override
    public void afterPropertiesSet() {
        load();
    }

    public void load() {
        this.transactions.executeWithoutResult(status -> {
            TABLES_IN_DELETE_ORDER.forEach(table -> this.jdbc.sql("DELETE FROM " + table).update());
            insertCatalog();
            insertOrders();
        });
        this.ids.reset();
    }

    public List<Seed.Order> orders() {
        return this.orders;
    }

    private void insertCatalog() {
        for (Seed.Warehouse w : this.warehouses) {
            this.jdbc.sql("INSERT INTO warehouse VALUES (:id, :name, :city, :state, :region)").param("id", w.id())
                    .param("name", w.name()).param("city", w.city()).param("state", w.state())
                    .param("region", w.region()).update();
        }
        for (Seed.Product p : this.products) {
            this.jdbc.sql("INSERT INTO product VALUES (:id, :name, :category, :returnCategory)").param("id", p.id())
                    .param("name", p.name()).param("category", p.category())
                    .param("returnCategory", p.returnCategory()).update();
            int sortOrder = 0;
            for (Seed.Variant v : p.variants()) {
                this.jdbc.sql("INSERT INTO variant VALUES (:sku, :product, :size, :color, :price, :sort)")
                        .param("sku", v.sku()).param("product", p.id()).param("size", v.size())
                        .param("color", v.color()).param("price", new BigDecimal(v.price()))
                        .param("sort", sortOrder++).update();
            }
        }
        for (Map.Entry<String, String> zone : this.delivery.zones().entrySet()) {
            this.jdbc.sql("INSERT INTO delivery_zone VALUES (:prefix, :region)").param("prefix", zone.getKey())
                    .param("region", zone.getValue()).update();
        }
        for (Seed.Rate r : this.delivery.rates()) {
            this.jdbc.sql("INSERT INTO delivery_rate VALUES (:w, :region, :method, :days, :charge)")
                    .param("w", r.warehouseId()).param("region", r.region()).param("method", r.method())
                    .param("days", r.businessDays()).param("charge", new BigDecimal(r.charge())).update();
        }
        for (Seed.Policy p : this.policies) {
            this.jdbc.sql("""
                    INSERT INTO return_policy VALUES (:category, :description, :returnable, :exchangeable,
                        :returnDays, :exchangeDays, :fee, :restocking)
                    """).param("category", p.returnCategory()).param("description", p.description())
                    .param("returnable", p.returnable()).param("exchangeable", p.exchangeable())
                    .param("returnDays", p.returnWindowDays()).param("exchangeDays", p.exchangeWindowDays())
                    .param("fee", new BigDecimal(p.returnShippingFee())).param("restocking", p.restockingFeePercent())
                    .update();
        }
        for (Map.Entry<String, List<Integer>> stock : this.inventory.available().entrySet()) {
            for (int i = 0; i < this.inventory.warehouses().size(); i++) {
                this.jdbc.sql("INSERT INTO inventory VALUES (:sku, :w, :available)").param("sku", stock.getKey())
                        .param("w", this.inventory.warehouses().get(i)).param("available", stock.getValue().get(i))
                        .update();
            }
        }
    }

    private void insertOrders() {
        for (Seed.Order o : this.orders) {
            BigDecimal subtotal = o.lines().stream()
                    .map(line -> new BigDecimal(line.unitPrice()).multiply(BigDecimal.valueOf(line.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal shipping = subtotal.compareTo(FREE_SHIPPING_FROM) >= 0 ? new BigDecimal("0.00") : FLAT_SHIPPING;
            this.jdbc.sql("""
                    INSERT INTO customer_order VALUES (:ref, :name, :email, :line1, :city, :state, :postal, :status,
                        :placed, :shipped, :delivered, :estimated, :shipping)
                    """).param("ref", o.reference()).param("name", o.customer().name())
                    .param("email", o.customer().email()).param("line1", o.shipTo().line1())
                    .param("city", o.shipTo().city()).param("state", o.shipTo().state())
                    .param("postal", o.shipTo().postalCode()).param("status", o.status())
                    .param("placed", OffsetDateTime.parse(o.placedAt())).param("shipped", date(o.shippedOn()))
                    .param("delivered", date(o.deliveredOn())).param("estimated", date(o.estimatedDelivery()))
                    .param("shipping", shipping).update();
            int lineNo = 1;
            for (Seed.Line line : o.lines()) {
                this.jdbc.sql("""
                        INSERT INTO order_line (item_id, order_reference, line_no, sku, quantity, unit_price)
                        VALUES (:item, :ref, :lineNo, :sku, :quantity, :price)
                        """).param("item", o.reference() + "-" + lineNo).param("ref", o.reference())
                        .param("lineNo", lineNo).param("sku", line.sku()).param("quantity", line.quantity())
                        .param("price", new BigDecimal(line.unitPrice())).update();
                lineNo++;
            }
        }
    }

    private static LocalDate date(String value) {
        return value == null ? null : LocalDate.parse(value);
    }

    private static <T> T read(JsonMapper json, String file, TypeReference<T> type) {
        try (InputStream in = new ClassPathResource("seed/" + file).getInputStream()) {
            return json.readValue(in, type);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Can't read fixture seed/" + file, e);
        }
    }
}
