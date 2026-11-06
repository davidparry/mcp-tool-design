package com.davidparry.tooldesign.benchmark.model;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * A tool the model can call but Spring AI never runs. The runner reads the tool calls off each response and runs
 * them itself, so every model turn is one recorded request.
 */
public record DefinitionOnlyTool(ToolDefinition definition) implements ToolCallback {

    public static DefinitionOnlyTool of(String name, String description, String inputSchema) {
        return new DefinitionOnlyTool(DefaultToolDefinition.builder().name(name).description(description)
                .inputSchema(inputSchema).build());
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return this.definition;
    }

    @Override
    public String call(String toolInput) {
        throw new IllegalStateException("The runner executes " + this.definition.name() + "; Spring AI must not");
    }
}
