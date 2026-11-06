package com.davidparry.tooldesign.app.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.davidparry.tooldesign.app.domain.Money;
import com.davidparry.tooldesign.app.domain.Orders.OrderLine;
import com.davidparry.tooldesign.app.domain.Orders.OrderStatus;
import com.davidparry.tooldesign.app.store.InventoryStore;
import com.davidparry.tooldesign.app.store.OrderStore;

@SpringBootTest
class FixtureLoaderTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FixtureLoader loader;

    @Autowired
    OrderStore orders;

    @Autowired
    InventoryStore inventory;

    @Test
    void loadsTheWholeStore() {
        assertThat(count("product")).isEqualTo(12);
        assertThat(count("variant")).isEqualTo(39);
        assertThat(count("warehouse")).isEqualTo(3);
        assertThat(count("delivery_zone")).isEqualTo(10);
        assertThat(count("delivery_rate")).isEqualTo(30);
        assertThat(count("return_policy")).isEqualTo(4);
        assertThat(count("inventory")).isEqualTo(39 * 3);
        assertThat(count("customer_order")).isEqualTo(40);
    }

    @Test
    void order1042IsTheDemoOrder() {
        var order = this.orders.order("1042").orElseThrow();
        assertThat(order.customerName()).isEqualTo("Maya Chen");
        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.deliveredOn()).hasToString("2026-10-28");
        assertThat(order.shipTo().postalCode()).isEqualTo("78704");
        assertThat(this.orders.lines("1042")).extracting(OrderLine::itemId, OrderLine::sku, OrderLine::unitPrice)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("1042-1", "TRB-M-BLU", Money.usd("34.99")),
                        org.assertj.core.groups.Tuple.tuple("1042-2", "RPE-STD", Money.usd("8.99")));
    }

    @Test
    void aReloadUndoesEveryChange() {
        this.inventory.set("TRB-L-BLU", "WH-CMH", 0);
        this.loader.load();
        assertThat(this.inventory.available("TRB-L-BLU", "WH-CMH")).isEqualTo(2);
    }

    private long count(String table) {
        return this.jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
