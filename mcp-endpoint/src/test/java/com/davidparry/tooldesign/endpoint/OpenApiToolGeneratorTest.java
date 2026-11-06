package com.davidparry.tooldesign.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.davidparry.tooldesign.endpoint.OpenApiToolGenerator.Operation;

class OpenApiToolGeneratorTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static Map<String, Object> document() throws IOException {
        try (InputStream in = OpenApiToolGeneratorTest.class.getResourceAsStream("/openapi/order-support-api.json")) {
            return JSON.readValue(in, new TypeReference<Map<String, Object>>() {
            });
        }
    }

    static Operation find(List<Operation> operations, String name) {
        return operations.stream().filter(op -> op.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void everyOperationBecomesOneToolNamedByItsOperationId() throws IOException {
        List<Operation> operations = OpenApiToolGenerator.generate(document());
        assertThat(operations).extracting(Operation::name).containsExactly("check_exchange_eligibility",
                "check_return_eligibility", "create_exchange_quote", "create_return_quote", "get_delivery_options",
                "get_inventory", "get_order", "get_order_items", "get_product_variants", "search_orders",
                "submit_exchange_quote", "submit_return_quote");
    }

    @Test
    void pathParametersAreRequiredAndQueryParametersKeepTheirOwnRequiredFlag() throws IOException {
        List<Operation> operations = OpenApiToolGenerator.generate(document());
        Operation eligibility = find(operations, "check_exchange_eligibility");
        assertThat(eligibility.path()).isEqualTo("/api/orders/{reference}/items/{itemId}/exchange-eligibility");
        assertThat(eligibility.tool().inputSchema().get("required")).isEqualTo(List.of("reference", "itemId"));

        Operation search = find(operations, "search_orders");
        assertThat(search.queryParams()).containsExactly("query", "status", "page", "size");
        assertThat(search.tool().inputSchema()).doesNotContainKey("required");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRequestBodysPropertiesBecomeArgumentsWithTheirSchemasInlined() throws IOException {
        Operation quote = find(OpenApiToolGenerator.generate(document()), "create_exchange_quote");
        Map<String, Object> properties = (Map<String, Object>) quote.tool().inputSchema().get("properties");
        assertThat(properties).containsOnlyKeys("orderReference", "itemId", "replacementSku", "quantity",
                "warehouseId", "deliveryMethod");
        assertThat(quote.bodyProperties()).hasSize(6);
        assertThat((List<String>) quote.tool().inputSchema().get("required")).hasSize(6);
        assertThat(((Map<String, Object>) properties.get("deliveryMethod")).get("enum"))
                .isEqualTo(List.of("GROUND", "EXPRESS"));
        assertThat(JSON.writeValueAsString(quote.tool())).doesNotContain("$ref");
    }

    @Test
    void theDescriptionIsTheSummaryAndDescriptionAndOnlyGetsAreReadOnly() throws IOException {
        List<Operation> operations = OpenApiToolGenerator.generate(document());
        Operation quote = find(operations, "create_exchange_quote");
        assertThat(quote.tool().description()).startsWith("Create an exchange quote\n\n")
                .contains("doesn't commit an exchange");
        assertThat(quote.tool().annotations().readOnlyHint()).isFalse();
        assertThat(find(operations, "get_order").tool().annotations().readOnlyHint()).isTrue();
        assertThat(operations).allSatisfy(op -> assertThat(op.tool().annotations().destructiveHint()).isNull());
    }

    @Test
    void aSelfReferencingSchemaIsRefused() {
        Map<String, Object> schemas = Map.of("Node",
                Map.of("type", "object", "properties", Map.of("next", Map.of("$ref", "#/components/schemas/Node"))));
        assertThatThrownBy(() -> OpenApiToolGenerator.inline(Map.of("$ref", "#/components/schemas/Node"), schemas,
                new java.util.LinkedHashSet<>())).hasMessageContaining("refers to itself");
    }
}
