package com.davidparry.tooldesign.app.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.davidparry.tooldesign.app.domain.Catalog.DeliveryMethod;
import com.davidparry.tooldesign.app.domain.Catalog.Variant;
import com.davidparry.tooldesign.app.domain.Catalog.Warehouse;
import com.davidparry.tooldesign.app.domain.Money;
import com.davidparry.tooldesign.app.domain.Orders.Order;
import com.davidparry.tooldesign.app.domain.Orders.OrderLine;
import com.davidparry.tooldesign.app.domain.Quotes.Quote;
import com.davidparry.tooldesign.app.service.EligibilityService.Action;
import com.davidparry.tooldesign.app.service.EligibilityService.Eligibility;
import com.davidparry.tooldesign.app.service.QuoteService.ExchangeRequest;
import com.davidparry.tooldesign.app.service.QuoteService.ReturnRequest;
import com.davidparry.tooldesign.app.store.CatalogStore;
import com.davidparry.tooldesign.app.store.InventoryStore;

/**
 * Recomputes every benchmark task's expected outcome through the services, so the task catalog and the business
 * rules can't drift apart. The verifier grades runs against these same files.
 */
class FixtureConsistencyTest extends ServiceTestSupport {

    static final Path TASKS = Path.of("..", "fixtures", "tasks");

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TaskFile(String id, String split, String category, String kind, String prompt, Expected expected) {
    }

    record CostLimit(String amount, String comparison) {
    }

    record ExpectedOption(String warehouseId, String method, String arrivesBy, String additionalCost,
            boolean feasible, List<String> fails) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Expected(String outcome, String order, String item, String sku, String replacementSku, Integer quantity,
            String deliverBy, CostLimit costLimit, List<ExpectedOption> options, String blockingReason,
            String earliestArrival, String cheapestAdditionalCost, String status, String dateKind, String date,
            String itemRefund, String returnShippingFee, String restockingFee, String refund, String action,
            String reasonCode, String windowEndsOn, String estimatedDelivery, List<String> candidates) {
    }

    @Autowired
    OrderLookup lookup;

    @Autowired
    EligibilityService eligibility;

    @Autowired
    QuoteService quotes;

    @Autowired
    CatalogStore catalog;

    @Autowired
    InventoryStore inventory;

