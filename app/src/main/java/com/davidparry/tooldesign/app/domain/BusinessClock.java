package com.davidparry.tooldesign.app.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The store's business clock. It stands still at its start instant so every run sees the same date, and only the
 * benchmark profile moves it, to expire quotes on purpose.
 */
public final class BusinessClock extends Clock {

    private final Instant start;

    private final ZoneId zone;

    private final AtomicReference<Instant> now;

    public BusinessClock(OffsetDateTime start, ZoneId zone) {
        this.start = start.toInstant();
        this.zone = zone;
        this.now = new AtomicReference<>(this.start);
    }

    private BusinessClock(Instant start, ZoneId zone, AtomicReference<Instant> now) {
        this.start = start;
        this.zone = zone;
        this.now = now;
    }

    @Override
    public ZoneId getZone() {
        return this.zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new BusinessClock(this.start, zone, this.now);
    }

    @Override
    public Instant instant() {
        return this.now.get();
    }

    public OffsetDateTime now() {
        return OffsetDateTime.now(this);
    }

    public void advance(Duration duration) {
        this.now.updateAndGet(current -> current.plus(duration));
    }

    public void reset() {
        this.now.set(this.start);
    }
}
