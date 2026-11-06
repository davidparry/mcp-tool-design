package com.davidparry.tooldesign.benchmark.app;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.davidparry.tooldesign.benchmark.config.BenchProperties;

/** The app's benchmark-only admin endpoints: reset, run tags, clock, stock, call log, and state. */
@Component
public class AdminClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final String baseUrl;

    private final String adminKey;

    private final JsonMapper json;

    public AdminClient(BenchProperties properties, JsonMapper json) {
        this.baseUrl = properties.appUrl();
        this.adminKey = properties.adminKey();
        this.json = json;
    }

    public void reset() {
        send("POST", "/admin/reset", null);
    }

    public void startRun(String runId) {
        send("POST", "/admin/runs/" + runId, null);
    }

    public void advanceClock(int minutes) {
        send("POST", "/admin/clock/advance?minutes=" + minutes, null);
    }

    public void setStock(String sku, String warehouseId, int available) {
        send("PUT", "/admin/stock", this.json.writeValueAsString(
                Map.of("sku", sku, "warehouseId", warehouseId, "available", available)));
    }

    public List<Map<String, Object>> calls(String runId) {
        return this.json.readValue(send("GET", "/admin/calls?runId=" + runId, null),
                new TypeReference<List<Map<String, Object>>>() {
                });
    }

    public Map<String, Object> state() {
        return this.json.readValue(send("GET", "/admin/state", null), new TypeReference<Map<String, Object>>() {
        });
    }

    /** The app's OpenAPI document, the contract the endpoint server is generated from. */
    public Map<String, Object> openApi() {
        return this.json.readValue(send("GET", "/v3/api-docs", null), new TypeReference<Map<String, Object>>() {
        });
    }

    public boolean reachable() {
        try {
            HttpResponse<Void> response = this.http.send(HttpRequest.newBuilder(URI.create(this.baseUrl + "/v3/api-docs"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        }
        catch (IOException e) {
            return false;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String send(String method, String path, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(this.baseUrl + path)).timeout(Duration.ofSeconds(30))
                .header("X-Admin-Key", this.adminKey).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException(method + " " + path + " answered " + response.statusCode()
                        + ". Is the app running with --spring.profiles.active=benchmark?");
            }
            return response.body();
        }
        catch (IOException e) {
            throw new IllegalStateException("The app at " + this.baseUrl + " isn't answering", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling the app", e);
        }
    }
}
