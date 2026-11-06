package com.davidparry.tooldesign.benchmark.mcp;

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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * An arm's catalog is what its server lists: each tool's name, title, description, input and output schemas, and
 * annotations. The hash is over canonical JSON with sorted keys, the same form the endpoint server writes its
 * snapshot in, so the runner can check a live server against {@code fixtures/catalogs/<arm>-tools.json}.
 */
public final class Catalogs {

    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private static final JsonMapper PRETTY = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private Catalogs() {
    }

    public static List<Map<String, Object>> describe(List<McpSchema.Tool> tools) {
        return tools.stream().map(Catalogs::describe).toList();
    }

    public static String sha256(List<McpSchema.Tool> tools) {
        return sha256Of(describe(tools));
    }

    public static String sha256Of(Object value) {
        return sha256Text(CANONICAL.writeValueAsString(value));
    }

    public static String sha256Text(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Path snapshot(Path catalogsDir, String arm) {
        return catalogsDir.resolve(arm + "-tools.json");
    }

    /** The hash a snapshot file was written with, or null when there's no snapshot. */
    public static String snapshotHash(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            Map<String, Object> snapshot = CANONICAL.readValue(Files.readString(file),
                    new TypeReference<Map<String, Object>>() {
                    });
            return (String) snapshot.get("sha256");
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void write(Path file, List<McpSchema.Tool> tools) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sha256", sha256(tools));
        snapshot.put("tools", describe(tools));
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, PRETTY.writeValueAsString(snapshot) + "\n");
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> describe(McpSchema.Tool tool) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("name", tool.name());
        if (tool.title() != null) {
            described.put("title", tool.title());
        }
        described.put("description", tool.description());
        described.put("inputSchema", CANONICAL.convertValue(tool.inputSchema(), Object.class));
        if (tool.outputSchema() != null) {
            described.put("outputSchema", CANONICAL.convertValue(tool.outputSchema(), Object.class));
        }
        if (tool.annotations() != null) {
            Map<String, Object> annotations = new LinkedHashMap<>();
            CANONICAL.convertValue(tool.annotations(), new TypeReference<Map<String, Object>>() {
            }).forEach((key, value) -> {
                if (value != null) {
                    annotations.put(key, value);
                }
            });
            described.put("annotations", annotations);
        }
        return described;
    }
}
