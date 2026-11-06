package com.davidparry.tooldesign.app.store;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.davidparry.tooldesign.app.domain.Catalog.DeliveryMethod;
import com.davidparry.tooldesign.app.domain.Money;
import com.davidparry.tooldesign.app.domain.Quotes.ActionStatus;
import com.davidparry.tooldesign.app.domain.Quotes.CommittedAction;
import com.davidparry.tooldesign.app.domain.Quotes.Quote;
import com.davidparry.tooldesign.app.domain.Quotes.QuoteStatus;
import com.davidparry.tooldesign.app.domain.Quotes.QuoteType;

/** Quotes and the actions committed from them. */
@Repository
public class QuoteStore {

    private final JdbcClient jdbc;

    public QuoteStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Quote q) {
        this.jdbc.sql("""
                INSERT INTO quote (quote_id, type, order_reference, item_id, sku, replacement_sku, quantity,
                    warehouse_id, delivery_method, estimated_arrival, price_difference, shipping_charge, item_refund,
                    return_shipping_fee, restocking_fee, total, created_at, expires_at, status)
                VALUES (:id, :type, :order, :item, :sku, :replacement, :quantity, :warehouse, :method, :arrival,
                    :difference, :shipping, :refund, :returnFee, :restocking, :total, :created, :expires, :status)
                """)
                .param("id", q.quoteId()).param("type", q.type().name()).param("order", q.orderReference())
                .param("item", q.itemId()).param("sku", q.sku()).param("replacement", q.replacementSku())
                .param("quantity", q.quantity()).param("warehouse", q.warehouseId())
                .param("method", q.deliveryMethod() == null ? null : q.deliveryMethod().name())
                .param("arrival", q.estimatedArrival()).param("difference", amount(q.priceDifference()))
                .param("shipping", amount(q.shippingCharge())).param("refund", amount(q.itemRefund()))
                .param("returnFee", amount(q.returnShippingFee())).param("restocking", amount(q.restockingFee()))
                .param("total", amount(q.total())).param("created", q.createdAt()).param("expires", q.expiresAt())
                .param("status", q.status().name()).update();
    }

    public Optional<Quote> quote(String quoteId) {
        return this.jdbc.sql("SELECT * FROM quote WHERE quote_id = :id").param("id", quoteId)
                .query(QuoteStore::quote).optional();
    }

    public List<Quote> quotes() {
        return this.jdbc.sql("SELECT * FROM quote ORDER BY created_at, quote_id").query(QuoteStore::quote).list();
    }

    public void markSubmitted(String quoteId) {
        this.jdbc.sql("UPDATE quote SET status = 'SUBMITTED' WHERE quote_id = :id").param("id", quoteId).update();
    }

    public void insert(CommittedAction a) {
        this.jdbc.sql("""
                INSERT INTO committed_action (action_id, type, quote_id, order_reference, item_id, sku,
                    replacement_sku, quantity, warehouse_id, delivery_method, estimated_arrival, amount, status,
                    submitted_at)
                VALUES (:id, :type, :quote, :order, :item, :sku, :replacement, :quantity, :warehouse, :method,
                    :arrival, :amount, :status, :submitted)
                """)
                .param("id", a.actionId()).param("type", a.type().name()).param("quote", a.quoteId())
                .param("order", a.orderReference()).param("item", a.itemId()).param("sku", a.sku())
                .param("replacement", a.replacementSku()).param("quantity", a.quantity())
                .param("warehouse", a.warehouseId())
                .param("method", a.deliveryMethod() == null ? null : a.deliveryMethod().name())
                .param("arrival", a.estimatedArrival()).param("amount", amount(a.amount()))
                .param("status", a.status().name()).param("submitted", a.submittedAt()).update();
    }

    public Optional<CommittedAction> actionForQuote(String quoteId) {
        return this.jdbc.sql("SELECT * FROM committed_action WHERE quote_id = :q").param("q", quoteId)
                .query(QuoteStore::action).optional();
    }

    public List<CommittedAction> actions() {
        return this.jdbc.sql("SELECT * FROM committed_action ORDER BY submitted_at, action_id")
                .query(QuoteStore::action).list();
    }

    private static BigDecimal amount(Money money) {
        return money == null ? null : money.amount();
    }

    private static Money money(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : Money.usd(value);
    }

    private static DeliveryMethod method(ResultSet rs) throws SQLException {
        String method = rs.getString("delivery_method");
        return method == null ? null : DeliveryMethod.valueOf(method);
    }

    private static Quote quote(ResultSet rs, int row) throws SQLException {
        return new Quote(rs.getString("quote_id"), QuoteType.valueOf(rs.getString("type")),
                rs.getString("order_reference"), rs.getString("item_id"), rs.getString("sku"),
                rs.getString("replacement_sku"), rs.getInt("quantity"), rs.getString("warehouse_id"), method(rs),
                OrderStore.date(rs, "estimated_arrival"), money(rs, "price_difference"),
                money(rs, "shipping_charge"), money(rs, "item_refund"), money(rs, "return_shipping_fee"),
                money(rs, "restocking_fee"), money(rs, "total"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class), QuoteStatus.valueOf(rs.getString("status")));
    }

    private static CommittedAction action(ResultSet rs, int row) throws SQLException {
        return new CommittedAction(rs.getString("action_id"), QuoteType.valueOf(rs.getString("type")),
                rs.getString("quote_id"), rs.getString("order_reference"), rs.getString("item_id"),
                rs.getString("sku"), rs.getString("replacement_sku"), rs.getInt("quantity"),
                rs.getString("warehouse_id"), method(rs), OrderStore.date(rs, "estimated_arrival"),
                money(rs, "amount"), ActionStatus.valueOf(rs.getString("status")),
                rs.getObject("submitted_at", OffsetDateTime.class));
    }
}
