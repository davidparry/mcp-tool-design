package com.davidparry.tooldesign.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void keepsTwoPlacesRoundingHalfUp() {
        assertThat(new Money(new BigDecimal("19.4985"), "USD").amount()).isEqualByComparingTo("19.50");
        assertThat(Money.usd("15").amount().toPlainString()).isEqualTo("15.00");
    }

    @Test
    void restockingFeeOnTheGpsCollarRoundsToTheCent() {
        assertThat(Money.usd("129.99").percent(15)).isEqualTo(Money.usd("19.50"));
    }

    @Test
    void arithmeticStaysInOneCurrency() {
        assertThat(Money.usd("39.99").minus(Money.usd("34.99")).plus(Money.usd("6.99"))).isEqualTo(Money.usd("11.99"));
        assertThat(Money.usd("11.99").times(2)).isEqualTo(Money.usd("23.98"));
        assertThatThrownBy(() -> Money.usd("1.00").plus(new Money(BigDecimal.ONE, "EUR")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void comparesByAmount() {
        assertThat(Money.usd("25.00").compareTo(Money.usd("25"))).isZero();
        assertThat(Money.usd("24.99")).isLessThan(Money.usd("25.00"));
    }
}
