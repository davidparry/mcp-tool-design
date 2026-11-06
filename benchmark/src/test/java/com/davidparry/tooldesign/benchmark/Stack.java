package com.davidparry.tooldesign.benchmark;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The app and both MCP servers, each run from its jar as its own process on a free port, the way the benchmark runs
 * them. Tests skip when the jars haven't been built.
 */
public final class Stack implements AutoCloseable {

    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    static final Path APP = ROOT.resolve("app/target/app-0.1.0-SNAPSHOT.jar");

    static final Path ENDPOINT = ROOT.resolve("mcp-endpoint/target/mcp-endpoint-0.1.0-SNAPSHOT.jar");

    static final Path WORKFLOW = ROOT.resolve("mcp-workflow/target/mcp-workflow-0.1.0-SNAPSHOT.jar");

    private final List<Process> processes = new ArrayList<>();

    private final int appPort;

    private final int endpointPort;

    private final int workflowPort;

    public Stack() throws IOException, InterruptedException {
        this.appPort = freePort();
        this.endpointPort = freePort();
        this.workflowPort = freePort();
        String java = ProcessHandle.current().info().command().orElse("java");
        try {
            start("app", java, "-jar", APP.toString(), "--server.port=" + this.appPort,
                    "--spring.profiles.active=benchmark");
            waitForHttp(appUrl() + "/v3/api-docs");
            start("endpoint", java, "-jar", ENDPOINT.toString(), "--server.port=" + this.endpointPort,
                    "--endpoint.api.base-url=" + appUrl());
            start("workflow", java, "-jar", WORKFLOW.toString(), "--server.port=" + this.workflowPort,
                    "--workflow.api.base-url=" + appUrl());
            waitForPort(this.endpointPort);
            waitForPort(this.workflowPort);
        }
        catch (IOException | InterruptedException | RuntimeException e) {
            close();
            throw e;
        }
    }

    public static boolean built() {
        return Files.exists(APP) && Files.exists(ENDPOINT) && Files.exists(WORKFLOW);
    }

    public String appUrl() {
        return "http://localhost:" + this.appPort;
    }

    public String endpointUrl() {
        return "http://localhost:" + this.endpointPort;
    }

    public String workflowUrl() {
        return "http://localhost:" + this.workflowPort;
    }

    @Override
    public void close() {
        this.processes.forEach(Process::destroy);
    }

    private void start(String name, String... command) throws IOException {
        Files.createDirectories(Path.of("target"));
        this.processes.add(new ProcessBuilder(command).directory(ROOT.toFile()).redirectErrorStream(true)
                .redirectOutput(Path.of("target", name + "-process.log").toFile()).start());
    }

    private static void waitForHttp(String url) throws InterruptedException {
        HttpClient http = HttpClient.newHttpClient();
        Instant deadline = Instant.now().plusSeconds(90);
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return;
                }
            }
            catch (IOException e) {
                // not up yet
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException(url + " didn't come up; see target/*-process.log");
    }

    private static void waitForPort(int port) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(90);
        while (Instant.now().isBefore(deadline)) {
            try (Socket socket = new Socket("localhost", port)) {
                return;
            }
            catch (IOException e) {
                Thread.sleep(250);
            }
        }
        throw new IllegalStateException("Nothing is listening on " + port + "; see target/*-process.log");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
