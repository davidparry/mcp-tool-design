package com.davidparry.tooldesign.app.store;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * Generated identifiers. They restart with every fixture load, so a run's quote and action IDs depend only on what
 * the run did, and two runs of the same task can be compared side by side.
 */
@Component
public class IdSequence {

    private final AtomicInteger exchangeQuotes = new AtomicInteger();

    private final AtomicInteger returnQuotes = new AtomicInteger();

    private final AtomicInteger exchanges = new AtomicInteger();

    private final AtomicInteger returns = new AtomicInteger();

    public IdSequence() {
        reset();
    }

    public String exchangeQuote() {
        return "EQ-" + this.exchangeQuotes.incrementAndGet();
    }

    public String returnQuote() {
        return "RQ-" + this.returnQuotes.incrementAndGet();
    }

    public String exchange() {
        return "EX-" + this.exchanges.incrementAndGet();
    }

    public String returnAction() {
        return "RT-" + this.returns.incrementAndGet();
    }

    public void reset() {
        this.exchangeQuotes.set(5000);
        this.returnQuotes.set(6000);
        this.exchanges.set(7000);
        this.returns.set(8000);
    }
}
