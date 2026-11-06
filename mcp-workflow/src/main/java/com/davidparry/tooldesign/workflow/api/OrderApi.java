package com.davidparry.tooldesign.workflow.api;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.davidparry.tooldesign.workflow.WorkflowProperties;
import com.davidparry.tooldesign.workflow.api.ApiTypes.DeliveryOptions;
import com.davidparry.tooldesign.workflow.api.ApiTypes.Eligibility;
import com.davidparry.tooldesign.workflow.api.ApiTypes.ExchangeQuote;
import com.davidparry.tooldesign.workflow.api.ApiTypes.ExchangeQuoteRequest;
import com.davidparry.tooldesign.workflow.api.ApiTypes.ExchangeView;
import com.davidparry.tooldesign.workflow.api.ApiTypes.Inventory;
import com.davidparry.tooldesign.workflow.api.ApiTypes.OrderDetail;
import com.davidparry.tooldesign.workflow.api.ApiTypes.OrderItems;
import com.davidparry.tooldesign.workflow.api.ApiTypes.OrderSearchPage;
import com.davidparry.tooldesign.workflow.api.ApiTypes.ProductVariants;
import com.davidparry.tooldesign.workflow.api.ApiTypes.ReturnQuote;
import com.davidparry.tooldesign.workflow.api.ApiTypes.ReturnQuoteRequest;
import com.davidparry.tooldesign.workflow.api.ApiTypes.ReturnView;
import com.davidparry.tooldesign.workflow.api.ApiTypes.SubmitQuoteRequest;

/** The order API, called the same way the frontend calls it. Every refusal becomes an {@link ApiProblem}. */
@Component
public class OrderApi {

    /** A submission and whether this call created it (201) or found it already submitted (200). */
    public record Submitted<T>(T body, boolean created) {
    }

    private final RestClient rest;

    private final JsonMapper json;

    public OrderApi(RestClient.Builder builder, WorkflowProperties properties, JsonMapper json) {
        this.json = json;
        this.rest = builder.baseUrl(properties.api().baseUrl())
                .defaultHeader("X-Api-Key", properties.api().key())
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw problem(response);
                })
                .build();
    }

    public OrderSearchPage searchOrders(String query, String status, int size) {
        return this.rest.get().uri(u -> {
            u.path("/api/orders").queryParam("query", query).queryParam("size", size);
            if (status != null) {
                u.queryParam("status", status);
            }
            return u.build();
        }).retrieve().body(OrderSearchPage.class);
    }

    public OrderDetail order(String reference) {
        return this.rest.get().uri("/api/orders/{reference}", reference).retrieve().body(OrderDetail.class);
    }

    public OrderItems items(String reference) {
        return this.rest.get().uri("/api/orders/{reference}/items", reference).retrieve().body(OrderItems.class);
    }

    public Eligibility exchangeEligibility(String reference, String itemId) {
        return this.rest.get().uri("/api/orders/{reference}/items/{itemId}/exchange-eligibility", reference, itemId)
                .retrieve().body(Eligibility.class);
    }

    public Eligibility returnEligibility(String reference, String itemId) {
        return this.rest.get().uri("/api/orders/{reference}/items/{itemId}/return-eligibility", reference, itemId)
                .retrieve().body(Eligibility.class);
    }

    public ProductVariants variants(String productId) {
        return this.rest.get().uri("/api/products/{productId}/variants", productId).retrieve()
                .body(ProductVariants.class);
    }

    public Inventory inventory(String sku) {
        return this.rest.get().uri(u -> u.path("/api/inventory").queryParam("sku", sku).build()).retrieve()
                .body(Inventory.class);
    }

    public DeliveryOptions deliveryOptions(String warehouseId, String postalCode) {
        return this.rest.get()
                .uri(u -> u.path("/api/delivery-options").queryParam("warehouseId", warehouseId)
                        .queryParam("postalCode", postalCode).build())
                .retrieve().body(DeliveryOptions.class);
    }

    public ExchangeQuote createExchangeQuote(ExchangeQuoteRequest request) {
        return this.rest.post().uri("/api/exchange-quotes").body(request).retrieve().body(ExchangeQuote.class);
    }

    public ReturnQuote createReturnQuote(ReturnQuoteRequest request) {
        return this.rest.post().uri("/api/return-quotes").body(request).retrieve().body(ReturnQuote.class);
    }

    public Submitted<ExchangeView> submitExchange(String quoteId) {
        ResponseEntity<ExchangeView> response = this.rest.post().uri("/api/exchanges")
                .body(new SubmitQuoteRequest(quoteId)).retrieve().toEntity(ExchangeView.class);
        return new Submitted<>(response.getBody(), response.getStatusCode().value() == 201);
    }

    public Submitted<ReturnView> submitReturn(String quoteId) {
        ResponseEntity<ReturnView> response = this.rest.post().uri("/api/returns")
                .body(new SubmitQuoteRequest(quoteId)).retrieve().toEntity(ReturnView.class);
        return new Submitted<>(response.getBody(), response.getStatusCode().value() == 201);
    }

    private ApiProblem problem(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        Map<String, Object> body;
        try {
            body = new HashMap<>(this.json.readValue(response.getBody(), new TypeReference<Map<String, Object>>() {
            }));
        }
        catch (RuntimeException e) {
            body = new HashMap<>();
        }
        String code = body.get("code") instanceof String c ? c : "http_" + status;
        String detail = body.get("detail") instanceof String d ? d : "The order API answered " + status;
        return new ApiProblem(status, code, detail, body);
    }
}
