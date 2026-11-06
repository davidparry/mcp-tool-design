package com.davidparry.tooldesign.app.store;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.davidparry.tooldesign.app.domain.Money;
import com.davidparry.tooldesign.app.domain.Orders.Address;
import com.davidparry.tooldesign.app.domain.Orders.Order;
import com.davidparry.tooldesign.app.domain.Orders.OrderLine;
import com.davidparry.tooldesign.app.domain.Orders.OrderStatus;

@Repository
public class OrderStore {

    public record Page(List<Order> orders, int page, int size, long totalItems) {
    }

    private final JdbcClient jdbc;

    public OrderStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Order> order(String reference) {
        return this.jdbc.sql("SELECT * FROM customer_order WHERE reference = :r").param("r", reference)
                .query(OrderStore::order).optional();
    }

    public List<OrderLine> lines(String reference) {
        return this.jdbc.sql("SELECT * FROM order_line WHERE order_reference = :r ORDER BY line_no")
                .param("r", reference).query(OrderStore::line).list();
    }

    public Optional<OrderLine> line(String reference, String itemId) {
        return this.jdbc.sql("SELECT * FROM order_line WHERE order_reference = :r AND item_id = :i")
                .param("r", reference).param("i", itemId).query(OrderStore::line).optional();
    }

    /**
     * Orders whose reference equals the query, or whose customer name or email contains it, newest first. A null
     * or blank query matches every order.
     */
    public Page search(String query, OrderStatus status, int page, int size) {
        String where = """
                WHERE (:q = '' OR reference = :exact OR LOWER(customer_name) LIKE :like OR LOWER(customer_email) LIKE :like)
                AND (:status = '' OR status = :status)
                """;
        String q = query == null ? "" : query.trim();
        String like = "%" + q.toLowerCase(Locale.ROOT) + "%";
        String statusName = status == null ? "" : status.name();
        long total = this.jdbc.sql("SELECT COUNT(*) FROM customer_order " + where).param("q", q).param("exact", q)
                .param("like", like).param("status", statusName).query(Long.class).single();
        List<Order> orders = this.jdbc
                .sql("SELECT * FROM customer_order " + where + " ORDER BY placed_at DESC LIMIT :limit OFFSET :offset")
                .param("q", q).param("exact", q).param("like", like).param("status", statusName)
                .param("limit", size).param("offset", (long) page * size).query(OrderStore::order).list();
        return new Page(orders, page, size, total);
    }

    public void addReturned(String itemId, int quantity) {
        this.jdbc.sql("UPDATE order_line SET returned_quantity = returned_quantity + :q WHERE item_id = :i")
                .param("q", quantity).param("i", itemId).update();
    }

    public void addExchanged(String itemId, int quantity) {
        this.jdbc.sql("UPDATE order_line SET exchanged_quantity = exchanged_quantity + :q WHERE item_id = :i")
                .param("q", quantity).param("i", itemId).update();
    }

    private static Order order(ResultSet rs, int row) throws SQLException {
        return new Order(rs.getString("reference"), rs.getString("customer_name"), rs.getString("customer_email"),
                new Address(rs.getString("ship_line1"), rs.getString("ship_city"), rs.getString("ship_state"),
                        rs.getString("ship_postal_code")),
                OrderStatus.valueOf(rs.getString("status")), rs.getObject("placed_at", OffsetDateTime.class),
                date(rs, "shipped_on"), date(rs, "delivered_on"), date(rs, "estimated_delivery"),
                Money.usd(rs.getBigDecimal("shipping_charge")));
    }

    private static OrderLine line(ResultSet rs, int row) throws SQLException {
        return new OrderLine(rs.getString("item_id"), rs.getString("order_reference"), rs.getInt("line_no"),
                rs.getString("sku"), rs.getInt("quantity"), Money.usd(rs.getBigDecimal("unit_price")),
                rs.getInt("returned_quantity"), rs.getInt("exchanged_quantity"));
    }

    static LocalDate date(ResultSet rs, String column) throws SQLException {
        Date date = rs.getDate(column);
        return date == null ? null : date.toLocalDate();
    }
}
