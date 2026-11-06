package com.davidparry.tooldesign.app.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.davidparry.tooldesign.app.domain.Catalog.DeliveryMethod;
import com.davidparry.tooldesign.app.domain.Catalog.Region;
import com.davidparry.tooldesign.app.domain.Money;
import com.davidparry.tooldesign.app.service.DeliveryEstimator.DeliveryOption;

class DeliveryEstimatorTest extends ServiceTestSupport {

    @Autowired
    DeliveryEstimator delivery;

    @Test
    void businessDaysSkipTheWeekend() {
        LocalDate friday = LocalDate.parse("2026-11-06");
        assertThat(DeliveryEstimator.addBusinessDays(friday, 1)).hasToString("2026-11-09");
        assertThat(DeliveryEstimator.addBusinessDays(friday, 4)).hasToString("2026-11-12");
        assertThat(DeliveryEstimator.addBusinessDays(friday, 6)).hasToString("2026-11-16");
    }

    @Test
    void columbusToAustinIsFourDaysByGround() {
        DeliveryOption ground = this.delivery.option("WH-CMH", "78704", DeliveryMethod.GROUND);
        assertThat(ground.region()).isEqualTo(Region.SOUTH);
        assertThat(ground.shipDate()).hasToString("2026-11-06");
        assertThat(ground.estimatedArrival()).hasToString("2026-11-12");
        assertThat(ground.charge()).isEqualTo(Money.usd("6.99"));
    }

    @Test
    void everyWarehouseOffersGroundAndExpress() {
        assertThat(this.delivery.options("WH-RNO", "78704")).extracting(DeliveryOption::method)
                .containsExactly(DeliveryMethod.GROUND, DeliveryMethod.EXPRESS);
    }

    @Test
    void aWeekendBusinessDateShipsMonday() {
        this.clock.advance(Duration.ofDays(1));
        assertThat(this.delivery.shipDate()).hasToString("2026-11-09");
    }

    @Test
    void anUnroutablePostalCodeIsRefused() {
        assertThatThrownBy(() -> this.delivery.options("WH-DAL", "ABC")).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("validation_failed");
        assertThatThrownBy(() -> this.delivery.options("WH-XXX", "78704")).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("warehouse_not_found");
    }
}
