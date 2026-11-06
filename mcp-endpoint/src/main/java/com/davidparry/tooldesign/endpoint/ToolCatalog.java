package com.davidparry.tooldesign.endpoint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The generated catalog as a model would see it in {@code tools/list}, and its SHA-256 over canonical JSON (keys
 * sorted, no whitespace). The benchmark freezes the catalog by this hash.
 */
public final class ToolCatalog {

    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private static final JsonMapper PRETTY = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private ToolCatalog() {
    }

    public static List<Map<String, Object>> describe(List<McpSchema.Tool> tools) {
        return tools.stream().map(ToolCatalog::describe).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> describe(McpSchema.Tool tool) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", tool.name());
        if (tool.title() != null) {
            entry.put("title", tool.title());
        }
        entry.put("description", tool.description());
        entry.put("inputSchema", CANONICAL.convertValue(tool.inputSchema(), Map.class));
        if (tool.outputSchema() != null) {
            entry.put("outputSchema", tool.outputSchema());
        }
        if (tool.annotations() != null) {
            Map<String, Object> annotations = CANONICAL.convertValue(tool.annotations(), Map.class);
            annotations.values().removeIf(value -> value == null);
            entry.put("annotations", annotations);
        }
        return entry;
    }

    public static String sha256(List<Map<String, Object>> catalog) {
        try {
            byte[] canonical = CANONICAL.writeValueAsString(catalog).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void write(Path file, List<Map<String, Object>> catalog) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sha256", sha256(catalog));
        snapshot.put("tools", catalog);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, PRETTY.writeValueAsString(snapshot) + "\n");
        }
        catch (IOException e) {
            throw new UncheckedIOException("Can't write the catalog snapshot " + file, e);
        }
    }

    public static String snapshotHash(Path file) {
        try {
            return (String) PRETTY.readValue(Files.readString(file), Map.class).get("sha256");
        }
        catch (IOException e) {
            throw new UncheckedIOException("Can't read the catalog snapshot " + file, e);
        }
    }
}
