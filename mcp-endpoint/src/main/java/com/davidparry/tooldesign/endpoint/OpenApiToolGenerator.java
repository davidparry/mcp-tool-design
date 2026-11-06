package com.davidparry.tooldesign.endpoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Turns an OpenAPI 3 document into one MCP tool per operation. It knows nothing about any particular API: the tool
 * name is the operationId, the description is the summary and description, and the input schema is the path and
 * query parameters plus the JSON request body's properties, with every {@code $ref} inlined.
 */
public final class OpenApiToolGenerator {

    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch");

    /** One generated tool and what forwarding it needs: where its arguments go in the HTTP request. */
    public record Operation(String name, String method, String path, List<String> pathParams,
            List<String> queryParams, List<String> bodyProperties, boolean hasBody, McpSchema.Tool tool) {
    }

    private OpenApiToolGenerator() {
    }

    /** Operations in operationId order, so the catalog doesn't depend on how the document orders its paths. */
    @SuppressWarnings("unchecked")
    public static List<Operation> generate(Map<String, Object> document) {
        Map<String, Object> components = (Map<String, Object>) document.getOrDefault("components", Map.of());
        Map<String, Object> schemas = (Map<String, Object>) components.getOrDefault("schemas", Map.of());
        Map<String, Object> paths = (Map<String, Object>) document.getOrDefault("paths", Map.of());
        List<Operation> operations = new ArrayList<>();
        for (Map.Entry<String, Object> path : paths.entrySet()) {
            Map<String, Object> pathItem = (Map<String, Object>) path.getValue();
            List<Object> sharedParameters = (List<Object>) pathItem.getOrDefault("parameters", List.of());
            for (Map.Entry<String, Object> entry : pathItem.entrySet()) {
                if (METHODS.contains(entry.getKey())) {
                    operations.add(operation(path.getKey(), entry.getKey(), (Map<String, Object>) entry.getValue(),
                            sharedParameters, schemas));
                }
            }
        }
        operations.sort(Comparator.comparing(Operation::name));
        return operations;
    }

    @SuppressWarnings("unchecked")
    private static Operation operation(String path, String method, Map<String, Object> op,
            List<Object> sharedParameters, Map<String, Object> schemas) {
        String name = (String) op.get("operationId");
        if (name == null || name.isBlank()) {
            throw new IllegalStateException(method.toUpperCase(Locale.ROOT) + " " + path + " has no operationId");
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        Set<String> required = new LinkedHashSet<>();
        List<String> pathParams = new ArrayList<>();
        List<String> queryParams = new ArrayList<>();

        List<Object> parameters = new ArrayList<>(sharedParameters);
        parameters.addAll((List<Object>) op.getOrDefault("parameters", List.of()));
        for (Object p : parameters) {
            Map<String, Object> parameter = (Map<String, Object>) inline(p, schemas, new LinkedHashSet<>());
            String in = (String) parameter.get("in");
            String paramName = (String) parameter.get("name");
            if (!"path".equals(in) && !"query".equals(in)) {
                continue;
            }
            Map<String, Object> schema = new LinkedHashMap<>(
                    (Map<String, Object>) inline(parameter.getOrDefault("schema", Map.of()), schemas, new LinkedHashSet<>()));
            if (parameter.get("description") != null) {
                schema.put("description", parameter.get("description"));
            }
            properties.put(paramName, schema);
            if ("path".equals(in)) {
                pathParams.add(paramName);
                required.add(paramName);
            }
            else {
                queryParams.add(paramName);
                if (Boolean.TRUE.equals(parameter.get("required"))) {
                    required.add(paramName);
                }
            }
        }

        List<String> bodyProperties = new ArrayList<>();
        Map<String, Object> body = jsonBodySchema(op, schemas);
        if (body != null) {
            Map<String, Object> bodyProps = (Map<String, Object>) body.getOrDefault("properties", Map.of());
            for (Map.Entry<String, Object> prop : bodyProps.entrySet()) {
                properties.put(prop.getKey(), prop.getValue());
                bodyProperties.add(prop.getKey());
            }
            required.addAll((List<String>) body.getOrDefault("required", List.of()));
        }

        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        if (!required.isEmpty()) {
            inputSchema.put("required", List.copyOf(required));
        }

        String summary = (String) op.get("summary");
        boolean readOnly = "get".equals(method);
        McpSchema.Tool tool = McpSchema.Tool.builder().name(name).title(summary)
                .description(join(summary, (String) op.get("description"))).inputSchema(inputSchema)
                .annotations(new McpSchema.ToolAnnotations(summary, readOnly, null, null, null, null)).build();
        return new Operation(name, method.toUpperCase(Locale.ROOT), path, pathParams, queryParams, bodyProperties,
                body != null, tool);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonBodySchema(Map<String, Object> op, Map<String, Object> schemas) {
        Map<String, Object> requestBody = (Map<String, Object>) op.get("requestBody");
        if (requestBody == null) {
            return null;
        }
        Map<String, Object> content = (Map<String, Object>) requestBody.getOrDefault("content", Map.of());
        Map<String, Object> json = (Map<String, Object>) content.get("application/json");
        if (json == null || json.get("schema") == null) {
            return null;
        }
        return (Map<String, Object>) inline(json.get("schema"), schemas, new LinkedHashSet<>());
    }

    /** A deep copy of {@code node} with every local {@code $ref} replaced by the schema it names. */
    @SuppressWarnings("unchecked")
    static Object inline(Object node, Map<String, Object> schemas, Set<String> resolving) {
        if (node instanceof Map<?, ?> map) {
            Object ref = map.get("$ref");
            if (ref instanceof String reference && reference.startsWith("#/components/schemas/")) {
                String schemaName = reference.substring("#/components/schemas/".length());
                if (!resolving.add(schemaName)) {
                    throw new IllegalStateException("Schema " + schemaName + " refers to itself; it can't be inlined");
                }
                Object target = schemas.get(schemaName);
                if (target == null) {
                    throw new IllegalStateException("No schema " + schemaName + " for " + reference);
                }
                Object resolved = inline(target, schemas, resolving);
                resolving.remove(schemaName);
                return resolved;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            ((Map<String, Object>) map).forEach((key, value) -> copy.put(key, inline(value, schemas, resolving)));
            return copy;
        }
        if (node instanceof List<?> list) {
            return list.stream().map(item -> inline(item, schemas, resolving)).toList();
        }
        return node;
    }

    private static String join(String summary, String description) {
        if (summary == null || summary.isBlank()) {
            return description == null ? "" : description;
        }
        if (description == null || description.isBlank()) {
            return summary;
        }
        return summary + "\n\n" + description;
    }
}
