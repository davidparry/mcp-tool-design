package com.davidparry.tooldesign.app.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;

import com.davidparry.tooldesign.app.domain.BusinessClock;
import com.davidparry.tooldesign.app.domain.Catalog.DeliveryMethod;
import com.davidparry.tooldesign.app.domain.Catalog.DeliveryRate;
import com.davidparry.tooldesign.app.domain.Catalog.Region;
import com.davidparry.tooldesign.app.domain.Money;
import com.davidparry.tooldesign.app.store.CatalogStore;

/**
 * Delivery dates and charges from a warehouse to a postal code. A shipment leaves on the business date, or the
 * next weekday if that falls on a weekend, and arrives after the rate's business days, Monday to Friday, with no
 * holiday calendar.
 */
@Service
public class DeliveryEstimator {

    public record DeliveryOption(String warehouseId, Region region, DeliveryMethod method, int businessDays,
            LocalDate shipDate, LocalDate estimatedArrival, Money charge) {
    }

    private final CatalogStore catalog;

    private final BusinessClock clock;

    public DeliveryEstimator(CatalogStore catalog, BusinessClock clock) {
        this.catalog = catalog;
        this.clock = clock;
    }

    public Region region(String postalCode) {
        if (postalCode == null || postalCode.isBlank() || !Character.isDigit(postalCode.trim().charAt(0))) {
            throw BusinessException.invalid("A postal code has to start with a digit, not '" + postalCode + "'");
        }
        return this.catalog.regionForPostalPrefix(postalCode.trim().charAt(0))
                .orElseThrow(() -> BusinessException.invalid("No delivery zone covers postal code " + postalCode));
    }

    /** Every method from the warehouse to the postal code, slowest first. */
    public List<DeliveryOption> options(String warehouseId, String postalCode) {
        this.catalog.warehouse(warehouseId).orElseThrow(
                () -> BusinessException.notFound("warehouse_not_found", "There is no warehouse " + warehouseId));
        Region region = region(postalCode);
        LocalDate shipDate = shipDate();
        return this.catalog.rates(warehouseId, region).stream().map(rate -> option(rate, shipDate)).toList();
    }

    public DeliveryOption option(String warehouseId, String postalCode, DeliveryMethod method) {
        return options(warehouseId, postalCode).stream().filter(option -> option.method() == method).findFirst()
                .orElseThrow(() -> BusinessException.invalid(
                        "Warehouse " + warehouseId + " has no " + method + " delivery to " + postalCode));
    }

    public LocalDate shipDate() {
        LocalDate date = LocalDate.now(this.clock);
        while (isWeekend(date)) {
            date = date.plusDays(1);
        }
        return date;
    }

    static LocalDate addBusinessDays(LocalDate start, int businessDays) {
        LocalDate date = start;
        int added = 0;
        while (added < businessDays) {
            date = date.plusDays(1);
            if (!isWeekend(date)) {
                added++;
            }
        }
        return date;
    }

    private static DeliveryOption option(DeliveryRate rate, LocalDate shipDate) {
        return new DeliveryOption(rate.warehouseId(), rate.region(), rate.method(), rate.businessDays(), shipDate,
                addBusinessDays(shipDate, rate.businessDays()), rate.charge());
    }

    private static boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }
}
