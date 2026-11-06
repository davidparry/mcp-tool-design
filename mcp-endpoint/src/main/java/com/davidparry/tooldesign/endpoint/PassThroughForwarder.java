package com.davidparry.tooldesign.endpoint;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import tools.jackson.databind.json.JsonMapper;

import com.davidparry.tooldesign.endpoint.OpenApiToolGenerator.Operation;

/**
 * Turns a tool call into the HTTP request its operation describes and hands back the raw response body. Path
 * parameters fill the template, query parameters and the body pass through as the model sent them, and a 4xx or 5xx
 * response comes back with {@code isError} set and the API's problem detail as the text.
 */
public class PassThroughForwarder {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final String baseUrl;

    private final String apiKey;

    private final JsonMapper json;

    public PassThroughForwarder(String baseUrl, String apiKey, JsonMapper json) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.json = json;
    }

    public CallToolResult forward(Operation op, Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        String path = op.path();
        for (String name : op.pathParams()) {
            Object value = args.get(name);
            if (value == null) {
                return error("Missing required argument '" + name + "'");
            }
            path = path.replace("{" + name + "}", encode(scalar(value)));
        }
        StringJoiner query = new StringJoiner("&", "?", "").setEmptyValue("");
        for (String name : op.queryParams()) {
            Object value = args.get(name);
            if (value != null) {
                query.add(encode(name) + "=" + encode(scalar(value)));
            }
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(this.baseUrl + path + query))
                .timeout(Duration.ofSeconds(30)).header("X-Api-Key", this.apiKey).header("Accept", "application/json");
        if (op.hasBody()) {
            Map<String, Object> body = new LinkedHashMap<>(args);
            op.pathParams().forEach(body::remove);
            op.queryParams().forEach(body::remove);
            request.header("Content-Type", "application/json")
                    .method(op.method(), HttpRequest.BodyPublishers.ofString(this.json.writeValueAsString(body)));
        }
        else {
            request.method(op.method(), HttpRequest.BodyPublishers.noBody());
        }
        try {
            HttpResponse<String> response = this.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return CallToolResult.builder().addTextContent(response.body()).isError(response.statusCode() >= 400)
                    .build();
        }
        catch (IOException e) {
            return error("The API at " + this.baseUrl + " didn't answer: " + e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error("Interrupted while calling the API");
        }
    }

    /** Numbers the model sends as JSON arrive as doubles; a whole number goes into the URL without a decimal point. */
    private static String scalar(Object value) {
        if (value instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
            return Long.toString(d.longValue());
        }
        return String.valueOf(value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }
}
