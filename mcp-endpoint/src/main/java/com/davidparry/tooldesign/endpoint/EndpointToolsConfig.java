package com.davidparry.tooldesign.endpoint;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.davidparry.tooldesign.endpoint.OpenApiToolGenerator.Operation;

/** Reads the API's OpenAPI document at startup and registers one pass-through tool per operation. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EndpointProperties.class)
class EndpointToolsConfig {

    private static final Logger log = LoggerFactory.getLogger(EndpointToolsConfig.class);

    @Bean
    List<SyncToolSpecification> endpointTools(EndpointProperties properties, JsonMapper json) {
        Map<String, Object> document = fetchDocument(properties.api(), json);
        List<Operation> operations = OpenApiToolGenerator.generate(document);
        checkCatalog(properties.catalog(), operations);
        PassThroughForwarder forwarder = new PassThroughForwarder(properties.api().baseUrl(), properties.api().key(),
                json);
        log.info("Generated {} tools from {}/v3/api-docs", operations.size(), properties.api().baseUrl());
        return operations.stream()
                .map(op -> new SyncToolSpecification(op.tool(),
                        (exchange, request) -> forwarder.forward(op, request.arguments())))
                .toList();
    }

    private static void checkCatalog(EndpointProperties.Catalog catalog, List<Operation> operations) {
        if (catalog.mode() == EndpointProperties.Mode.OFF) {
            return;
        }
        List<Map<String, Object>> described = ToolCatalog.describe(operations.stream().map(Operation::tool).toList());
        Path snapshot = Path.of(catalog.snapshot());
        String generated = ToolCatalog.sha256(described);
        if (catalog.mode() == EndpointProperties.Mode.WRITE) {
            ToolCatalog.write(snapshot, described);
            log.info("Wrote the catalog snapshot {} (sha256 {})", snapshot.toAbsolutePath(), generated);
            return;
        }
        String frozen = ToolCatalog.snapshotHash(snapshot);
        if (!generated.equals(frozen)) {
            throw new IllegalStateException("The generated catalog (sha256 " + generated + ") doesn't match the "
                    + "snapshot " + snapshot.toAbsolutePath() + " (sha256 " + frozen + "). The API's OpenAPI document "
                    + "changed; refresh the snapshot with endpoint.catalog.mode=write if that was intended.");
        }
        log.info("The generated catalog matches the snapshot (sha256 {})", generated);
    }

    private static Map<String, Object> fetchDocument(EndpointProperties.Api api, JsonMapper json) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(api.baseUrl() + "/v3/api-docs"))
                .timeout(Duration.ofSeconds(10)).GET().build();
        Instant deadline = Instant.now().plus(api.startupWait());
        while (true) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return json.readValue(response.body(), new TypeReference<Map<String, Object>>() {
                    });
                }
                log.info("{} answered {}; waiting for the API", request.uri(), response.statusCode());
            }
            catch (IOException e) {
                log.info("Waiting for the API at {} ({})", api.baseUrl(), e.getClass().getSimpleName());
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the API", e);
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("No OpenAPI document at " + request.uri() + " after "
                        + api.startupWait().toSeconds() + "s");
            }
            try {
                Thread.sleep(1000);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the API", e);
            }
        }
    }
}
