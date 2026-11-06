package com.davidparry.tooldesign.benchmark.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

/** One MCP session against one arm's server. The runner opens a new one for every run. */
public final class McpSession implements AutoCloseable {

    private final McpSyncClient client;

    private final JsonMapper json;

    private final String protocolVersion;

    private McpSession(McpSyncClient client, JsonMapper json, String protocolVersion) {
        this.client = client;
        this.json = json;
        this.protocolVersion = protocolVersion;
    }

    public static McpSession open(String baseUrl, JsonMapper json) {
        McpSyncClient client = McpClient
                .sync(HttpClientStreamableHttpTransport.builder(baseUrl).endpoint("/mcp").build())
                .clientInfo(new McpSchema.Implementation("tool-design-benchmark", "1.0.0"))
                .requestTimeout(Duration.ofSeconds(120)).initializationTimeout(Duration.ofSeconds(30)).build();
        McpSchema.InitializeResult init = client.initialize();
        return new McpSession(client, json, init.protocolVersion());
    }

    public String protocolVersion() {
        return this.protocolVersion;
    }

    public List<McpSchema.Tool> listTools() {
        List<McpSchema.Tool> tools = new ArrayList<>();
        McpSchema.ListToolsResult page = this.client.listTools();
        tools.addAll(page.tools());
        while (page.nextCursor() != null) {
            page = this.client.listTools(page.nextCursor());
            tools.addAll(page.tools());
        }
        return tools;
    }

    /**
     * Calls a tool and returns what the model will see: the result's text blocks, or the compact JSON of its
     * structured content when there are none. Protocol failures come back as an error result, not an exception,
     * because the model sees them the same way.
     */
    public ToolOutcome call(String name, Map<String, Object> arguments) {
        Instant started = Instant.now();
        try {
            McpSchema.CallToolResult result = this.client.callTool(new McpSchema.CallToolRequest(name, arguments));
            String text = result.content() == null ? "" : result.content().stream()
                    .filter(McpSchema.TextContent.class::isInstance).map(c -> ((McpSchema.TextContent) c).text())
                    .collect(Collectors.joining("\n"));
            if (text.isEmpty() && result.structuredContent() != null) {
                text = this.json.writeValueAsString(result.structuredContent());
            }
            return new ToolOutcome(text, Boolean.TRUE.equals(result.isError()), started, Instant.now(), null);
        }
        catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new ToolOutcome(message, true, started, Instant.now(), e.getClass().getName());
        }
    }

    @Override
    public void close() {
        this.client.closeGracefully();
    }

    /**
     * @param failure the exception class when the call failed below the tool (transport or protocol), otherwise
     * null
     */
    public record ToolOutcome(String text, boolean isError, Instant startedAt, Instant endedAt, String failure) {

        public long latencyMs() {
            return Duration.between(this.startedAt, this.endedAt).toMillis();
        }
    }
}
