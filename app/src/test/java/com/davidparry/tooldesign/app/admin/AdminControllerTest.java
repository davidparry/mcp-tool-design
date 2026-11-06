package com.davidparry.tooldesign.app.admin;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("benchmark")
class AdminControllerTest {

    static final String ADMIN = "admin-dev-key";

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void reset() throws Exception {
        this.mvc.perform(post("/admin/reset").header("X-Admin-Key", ADMIN)).andExpect(status().isOk());
    }

    @Test
    void theAdminEndpointsNeedTheAdminKey() throws Exception {
        this.mvc.perform(post("/admin/reset")).andExpect(status().isUnauthorized());
        this.mvc.perform(post("/admin/reset").header("X-Admin-Key", "support-agent-dev-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void everyApiCallIsLabeledWithTheActiveRun() throws Exception {
        this.mvc.perform(post("/admin/runs/run-1").header("X-Admin-Key", ADMIN)).andExpect(status().isOk());
        this.mvc.perform(get("/api/orders/1042").header("X-Api-Key", "support-agent-dev-key"));
        this.mvc.perform(get("/api/orders/1042/items").header("X-Api-Key", "support-agent-dev-key"));
        this.mvc.perform(post("/admin/runs/run-2").header("X-Admin-Key", ADMIN));
        this.mvc.perform(get("/api/orders/9999").header("X-Api-Key", "support-agent-dev-key"));
        this.mvc.perform(get("/admin/calls").param("runId", "run-1").header("X-Admin-Key", ADMIN))
                .andExpect(jsonPath("$", hasSize(2))).andExpect(jsonPath("$[0].caller").value("support-agent"))
                .andExpect(jsonPath("$[1].path").value("/api/orders/1042/items"));
        this.mvc.perform(get("/admin/calls").param("runId", "run-2").header("X-Admin-Key", ADMIN))
                .andExpect(jsonPath("$[0].status").value(404));
    }

    @Test
    void stockAndTheClockCanBeMovedAndResetPutsThemBack() throws Exception {
        this.mvc.perform(put("/admin/stock").header("X-Admin-Key", ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"TRB-L-BLU\",\"warehouseId\":\"WH-CMH\",\"available\":0}"))
                .andExpect(jsonPath("$.available").value(0));
        this.mvc.perform(post("/admin/clock/advance").param("minutes", "31").header("X-Admin-Key", ADMIN))
                .andExpect(jsonPath("$.businessTime").value("2026-11-06T09:31-06:00"));
        this.mvc.perform(post("/admin/reset").header("X-Admin-Key", ADMIN))
                .andExpect(jsonPath("$.businessTime").value("2026-11-06T09:00-06:00"));
        this.mvc.perform(get("/admin/state").header("X-Admin-Key", ADMIN))
                .andExpect(jsonPath("$.actions", hasSize(0)))
                .andExpect(jsonPath("$.inventory[?(@.sku == 'TRB-L-BLU' && @.warehouseId == 'WH-CMH')].available")
                        .value(2));
    }
}
