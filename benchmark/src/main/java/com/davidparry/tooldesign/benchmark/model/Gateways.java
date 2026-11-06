package com.davidparry.tooldesign.benchmark.model;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.davidparry.tooldesign.benchmark.config.BenchProperties;

/** The configured models, keyed by the short name runs use: claude, gpt, and qwen. */
@Component
public class Gateways {

    private final Map<String, ModelGateway> gateways = new LinkedHashMap<>();

    public Gateways(BenchProperties properties, ObjectProvider<AnthropicChatModel> anthropic,
            ObjectProvider<OpenAiChatModel> openai, ObjectProvider<OllamaChatModel> ollama) {
        properties.models().forEach((key, spec) -> {
            ChatModel chatModel = switch (spec.provider()) {
                case "anthropic" -> anthropic.getIfAvailable();
                case "openai" -> openai.getIfAvailable();
                case "ollama" -> ollama.getIfAvailable();
                default -> null;
            };
            if (chatModel == null) {
                throw new IllegalStateException("No Spring AI chat model for provider " + spec.provider() + " ("
                        + key + ")");
            }
            this.gateways.put(key, new ModelGateway(key, spec, chatModel));
        });
    }

    public ModelGateway get(String key) {
        ModelGateway gateway = this.gateways.get(key);
        if (gateway == null) {
            throw new IllegalArgumentException("No model named " + key + "; configured: " + this.gateways.keySet());
        }
        return gateway;
    }

    public Map<String, ModelGateway> all() {
        return this.gateways;
    }
}
