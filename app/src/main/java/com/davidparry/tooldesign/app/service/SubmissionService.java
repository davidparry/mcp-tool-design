package com.davidparry.tooldesign.app.service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.davidparry.tooldesign.app.domain.BusinessClock;
import com.davidparry.tooldesign.app.domain.Orders.Order;
import com.davidparry.tooldesign.app.domain.Orders.OrderLine;
import com.davidparry.tooldesign.app.domain.Quotes.ActionStatus;
import com.davidparry.tooldesign.app.domain.Quotes.CommittedAction;
import com.davidparry.tooldesign.app.domain.Quotes.Quote;
import com.davidparry.tooldesign.app.domain.Quotes.QuoteType;
import com.davidparry.tooldesign.app.service.EligibilityService.Action;
import com.davidparry.tooldesign.app.service.EligibilityService.Eligibility;
import com.davidparry.tooldesign.app.store.IdSequence;
import com.davidparry.tooldesign.app.store.InventoryStore;
import com.davidparry.tooldesign.app.store.OrderStore;
import com.davidparry.tooldesign.app.store.QuoteStore;

/**
 * Commits the exchange or return a quote describes. Submitting revalidates everything the quote assumed: it hasn't
 * expired, the item is still eligible and still has the units, and for an exchange the warehouse still has the
 * stock. Submitting a quote that was already submitted returns the original action and commits nothing new.
 */
@Service
public class SubmissionService {

    /** {@code created} is false when the quote had already been submitted and {@code action} is the original. */
    public record Submission(CommittedAction action, boolean created) {
    }

    private final QuoteStore quotes;

    private final OrderLookup lookup;

    private final OrderStore orders;

    private final InventoryStore inventory;

    private final QuoteService quoteService;

    private final IdSequence ids;

    private final BusinessClock clock;

    public SubmissionService(QuoteStore quotes, OrderLookup lookup, OrderStore orders, InventoryStore inventory,
            QuoteService quoteService, IdSequence ids, BusinessClock clock) {
        this.quotes = quotes;
        this.lookup = lookup;
        this.orders = orders;
        this.inventory = inventory;
        this.quoteService = quoteService;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    public synchronized Submission submitExchange(String quoteId) {
        Quote quote = quote(quoteId, QuoteType.EXCHANGE);
        Optional<CommittedAction> earlier = this.quotes.actionForQuote(quoteId);
        if (earlier.isPresent()) {
            return new Submission(earlier.get(), false);
        }
        OffsetDateTime now = requireUnexpired(quote);
        Order order = this.lookup.order(quote.orderReference());
        OrderLine line = this.lookup.line(quote.orderReference(), quote.itemId());
        requireStillEligible(Action.EXCHANGE, order, line, quote);
        if (!this.inventory.take(quote.replacementSku(), quote.warehouseId(), quote.quantity())) {
            int available = this.inventory.available(quote.replacementSku(), quote.warehouseId());
            throw new BusinessException(HttpStatus.CONFLICT, "stock_unavailable",
                    "Warehouse " + quote.warehouseId() + " no longer has " + quote.quantity() + " of "
                            + quote.replacementSku() + " available (it has " + available + "). Nothing was submitted.",
                    Map.of("sku", quote.replacementSku(), "warehouseId", quote.warehouseId(), "available", available));
        }
        this.orders.addExchanged(line.itemId(), quote.quantity());
        CommittedAction action = new CommittedAction(this.ids.exchange(), QuoteType.EXCHANGE, quote.quoteId(),
                quote.orderReference(), quote.itemId(), quote.sku(), quote.replacementSku(), quote.quantity(),
                quote.warehouseId(), quote.deliveryMethod(), quote.estimatedArrival(), quote.total(),
                ActionStatus.CONFIRMED, now);
        commit(action);
        return new Submission(action, true);
    }

    @Transactional
    public synchronized Submission submitReturn(String quoteId) {
        Quote quote = quote(quoteId, QuoteType.RETURN);
        Optional<CommittedAction> earlier = this.quotes.actionForQuote(quoteId);
        if (earlier.isPresent()) {
            return new Submission(earlier.get(), false);
        }
        OffsetDateTime now = requireUnexpired(quote);
        Order order = this.lookup.order(quote.orderReference());
        OrderLine line = this.lookup.line(quote.orderReference(), quote.itemId());
        requireStillEligible(Action.RETURN, order, line, quote);
        this.orders.addReturned(line.itemId(), quote.quantity());
        CommittedAction action = new CommittedAction(this.ids.returnAction(), QuoteType.RETURN, quote.quoteId(),
                quote.orderReference(), quote.itemId(), quote.sku(), null, quote.quantity(), null, null, null,
                quote.total(), ActionStatus.CONFIRMED, now);
        commit(action);
        return new Submission(action, true);
    }

    private void commit(CommittedAction action) {
        this.quotes.insert(action);
        this.quotes.markSubmitted(action.quoteId());
    }

    private Quote quote(String quoteId, QuoteType type) {
        if (quoteId == null || quoteId.isBlank()) {
            throw BusinessException.invalid("quoteId is required");
        }
        Quote quote = this.quotes.quote(quoteId)
                .orElseThrow(() -> BusinessException.notFound("quote_not_found", "There is no quote " + quoteId));
        if (quote.type() != type) {
            throw BusinessException.invalid("Quote " + quoteId + " is a " + quote.type().name().toLowerCase()
                    + " quote, not a " + type.name().toLowerCase() + " quote");
        }
        return quote;
    }

    private OffsetDateTime requireUnexpired(Quote quote) {
        OffsetDateTime now = this.clock.now();
        if (quote.expiredAt(now)) {
            throw new BusinessException(HttpStatus.CONFLICT, "quote_expired",
                    "Quote " + quote.quoteId() + " expired at " + quote.expiresAt()
                            + ". Nothing was submitted; create a new quote to continue.",
                    Map.of("quoteId", quote.quoteId(), "expiresAt", quote.expiresAt().toString()));
        }
        return now;
    }

    private void requireStillEligible(Action action, Order order, OrderLine line, Quote quote) {
        Eligibility verdict = this.quoteService.requireEligible(action, order, line);
        if (quote.quantity() > verdict.maxQuantity()) {
            throw new BusinessException(HttpStatus.CONFLICT, "not_eligible",
                    "Only " + verdict.maxQuantity() + " of item " + line.itemId()
                            + " can still be returned or exchanged; the quote is for " + quote.quantity(),
                    Map.of("reasonCode", "nothing_left"));
        }
    }
}
