package com.davidparry.tooldesign.app.admin;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.davidparry.tooldesign.app.calls.CallLog;
import com.davidparry.tooldesign.app.domain.BusinessClock;
import com.davidparry.tooldesign.app.domain.Orders.OrderLine;
import com.davidparry.tooldesign.app.domain.Quotes.CommittedAction;
import com.davidparry.tooldesign.app.domain.Quotes.Quote;
import com.davidparry.tooldesign.app.fixtures.FixtureLoader;
import com.davidparry.tooldesign.app.store.InventoryStore;
import com.davidparry.tooldesign.app.store.InventoryStore.StockLevel;
import com.davidparry.tooldesign.app.store.OrderStore;
import com.davidparry.tooldesign.app.store.QuoteStore;

/**
 * The benchmark's control surface: reset, run tagging, the clock, stock, the call log, and a snapshot of state for
 * the verifier. It exists only under the {@code benchmark} profile and is outside the OpenAPI document.
 */
@Hidden
@Profile("benchmark")
@RestController
@RequestMapping("/admin")
public class AdminController {

    public record StockChange(String sku, String warehouseId, int available) {
    }

    public record LineState(String itemId, String sku, int quantity, int returnedQuantity, int exchangedQuantity) {
    }

    public record State(OffsetDateTime businessTime, List<Quote> quotes, List<CommittedAction> actions,
            List<StockLevel> inventory, List<LineState> lines) {
    }

    private final FixtureLoader fixtures;

    private final BusinessClock clock;

    private final CallLog calls;

    private final InventoryStore inventory;

    private final QuoteStore quotes;

    private final OrderStore orders;

    public AdminController(FixtureLoader fixtures, BusinessClock clock, CallLog calls, InventoryStore inventory,
            QuoteStore quotes, OrderStore orders) {
        this.fixtures = fixtures;
        this.clock = clock;
        this.calls = calls;
        this.inventory = inventory;
        this.quotes = quotes;
        this.orders = orders;
    }

    @PostMapping("/reset")
    Map<String, Object> reset() {
        this.fixtures.load();
        this.clock.reset();
        this.calls.clear();
        return Map.of("status", "reset", "businessTime", this.clock.now().toString());
    }

    @PostMapping("/runs/{runId}")
    Map<String, Object> startRun(@PathVariable String runId) {
        this.calls.startRun(runId);
        return Map.of("runId", runId);
    }

    @PostMapping("/clock/advance")
    Map<String, Object> advance(@RequestParam int minutes) {
        this.clock.advance(Duration.ofMinutes(minutes));
        return Map.of("businessTime", this.clock.now().toString());
    }

    @PutMapping("/stock")
    StockChange stock(@RequestBody StockChange change) {
        this.inventory.set(change.sku(), change.warehouseId(), change.available());
        return new StockChange(change.sku(), change.warehouseId(),
                this.inventory.available(change.sku(), change.warehouseId()));
    }

    @GetMapping("/calls")
    List<CallLog.Entry> calls(@RequestParam(required = false) String runId) {
        return this.calls.entries(runId);
    }

    @GetMapping("/state")
    State state() {
        List<LineState> lines = this.fixtures.orders().stream().flatMap(o -> this.orders.lines(o.reference()).stream())
                .filter(line -> line.returnedQuantity() > 0 || line.exchangedQuantity() > 0).map(AdminController::line)
                .toList();
        return new State(this.clock.now(), this.quotes.quotes(), this.quotes.actions(), this.inventory.all(), lines);
    }

    private static LineState line(OrderLine line) {
        return new LineState(line.itemId(), line.sku(), line.quantity(), line.returnedQuantity(),
                line.exchangedQuantity());
    }
}
