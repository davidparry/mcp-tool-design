package com.davidparry.tooldesign.workflow.tools;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** Tool inputs that need parsing, and the refusal a tool returns when one can't be read. */
public final class Inputs {

    private Inputs() {
    }

    /** The constant names are the JSON values the schema offers. */
    public enum Comparison {
        under, at_most
    }

    public record CostLimit(
            @JsonPropertyDescription("The amount in USD, such as \"15.00\"") String amount,
            @JsonPropertyDescription("under: the cost must be less than the amount. at_most: it may equal it.") Comparison comparison) {

        boolean allows(BigDecimal cost) {
            int compared = cost.compareTo(new BigDecimal(this.amount));
            return this.comparison == Comparison.at_most ? compared <= 0 : compared < 0;
        }

        String describe() {
            return (this.comparison == Comparison.at_most ? "at most " : "under ") + "$" + this.amount;
        }
    }

    static final class InvalidInput extends RuntimeException {

        InvalidInput(String message) {
            super(message);
        }
    }

    static LocalDate date(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        }
        catch (DateTimeParseException e) {
            throw new InvalidInput(name + " must be a date like 2026-11-13, not '" + value + "'");
        }
    }

    static CostLimit costLimit(CostLimit limit) {
        if (limit == null) {
            return null;
        }
        if (limit.amount() == null || limit.comparison() == null) {
            throw new InvalidInput("costLimit needs both amount and comparison");
        }
        try {
            BigDecimal amount = new BigDecimal(limit.amount().trim().replace("$", ""));
            if (amount.signum() < 0) {
                throw new InvalidInput("costLimit.amount can't be negative");
            }
            return new CostLimit(amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                    limit.comparison());
        }
        catch (NumberFormatException e) {
            throw new InvalidInput("costLimit.amount must be a number like \"15.00\", not '" + limit.amount() + "'");
        }
    }

    static int quantity(Integer quantity) {
        if (quantity == null) {
            return 1;
        }
        if (quantity < 1) {
            throw new InvalidInput("quantity must be at least 1");
        }
        return quantity;
    }
}
