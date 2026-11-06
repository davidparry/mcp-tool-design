package com.davidparry.tooldesign.endpoint;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** A stand-in for the order API: it serves the real OpenAPI document and canned responses, and records requests. */
final class StubApi {

    record Request(String method, String path, String query, String apiKey, String body) {
    }

    /** Odd spacing and key order on purpose: a pass-through must not reformat it. */
    static final String ORDER_BODY = "{\"reference\":\"1042\",  \"status\":\"DELIVERED\",\"deliveredOn\":\"2026-10-28\"}";

    static final String EXPIRED_BODY = "{\"detail\":\"Quote EQ-5001 expired\",\"status\":409,\"title\":\"Conflict\","
            + "\"code\":\"quote_expired\"}";

    final List<Request> requests = new CopyOnWriteArrayList<>();

    private final HttpServer server;

    StubApi() {
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + this.server.getAddress().getPort();
    }

    void stop() {
        this.server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (path.equals("/v3/api-docs")) {
            try (InputStream in = StubApi.class.getResourceAsStream("/openapi/order-support-api.json")) {
                respond(exchange, 200, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            return;
        }
        this.requests.add(new Request(exchange.getRequestMethod(), path, exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders().getFirst("X-Api-Key"), body));
        switch (exchange.getRequestMethod() + " " + path) {
            case "GET /api/orders/1042" -> respond(exchange, 200, ORDER_BODY);
            case "POST /api/exchanges" -> respond(exchange, 409, EXPIRED_BODY);
            case "POST /api/exchange-quotes" -> respond(exchange, 201, "{\"quoteId\":\"EQ-5001\"}");
            case "GET /api/orders" -> respond(exchange, 200, "{\"items\":[]}");
            default -> respond(exchange, 404, "{\"status\":404,\"code\":\"not_found\"}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
