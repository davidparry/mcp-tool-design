package com.davidparry.tooldesign.app.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.davidparry.tooldesign.app.domain.Catalog.DeliveryMethod;
import com.davidparry.tooldesign.app.domain.Catalog.DeliveryRate;
import com.davidparry.tooldesign.app.domain.Catalog.Product;
import com.davidparry.tooldesign.app.domain.Catalog.Region;
import com.davidparry.tooldesign.app.domain.Catalog.ReturnCategory;
import com.davidparry.tooldesign.app.domain.Catalog.ReturnPolicy;
import com.davidparry.tooldesign.app.domain.Catalog.Variant;
import com.davidparry.tooldesign.app.domain.Catalog.Warehouse;
import com.davidparry.tooldesign.app.domain.Money;

@Repository
public class CatalogStore {

    private final JdbcClient jdbc;

    public CatalogStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Product> product(String id) {
        return this.jdbc.sql("SELECT * FROM product WHERE id = :id").param("id", id).query(CatalogStore::product)
                .optional();
    }

    public List<Variant> variants(String productId) {
        return this.jdbc.sql("SELECT * FROM variant WHERE product_id = :id ORDER BY sort_order")
                .param("id", productId).query(CatalogStore::variant).list();
    }

    public Optional<Variant> variant(String sku) {
        return this.jdbc.sql("SELECT * FROM variant WHERE sku = :sku").param("sku", sku).query(CatalogStore::variant)
                .optional();
    }

    public List<Warehouse> warehouses() {
        return this.jdbc.sql("SELECT * FROM warehouse ORDER BY id").query(CatalogStore::warehouse).list();
    }

    public Optional<Warehouse> warehouse(String id) {
        return this.jdbc.sql("SELECT * FROM warehouse WHERE id = :id").param("id", id).query(CatalogStore::warehouse)
                .optional();
    }

    public Optional<Region> regionForPostalPrefix(char prefix) {
        return this.jdbc.sql("SELECT region FROM delivery_zone WHERE postal_prefix = :p")
                .param("p", String.valueOf(prefix)).query((rs, n) -> Region.valueOf(rs.getString("region")))
                .optional();
    }

    public List<DeliveryRate> rates(String warehouseId, Region region) {
        return this.jdbc.sql("""
                SELECT * FROM delivery_rate WHERE warehouse_id = :w AND region = :r
                ORDER BY business_days DESC, method
                """).param("w", warehouseId).param("r", region.name()).query(CatalogStore::rate).list();
    }

    public ReturnPolicy policy(ReturnCategory category) {
        return this.jdbc.sql("SELECT * FROM return_policy WHERE return_category = :c").param("c", category.name())
                .query(CatalogStore::policy).single();
    }

    private static Product product(ResultSet rs, int row) throws SQLException {
        return new Product(rs.getString("id"), rs.getString("name"), rs.getString("category"),
                ReturnCategory.valueOf(rs.getString("return_category")));
    }

    private static Variant variant(ResultSet rs, int row) throws SQLException {
        return new Variant(rs.getString("sku"), rs.getString("product_id"), rs.getString("size"),
                rs.getString("color"), Money.usd(rs.getBigDecimal("price")));
    }

    private static Warehouse warehouse(ResultSet rs, int row) throws SQLException {
        return new Warehouse(rs.getString("id"), rs.getString("name"), rs.getString("city"), rs.getString("state"),
                Region.valueOf(rs.getString("region")));
    }

    private static DeliveryRate rate(ResultSet rs, int row) throws SQLException {
        return new DeliveryRate(rs.getString("warehouse_id"), Region.valueOf(rs.getString("region")),
                DeliveryMethod.valueOf(rs.getString("method")), rs.getInt("business_days"),
                Money.usd(rs.getBigDecimal("charge")));
    }

    private static ReturnPolicy policy(ResultSet rs, int row) throws SQLException {
        return new ReturnPolicy(ReturnCategory.valueOf(rs.getString("return_category")), rs.getString("description"),
                rs.getBoolean("returnable"), rs.getBoolean("exchangeable"), rs.getInt("return_window_days"),
                rs.getInt("exchange_window_days"), Money.usd(rs.getBigDecimal("return_shipping_fee")),
                rs.getInt("restocking_fee_percent"));
    }
}
