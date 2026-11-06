package com.davidparry.tooldesign.workflow;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real order app, run from its jar as its own process under the benchmark profile, the way the benchmark runs
 * it. The workflow server only ever reaches it over HTTP.
 */
final class AppProcess {

    static final Path JAR = Path.of("..", "app", "target", "app-0.1.0-SNAPSHOT.jar");

    private static final String ADMIN_KEY = "admin-dev-key";

    private final HttpClient http = HttpClient.newHttpClient();

    private final JsonMapper json = JsonMapper.builder().build();

    private final Process process;

    private final int port;

    AppProcess() throws IOException, InterruptedException {
        try (ServerSocket socket = new ServerSocket(0)) {
            this.port = socket.getLocalPort();
        }
        this.process = new ProcessBuilder("java", "-jar", JAR.toString(), "--server.port=" + this.port,
                "--spring.profiles.active=benchmark").redirectErrorStream(true)
                .redirectOutput(Path.of("target", "app-process.log").toFile()).start();
        Instant deadline = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(deadline)) {
            try {
                if (get("/v3/api-docs").statusCode() == 200) {
                    return;
                }
            }
            catch (IOException e) {
                Thread.sleep(250);
            }
        }
        stop();
        throw new IllegalStateException("The app didn't start; see target/app-process.log");
    }

    static boolean jarExists() {
        return Files.exists(JAR);
    }

    String baseUrl() {
        return "http://localhost:" + this.port;
    }

    void stop() {
        this.process.destroy();
    }

    void reset() throws IOException, InterruptedException {
        admin("POST", "/admin/reset", null);
    }

    void startRun(String runId) throws IOException, InterruptedException {
        admin("POST", "/admin/runs/" + runId, null);
    }

    void advanceClock(int minutes) throws IOException, InterruptedException {
        admin("POST", "/admin/clock/advance?minutes=" + minutes, null);
    }

    List<Map<String, Object>> calls(String runId) throws IOException, InterruptedException {
        return this.json.readValue(admin("GET", "/admin/calls?runId=" + runId, null),
                new TypeReference<List<Map<String, Object>>>() {
                });
    }

    Map<String, Object> state() throws IOException, InterruptedException {
        return this.json.readValue(admin("GET", "/admin/state", null), new TypeReference<Map<String, Object>>() {
        });
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return this.http.send(HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(Duration.ofSeconds(5))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private String admin(String method, String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path)).header("X-Admin-Key", ADMIN_KEY)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException(method + " " + path + " answered " + response.statusCode());
        }
        return response.body();
    }
}
