package com.davidparry.tooldesign.app.service;

import static com.davidparry.tooldesign.app.service.QuoteServiceTest.catchBusiness;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.davidparry.tooldesign.app.domain.Catalog.DeliveryMethod;
import com.davidparry.tooldesign.app.domain.Money;
import com.davidparry.tooldesign.app.domain.Quotes.Quote;
import com.davidparry.tooldesign.app.domain.Quotes.QuoteStatus;
import com.davidparry.tooldesign.app.service.QuoteService.ExchangeRequest;
import com.davidparry.tooldesign.app.service.QuoteService.ReturnRequest;
import com.davidparry.tooldesign.app.service.SubmissionService.Submission;
import com.davidparry.tooldesign.app.store.InventoryStore;
import com.davidparry.tooldesign.app.store.OrderStore;
import com.davidparry.tooldesign.app.store.QuoteStore;

class SubmissionServiceTest extends ServiceTestSupport {

    @Autowired
    QuoteService quotes;

    @Autowired
    SubmissionService submissions;

    @Autowired
    QuoteStore store;

    @Autowired
    InventoryStore inventory;

    @Autowired
    OrderStore orders;

    @Test
    void anApprovedExchangeCommitsAndTakesTheStock() {
        Quote quote = columbusGround();
        Submission submission = this.submissions.submitExchange(quote.quoteId());
        assertThat(submission.created()).isTrue();
        assertThat(submission.action().actionId()).isEqualTo("EX-7001");
        assertThat(submission.action().amount()).isEqualTo(Money.usd("11.99"));
        assertThat(this.inventory.available("TRB-L-BLU", "WH-CMH")).isEqualTo(1);
        assertThat(this.store.quote(quote.quoteId()).orElseThrow().status()).isEqualTo(QuoteStatus.SUBMITTED);
        assertThat(this.orders.line("1042", "1042-1").orElseThrow().exchangedQuantity()).isEqualTo(1);
    }

    @Test
    void submittingTheSameQuoteTwiceCommitsOnce() {
        Quote quote = columbusGround();
        Submission first = this.submissions.submitExchange(quote.quoteId());
        Submission second = this.submissions.submitExchange(quote.quoteId());
        assertThat(second.created()).isFalse();
        assertThat(second.action()).isEqualTo(first.action());
        assertThat(this.store.actions()).hasSize(1);
        assertThat(this.inventory.available("TRB-L-BLU", "WH-CMH")).isEqualTo(1);
    }

    @Test
    void anExpiredQuoteCommitsNothing() {
        Quote quote = columbusGround();
        this.clock.advance(Duration.ofMinutes(31));
        assertThat(catchBusiness(() -> this.submissions.submitExchange(quote.quoteId())).code())
                .isEqualTo("quote_expired");
        assertThat(this.store.actions()).isEmpty();
        assertThat(this.inventory.available("TRB-L-BLU", "WH-CMH")).isEqualTo(2);
    }

    @Test
    void stockThatSoldOutAfterThePreviewCommitsNothing() {
        Quote quote = columbusGround();
        this.inventory.set("TRB-L-BLU", "WH-CMH", 0);
        BusinessException refused = catchBusiness(() -> this.submissions.submitExchange(quote.quoteId()));
        assertThat(refused.code()).isEqualTo("stock_unavailable");
        assertThat(this.store.actions()).isEmpty();
        assertThat(this.store.quote(quote.quoteId()).orElseThrow().status()).isEqualTo(QuoteStatus.OPEN);
    }

    @Test
    void aReturnQuoteCantBeSubmittedAsAnExchange() {
        Quote quote = this.quotes.returnQuote(new ReturnRequest("1019", "1019-1", 1));
        assertThat(catchBusiness(() -> this.submissions.submitExchange(quote.quoteId())).code())
                .isEqualTo("validation_failed");
        assertThat(catchBusiness(() -> this.submissions.submitExchange("EQ-9999")).code())
                .isEqualTo("quote_not_found");
    }

    @Test
    void aSubmittedReturnUsesUpTheQuantity() {
        Quote two = this.quotes.returnQuote(new ReturnRequest("1023", "1023-1", 2));
        Quote alsoTwo = this.quotes.returnQuote(new ReturnRequest("1023", "1023-1", 2));
        this.submissions.submitReturn(two.quoteId());
        BusinessException refused = catchBusiness(() -> this.submissions.submitReturn(alsoTwo.quoteId()));
        assertThat(refused.code()).isEqualTo("not_eligible");
        assertThat(this.orders.line("1023", "1023-1").orElseThrow().returnedQuantity()).isEqualTo(2);
    }

    private Quote columbusGround() {
        return this.quotes.exchange(new ExchangeRequest("1042", "1042-1", "TRB-L-BLU", 1, "WH-CMH",
                DeliveryMethod.GROUND));
    }
}