    static Stream<Arguments> tasks() throws IOException {
        JsonMapper json = JsonMapper.builder().build();
        List<Arguments> tasks = new ArrayList<>();
        for (String split : List.of("dev", "heldout")) {
            try (Stream<Path> files = Files.list(TASKS.resolve(split))) {
                for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                    TaskFile task = json.readValue(file.toFile(), TaskFile.class);
                    tasks.add(Arguments.of(task.id(), task));
                }
            }
        }
        return tasks.stream();
    }

    @Test
    void theCatalogHasSixteenDevelopmentTasksAndEightHeldOut() throws IOException {
        List<TaskFile> all = tasks().map(arguments -> (TaskFile) arguments.get()[1]).toList();
        assertThat(all).filteredOn(t -> t.split().equals("dev")).hasSize(16);
        assertThat(all).filteredOn(t -> t.split().equals("heldout")).hasSize(8);
        assertThat(all).extracting(TaskFile::category).containsOnly("lookup", "return_preview", "exchange_options",
                "deadline_miss", "price_cap_miss", "unavailable", "ineligible", "ambiguous");
        assertThat(all.stream().filter(t -> t.split().equals("heldout")).map(TaskFile::category).distinct())
                .hasSize(8);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tasks")
    void theExpectedOutcomeIsWhatTheRulesProduce(String id, TaskFile task) {
        Expected e = task.expected();
        switch (task.category()) {
            case "lookup" -> lookup(e);
            case "return_preview" -> returnPreview(e);
            case "exchange_options", "deadline_miss", "price_cap_miss" -> exchangeOptions(e);
            case "unavailable" -> unavailable(e);
            case "ineligible" -> ineligible(e);
            case "ambiguous" -> ambiguous(e);
            default -> throw new AssertionError("Unknown category " + task.category());
        }
    }

    private void lookup(Expected e) {
        Order order = this.lookup.order(e.order());
        assertThat(order.status().name()).isEqualTo(e.status());
        assertThat(e.outcome()).isEqualTo("order_status");
        LocalDate date = e.dateKind() == null ? null
                : e.dateKind().equals("delivered") ? order.deliveredOn() : order.estimatedDelivery();
        assertThat(date == null ? null : date.toString()).isEqualTo(e.date());
    }

    private void returnPreview(Expected e) {
        assertThat(this.lookup.line(e.order(), e.item()).sku()).isEqualTo(e.sku());
        Quote quote = this.quotes.returnQuote(new ReturnRequest(e.order(), e.item(), e.quantity()));
        assertThat(quote.itemRefund()).isEqualTo(Money.usd(e.itemRefund()));
        assertThat(quote.returnShippingFee()).isEqualTo(Money.usd(e.returnShippingFee()));
        assertThat(quote.restockingFee()).isEqualTo(Money.usd(e.restockingFee()));
        assertThat(quote.total()).isEqualTo(Money.usd(e.refund()));
        assertThat(e.outcome()).isEqualTo("refund_preview");
    }

    private void exchangeOptions(Expected e) {
        OrderLine line = this.lookup.line(e.order(), e.item());
        assertThat(line.sku()).isEqualTo(e.sku());
        Variant replacement = this.lookup.variant(e.replacementSku());
        assertThat(replacement.productId()).isEqualTo(this.lookup.variant(line.sku()).productId());

        List<Quote> quoted = new ArrayList<>();
        for (Warehouse warehouse : this.catalog.warehouses()) {
            if (this.inventory.available(e.replacementSku(), warehouse.id()) >= e.quantity()) {
                for (DeliveryMethod method : DeliveryMethod.values()) {
                    quoted.add(this.quotes.exchange(new ExchangeRequest(e.order(), e.item(), e.replacementSku(),
                            e.quantity(), warehouse.id(), method)));
                }
            }
        }
        Set<String> actual = new HashSet<>();
        quoted.forEach(q -> actual.add(key(q.warehouseId(), q.deliveryMethod().name(), q.estimatedArrival().toString(),
                q.total().amount().toPlainString())));
        Set<String> expected = new HashSet<>();
        e.options().forEach(o -> expected.add(key(o.warehouseId(), o.method(), o.arrivesBy(), o.additionalCost())));
        assertThat(actual).as("every quotable option, with its arrival and additional cost").isEqualTo(expected);

        for (ExpectedOption option : e.options()) {
            List<String> fails = new ArrayList<>();
            if (e.deliverBy() != null && LocalDate.parse(option.arrivesBy()).isAfter(LocalDate.parse(e.deliverBy()))) {
                fails.add("deliver_by");
            }
            if (e.costLimit() != null && !withinLimit(Money.usd(option.additionalCost()), e.costLimit())) {
                fails.add("cost_limit");
            }
            assertThat(option.fails()).as("fails for %s %s", option.warehouseId(), option.method()).isEqualTo(fails);
            assertThat(option.feasible()).isEqualTo(fails.isEmpty());
        }
        boolean anyFeasible = e.options().stream().anyMatch(ExpectedOption::feasible);
        assertThat(e.outcome()).isEqualTo(anyFeasible ? "options_available" : "no_matching_option");
        if ("deadline".equals(e.blockingReason())) {
            assertThat(e.options()).allMatch(o -> o.fails().contains("deliver_by"));
            assertThat(e.options().stream().map(ExpectedOption::arrivesBy).min(Comparator.naturalOrder()))
                    .contains(e.earliestArrival());
        }
        if ("cost_limit".equals(e.blockingReason())) {
            assertThat(e.options()).allMatch(o -> o.fails().contains("cost_limit"));
            assertThat(e.options().stream().map(o -> Money.usd(o.additionalCost())).min(Comparator.naturalOrder()))
                    .contains(Money.usd(e.cheapestAdditionalCost()));
        }
    }

    private void unavailable(Expected e) {
        OrderLine line = this.lookup.line(e.order(), e.item());
        assertThat(line.sku()).isEqualTo(e.sku());
        assertThat(this.eligibility.check(Action.EXCHANGE, e.order(), e.item()).eligible()).isTrue();
        assertThat(this.inventory.stock(e.replacementSku())).allMatch(level -> level.available() == 0);
        assertThat(e.outcome()).isEqualTo("no_matching_option");
        assertThat(e.blockingReason()).isEqualTo("out_of_stock");
    }

    private void ineligible(Expected e) {
        assertThat(this.lookup.line(e.order(), e.item()).sku()).isEqualTo(e.sku());
        Eligibility verdict = this.eligibility.check(Action.valueOf(e.action().toUpperCase()), e.order(), e.item());
        assertThat(verdict.eligible()).isFalse();
        assertThat(verdict.reasonCode()).isEqualTo(e.reasonCode());
        if (e.windowEndsOn() != null) {
            assertThat(verdict.windowEndsOn()).hasToString(e.windowEndsOn());
        }
        if (e.estimatedDelivery() != null) {
            assertThat(this.lookup.order(e.order()).estimatedDelivery()).hasToString(e.estimatedDelivery());
        }
    }

    private void ambiguous(Expected e) {
        assertThat(e.candidates()).hasSizeGreaterThanOrEqualTo(2);
        Set<String> categories = new HashSet<>();
        for (String itemId : e.candidates()) {
            categories.add(this.lookup.productOf(this.lookup.line(e.order(), itemId)).category());
        }
        assertThat(categories).as("the candidates are the same kind of product").hasSize(1);
    }

    private static boolean withinLimit(Money cost, CostLimit limit) {
        int comparison = cost.compareTo(Money.usd(limit.amount()));
        return "under".equals(limit.comparison()) ? comparison < 0 : comparison <= 0;
    }

    private static String key(String warehouse, String method, String arrival, String cost) {
        return warehouse + " " + method + " " + arrival + " " + cost;
    }
}
