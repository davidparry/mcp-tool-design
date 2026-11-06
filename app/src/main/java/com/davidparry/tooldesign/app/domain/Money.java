package com.davidparry.tooldesign.app.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

/** An amount in one currency at scale 2, rounded half up. Serialized as {@code {"amount":"15.00","currency":"USD"}}. */
@Schema(description = "A monetary amount. The amount is a decimal string with two places.")
public record Money(
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @Schema(type = "string", example = "15.00")
        BigDecimal amount,
        @Schema(example = "USD")
        String currency) implements Comparable<Money> {

    public static final String USD = "USD";

    public static final Money ZERO = usd("0.00");

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        amount = amount.setScale(2, RoundingMode.HALF_UP);
    }

    public static Money usd(String amount) {
        return new Money(new BigDecimal(amount), USD);
    }

    public static Money usd(BigDecimal amount) {
        return new Money(amount, USD);
    }

    public Money plus(Money other) {
        return new Money(this.amount.add(sameCurrency(other).amount), this.currency);
    }

    public Money minus(Money other) {
        return new Money(this.amount.subtract(sameCurrency(other).amount), this.currency);
    }

    public Money times(int quantity) {
        return new Money(this.amount.multiply(BigDecimal.valueOf(quantity)), this.currency);
    }

    /** {@code percent} of this amount, rounded half up to the cent. */
    public Money percent(int percent) {
        return new Money(this.amount.multiply(BigDecimal.valueOf(percent)).divide(BigDecimal.valueOf(100), 2,
                RoundingMode.HALF_UP), this.currency);
    }

    public boolean isPositive() {
        return this.amount.signum() > 0;
    }

    @Override
    public int compareTo(Money other) {
        return this.amount.compareTo(sameCurrency(other).amount);
    }

    private Money sameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException("Can't combine " + this.currency + " with " + other.currency);
        }
        return other;
    }

    @Override
    public String toString() {
        return this.amount.toPlainString() + " " + this.currency;
    }
}
