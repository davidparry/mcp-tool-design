package com.davidparry.tooldesign.app.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.davidparry.tooldesign.app.domain.Catalog.DeliveryMethod;
import com.davidparry.tooldesign.app.domain.Money;
import com.davidparry.tooldesign.app.domain.Quotes.Quote;
import com.davidparry.tooldesign.app.domain.Quotes.QuoteStatus;
import com.davidparry.tooldesign.app.service.QuoteService.ExchangeRequest;
import com.davidparry.tooldesign.app.service.QuoteService.ReturnRequest;
import com.davidparry.tooldesign.app.store.InventoryStore;
import com.davidparry.tooldesign.app.store.QuoteStore;

class QuoteServiceTest extends ServiceTestSupport {

    @Autowired
    QuoteService quotes;

    @Autowired
    QuoteStore store;

    @Autowired
    InventoryStore inventory;

    @Test
    void theColumbusGroundOptionCosts1199AndArrivesNovember12() {
        Quote quote = this.quotes.exchange(new ExchangeRequest("1042", "1042-1", "TRB-L-BLU", 1, "WH-CMH",
                DeliveryMethod.GROUND));
        assertThat(quote.quoteId()).isEqualTo("EQ-5001");
        assertThat(quote.priceDifference()).isEqualTo(Money.usd("5.00"));
        assertThat(quote.shippingCharge()).isEqualTo(Money.usd("6.99"));
        assertThat(quote.total()).isEqualTo(Money.usd("11.99"));
        assertThat(quote.estimatedArrival()).hasToString("2026-11-12");
        assertThat(quote.expiresAt()).isEqualTo(this.clock.now().plusMinutes(30));
        assertThat(quote.status()).isEqualTo(QuoteStatus.OPEN);
    }

    @Test
    void aQuoteReservesNothing() {
        this.quotes.exchange(new ExchangeRequest("1042", "1042-1", "TRB-L-BLU", 1, "WH-CMH", DeliveryMethod.EXPRESS));
        assertThat(this.inventory.available("TRB-L-BLU", "WH-CMH")).isEqualTo(2);
        assertThat(this.store.actions()).isEmpty();
    }

    @Test
    void aWarehouseWithoutStockCantBeQuoted() {
        assertThatThrownBy(() -> this.quotes.exchange(new ExchangeRequest("1042", "1042-1", "TRB-L-BLU", 1, "WH-DAL",
                DeliveryMethod.GROUND))).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("stock_unavailable");
    }

    @Test
    void anIneligibleItemCantBeQuoted() {
        BusinessException refused = catchBusiness(() -> this.quotes.exchange(
                new ExchangeRequest("1039", "1039-1", "JKT-M-YEL", 1, "WH-DAL", DeliveryMethod.GROUND)));
        assertThat(refused.code()).isEqualTo("not_eligible");
        assertThat(refused.details()).containsEntry("reasonCode", "not_delivered");
    }

    @Test
    void aReplacementHasToBeTheSameProduct() {
        assertThat(catchBusiness(() -> this.quotes.exchange(
                new ExchangeRequest("1042", "1042-1", "CTY-L-BLK", 1, "WH-DAL", DeliveryMethod.GROUND))).code())
                .isEqualTo("validation_failed");
    }

    @Test
    void theQuantityCantExceedWhatWasBought() {
        assertThat(catchBusiness(() -> this.quotes.returnQuote(new ReturnRequest("1023", "1023-1", 4))).code())
                .isEqualTo("validation_failed");
    }

    @Test
    void aGpsReturnPaysTheRestockingFee() {
        Quote quote = this.quotes.returnQuote(new ReturnRequest("1036", "1036-1", 1));
        assertThat(quote.quoteId()).isEqualTo("RQ-6001");
        assertThat(quote.itemRefund()).isEqualTo(Money.usd("129.99"));
        assertThat(quote.restockingFee()).isEqualTo(Money.usd("19.50"));
        assertThat(quote.total()).isEqualTo(Money.usd("110.49"));
    }

    @Test
    void aBedReturnPaysTheReturnShippingFee() {
        Quote quote = this.quotes.returnQuote(new ReturnRequest("1019", "1019-1", 1));
        assertThat(quote.returnShippingFee()).isEqualTo(Money.usd("19.99"));
        assertThat(quote.total()).isEqualTo(Money.usd("60.00"));
    }

    static BusinessException catchBusiness(Runnable call) {
        try {
            call.run();
        }
        catch (BusinessException e) {
            return e;
        }
        throw new AssertionError("Expected a BusinessException");
    }
}
