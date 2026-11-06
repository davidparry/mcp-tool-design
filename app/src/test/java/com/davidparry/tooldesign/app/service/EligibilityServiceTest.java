package com.davidparry.tooldesign.app.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.davidparry.tooldesign.app.service.EligibilityService.Action;
import com.davidparry.tooldesign.app.service.EligibilityService.Eligibility;

class EligibilityServiceTest extends ServiceTestSupport {

    @Autowired
    EligibilityService eligibility;

    @Test
    void theDemoHarnessCanBeExchangedUntilNovember27() {
        Eligibility verdict = this.eligibility.check(Action.EXCHANGE, "1042", "1042-1");
        assertThat(verdict.eligible()).isTrue();
        assertThat(verdict.windowEndsOn()).hasToString("2026-11-27");
        assertThat(verdict.maxQuantity()).isEqualTo(1);
    }

    @Test
    void theWindowIncludesItsLastDay() {
        this.clock.advance(Duration.ofDays(21));
        assertThat(this.eligibility.check(Action.EXCHANGE, "1042", "1042-1").eligible()).isTrue();
        this.clock.advance(Duration.ofDays(1));
        Eligibility verdict = this.eligibility.check(Action.EXCHANGE, "1042", "1042-1");
        assertThat(verdict.eligible()).isFalse();
        assertThat(verdict.reasonCode()).isEqualTo("window_closed");
    }

    @Test
    void aLeashDeliveredInSeptemberIsPastItsWindow() {
        Eligibility verdict = this.eligibility.check(Action.RETURN, "1012", "1012-1");
        assertThat(verdict.eligible()).isFalse();
        assertThat(verdict.reasonCode()).isEqualTo("window_closed");
        assertThat(verdict.windowEndsOn()).hasToString("2026-10-20");
        assertThat(verdict.reason()).contains("2026-10-20");
    }

    @Test
    void treatsCantBeReturned() {
        Eligibility verdict = this.eligibility.check(Action.RETURN, "1026", "1026-1");
        assertThat(verdict.reasonCode()).isEqualTo("not_returnable");
        assertThat(verdict.policy().returnCategory().name()).isEqualTo("CONSUMABLE");
    }

    @Test
    void anOrderStillInTransitCantBeExchanged() {
        Eligibility verdict = this.eligibility.check(Action.EXCHANGE, "1039", "1039-1");
        assertThat(verdict.reasonCode()).isEqualTo("not_delivered");
        assertThat(verdict.reason()).contains("2026-11-10");
    }

    @Test
    void aCancelledOrderHasNothingToReturn() {
        assertThat(this.eligibility.check(Action.RETURN, "1028", "1028-1").reasonCode()).isEqualTo("order_cancelled");
    }

    @Test
    void gpsCollarsHaveAFifteenDayWindow() {
        Eligibility verdict = this.eligibility.check(Action.EXCHANGE, "1029", "1029-1");
        assertThat(verdict.eligible()).isTrue();
        assertThat(verdict.windowEndsOn()).hasToString("2026-11-10");
    }

    @Test
    void theQuantityLeftIsWhatHasntBeenReturned() {
        assertThat(this.eligibility.check(Action.RETURN, "1023", "1023-1").maxQuantity()).isEqualTo(3);
    }
}
