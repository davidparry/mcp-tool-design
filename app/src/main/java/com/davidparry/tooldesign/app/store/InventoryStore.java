package com.davidparry.tooldesign.app.store;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InventoryStore {

    public record StockLevel(String sku, String warehouseId, int available) {
    }

    private final JdbcClient jdbc;

    public InventoryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<StockLevel> stock(String sku) {
        return this.jdbc.sql("SELECT * FROM inventory WHERE sku = :s ORDER BY warehouse_id").param("s", sku)
                .query((rs, n) -> new StockLevel(rs.getString("sku"), rs.getString("warehouse_id"),
                        rs.getInt("available")))
                .list();
    }

    public List<StockLevel> all() {
        return this.jdbc.sql("SELECT * FROM inventory ORDER BY sku, warehouse_id")
                .query((rs, n) -> new StockLevel(rs.getString("sku"), rs.getString("warehouse_id"),
                        rs.getInt("available")))
                .list();
    }

    public int available(String sku, String warehouseId) {
        return this.jdbc.sql("SELECT available FROM inventory WHERE sku = :s AND warehouse_id = :w").param("s", sku)
                .param("w", warehouseId).query(Integer.class).optional().orElse(0);
    }

    /** Takes {@code quantity} units if that many are available. Returns false, and changes nothing, otherwise. */
    public boolean take(String sku, String warehouseId, int quantity) {
        return this.jdbc.sql("""
                UPDATE inventory SET available = available - :q
                WHERE sku = :s AND warehouse_id = :w AND available >= :q
                """).param("q", quantity).param("s", sku).param("w", warehouseId).update() == 1;
    }

    public void set(String sku, String warehouseId, int available) {
        this.jdbc.sql("UPDATE inventory SET available = :a WHERE sku = :s AND warehouse_id = :w")
                .param("a", available).param("s", sku).param("w", warehouseId).update();
    }
}
