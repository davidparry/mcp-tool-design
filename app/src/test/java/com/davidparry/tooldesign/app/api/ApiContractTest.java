package com.davidparry.tooldesign.app.api;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.davidparry.tooldesign.app.domain.BusinessClock;
import com.davidparry.tooldesign.app.fixtures.FixtureLoader;

/** The error contracts and wire formats the frontend and both MCP servers depend on. */
@SpringBootTest
@AutoConfigureMockMvc
class ApiContractTest {

    static final String KEY = "support-agent-dev-key";

    static final String COLUMBUS_GROUND = """
            {"orderReference":"1042","itemId":"1042-1","replacementSku":"TRB-L-BLU","quantity":1,
             "warehouseId":"WH-CMH","deliveryMethod":"GROUND"}
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    FixtureLoader fixtures;

    @Autowired
    BusinessClock clock;

    @BeforeEach
    void freshStore() {
        this.fixtures.load();
        this.clock.reset();
    }

    @Test
    void theApiNeedsAKey() throws Exception {
        this.mvc.perform(get("/api/orders/1042")).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("unauthorized"));
        this.mvc.perform(get("/api/orders/1042").header("X-Api-Key", "wrong")).andExpect(status().isUnauthorized());
    }

    @Test
    void anOrderHasItsDatesAndTotals() throws Exception {
        this.mvc.perform(authorized(get("/api/orders/1042"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.deliveredOn").value("2026-10-28"))
                .andExpect(jsonPath("$.shipTo.postalCode").value("78704"))
                .andExpect(jsonPath("$.subtotal.amount").value("43.98"))
                .andExpect(jsonPath("$.total.currency").value("USD"));
    }

    @Test
    void searchFindsACustomersOrdersNewestFirst() throws Exception {
        this.mvc.perform(authorized(get("/api/orders").param("query", "priya"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].reference").value("1031"))
                .andExpect(jsonPath("$.items[1].reference").value("1014"));
        this.mvc.perform(authorized(get("/api/orders").param("size", "5"))).andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.totalPages").value(8));
    }

    @Test
    void aMissingOrderIsAProblemWithACode() throws Exception {
        this.mvc.perform(authorized(get("/api/orders/9999"))).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("order_not_found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void anExchangeQuoteCarriesTheAuthoritativeTotal() throws Exception {
        this.mvc.perform(authorized(post("/api/exchange-quotes")).contentType(MediaType.APPLICATION_JSON)
                .content(COLUMBUS_GROUND)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.quoteId").value("EQ-5001"))
                .andExpect(jsonPath("$.additionalCost.amount").value("11.99"))
                .andExpect(jsonPath("$.additionalCost.*", hasSize(2)))
                .andExpect(jsonPath("$.estimatedArrival").value("2026-11-12"))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-06T09:30:00-06:00"));
    }

    @Test
    void stockThatIsntThereIsAConflict() throws Exception {
        this.mvc.perform(authorized(post("/api/exchange-quotes")).contentType(MediaType.APPLICATION_JSON)
                .content(COLUMBUS_GROUND.replace("WH-CMH", "WH-DAL"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stock_unavailable"))
                .andExpect(jsonPath("$.available").value(0));
    }

    @Test
    void anIneligibleItemIsAConflictWithItsReason() throws Exception {
        this.mvc.perform(authorized(post("/api/return-quotes")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderReference\":\"1012\",\"itemId\":\"1012-1\",\"quantity\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("not_eligible"))
                .andExpect(jsonPath("$.reasonCode").value("window_closed"))
                .andExpect(jsonPath("$.windowEndsOn").value("2026-10-20"));
    }

    @Test
    void anInvalidBodyListsItsFields() throws Exception {
        this.mvc.perform(authorized(post("/api/exchange-quotes")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderReference\":\"1042\"}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors", hasSize(5)));
        this.mvc.perform(authorized(post("/api/exchange-quotes")).contentType(MediaType.APPLICATION_JSON)
                .content(COLUMBUS_GROUND.replace("GROUND", "DRONE"))).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }

    @Test
    void anExpiredQuoteIsRefusedAndNothingIsCommitted() throws Exception {
        this.mvc.perform(authorized(post("/api/exchange-quotes")).contentType(MediaType.APPLICATION_JSON)
                .content(COLUMBUS_GROUND)).andExpect(status().isCreated());
        this.clock.advance(Duration.ofMinutes(31));
        this.mvc.perform(authorized(post("/api/exchanges")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quoteId\":\"EQ-5001\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("quote_expired"))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-06T09:30:00-06:00"));
        this.mvc.perform(authorized(get("/api/inventory").param("sku", "TRB-L-BLU")))
                .andExpect(jsonPath("$.totalAvailable").value(6));
    }

    @Test
    void resubmittingAQuoteReturnsTheOriginalExchange() throws Exception {
        this.mvc.perform(authorized(post("/api/exchange-quotes")).contentType(MediaType.APPLICATION_JSON)
                .content(COLUMBUS_GROUND)).andExpect(status().isCreated());
        this.mvc.perform(authorized(post("/api/exchanges")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quoteId\":\"EQ-5001\"}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.exchangeId").value("EX-7001"));
        this.mvc.perform(authorized(post("/api/exchanges")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"quoteId\":\"EQ-5001\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.exchangeId").value("EX-7001"));
        this.mvc.perform(authorized(get("/api/inventory").param("sku", "TRB-L-BLU")))
                .andExpect(jsonPath("$.totalAvailable").value(5));
    }

    @Test
    void deliveryOptionsLeaveOnTheBusinessDate() throws Exception {
        this.mvc.perform(authorized(get("/api/delivery-options").param("warehouseId", "WH-RNO")
                .param("postalCode", "78704"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.shipDate").value("2026-11-06"))
                .andExpect(jsonPath("$.options[0].method").value("GROUND"))
                .andExpect(jsonPath("$.options[0].estimatedArrival").value("2026-11-16"))
                .andExpect(jsonPath("$.options[1].charge.amount").value("12.99"));
        this.mvc.perform(authorized(get("/api/delivery-options").param("warehouseId", "WH-RNO")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("validation_failed"));
    }

    private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
        return request.header("X-Api-Key", KEY);
    }
}
