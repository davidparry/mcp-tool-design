package com.davidparry.tooldesign.benchmark.model;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.anthropic.models.messages.OutputConfig;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.ThinkOption;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

import com.davidparry.tooldesign.benchmark.config.BenchProperties.ModelSpec;

/**
 * One configured model behind a Spring AI {@link ChatModel}. Each {@link #call} is one provider request; tools are
 * sent as definitions only and Spring AI's {@code ChatModel} doesn't run them, so the runner sees every tool call.
 */
public final class ModelGateway {

    private static final List<String> ACCOUNT_PROBLEMS = List.of("invalid x-api-key", "invalid_api_key",
            "authentication_error", "permission_error", "insufficient_quota", "exceeded your current quota",
            "enforced_spend_limit_reached", "api usage limits", "billing_hard_limit_reached");

    private final String key;

    private final ModelSpec spec;

    private final ChatModel chatModel;

    public ModelGateway(String key, ModelSpec spec, ChatModel chatModel) {
        this.key = key;
        this.spec = spec;
        this.chatModel = chatModel;
    }

    public String key() {
        return this.key;
    }

    public ModelSpec spec() {
        return this.spec;
    }

    /**
     * Sends the conversation and tools, retrying a failed request up to {@code retries} times with a growing pause.
     *
     * @param seed the sampling seed, for providers that take one
     */
    public ModelReply call(List<Message> messages, List<ToolCallback> tools, Integer seed, int retries,
            Duration backoff) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            if (attempt > 0) {
                pause(backoff.multipliedBy(attempt));
            }
            long started = System.nanoTime();
            try {
                ChatResponse response = this.chatModel.call(new Prompt(List.copyOf(messages), options(tools, seed)));
                long latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
                var generation = response.getResult();
                return new ModelReply(generation.getOutput(),
                        generation.getMetadata() == null ? null : generation.getMetadata().getFinishReason(),
                        response.getMetadata().getModel(), UsageReader.usage(response.getMetadata().getUsage()),
                        UsageReader.nativeUsage(response.getMetadata().getUsage()),
                        UsageReader.metadata(response.getMetadata()), latency, attempt);
            }
            catch (RuntimeException e) {
                if (!retryable(e)) {
                    throw e;
                }
                last = e;
            }
        }
        throw last;
    }

    /** The settings sent with every request, as recorded in the manifest. Unset settings are left out. */
    public Map<String, Object> settings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("provider", this.spec.provider());
        settings.put("model", this.spec.model());
        putIfSet(settings, "maxTokens", this.spec.maxTokens());
        putIfSet(settings, "temperature", this.spec.temperature());
        putIfSet(settings, "topP", this.spec.topP());
        putIfSet(settings, "topK", this.spec.topK());
        putIfSet(settings, "numCtx", this.spec.numCtx());
        putIfSet(settings, "think", this.spec.think());
        putIfSet(settings, "effort", this.spec.effort());
        putIfSet(settings, "reasoningEffort", this.spec.reasoningEffort());
        return settings;
    }

    private ChatOptions options(List<ToolCallback> tools, Integer seed) {
        return switch (this.spec.provider()) {
            case "anthropic" -> {
                var builder = AnthropicChatOptions.builder().model(this.spec.model()).toolCallbacks(tools);
                if (this.spec.maxTokens() != null) {
                    builder.maxTokens(this.spec.maxTokens());
                }
                if (this.spec.temperature() != null) {
                    builder.temperature(this.spec.temperature());
                }
                if (this.spec.effort() != null) {
                    builder.effort(OutputConfig.Effort.of(this.spec.effort()));
                }
                yield builder.build();
            }
            case "openai" -> {
                var builder = OpenAiChatOptions.builder().model(this.spec.model()).toolCallbacks(tools);
                if (this.spec.maxTokens() != null) {
                    builder.maxCompletionTokens(this.spec.maxTokens());
                }
                if (this.spec.temperature() != null) {
                    builder.temperature(this.spec.temperature());
                }
                if (this.spec.reasoningEffort() != null) {
                    builder.reasoningEffort(this.spec.reasoningEffort());
                }
                if (seed != null) {
                    builder.seed(seed);
                }
                yield builder.build();
            }
            case "ollama" -> {
                var builder = OllamaChatOptions.builder().model(this.spec.model()).toolCallbacks(tools);
                if (this.spec.maxTokens() != null) {
                    builder.numPredict(this.spec.maxTokens());
                }
                if (this.spec.temperature() != null) {
                    builder.temperature(this.spec.temperature());
                }
                if (this.spec.topP() != null) {
                    builder.topP(this.spec.topP());
                }
                if (this.spec.topK() != null) {
                    builder.topK(this.spec.topK());
                }
                if (this.spec.numCtx() != null) {
                    builder.numCtx(this.spec.numCtx());
                }
                if (this.spec.think() != null) {
                    builder.thinkOption(this.spec.think() ? ThinkOption.ThinkBoolean.ENABLED
                            : ThinkOption.ThinkBoolean.DISABLED);
                }
                if (seed != null) {
                    builder.seed(seed);
                }
                yield builder.build();
            }
            default -> throw new IllegalStateException("Unknown provider " + this.spec.provider() + " for " + this.key);
        };
    }

    /**
     * Whether a failed request says nothing about the run: the provider stayed overloaded or out of reach through
     * every retry, or the account can't make requests at all. A run that ends this way has to be run again.
     */
    public static boolean providerProblem(Throwable error) {
        return accountProblem(error) || retryable(error);
    }

    /** Rate limits, overloads, server errors, and timeouts are worth another try; bad requests and bad keys aren't. */
    static boolean retryable(Throwable error) {
        if (accountProblem(error)) {
            return false;
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String name = cause.getClass().getSimpleName();
            String message = String.valueOf(cause.getMessage()).toLowerCase();
            if (name.contains("Unauthorized") || name.contains("Permission") || name.contains("BadRequest")
                    || name.contains("NotFound") || message.contains("invalid x-api-key")
                    || message.contains("invalid_api_key") || message.contains("401")) {
                return false;
            }
            if (name.contains("RateLimit") || name.contains("InternalServer") || name.contains("Timeout")
                    || name.contains("Unexpected") || name.contains("Overloaded") || cause instanceof java.io.IOException
                    || message.contains("429") || message.contains("529") || message.contains("503")
                    || message.contains("overloaded") || message.contains("timed out")) {
                return true;
            }
        }
        return false;
    }

    /**
     * A bad key, missing permission, or a spent quota or monthly spend cap. Both providers send the last two as 429s,
     * but waiting doesn't clear them.
     */
    static boolean accountProblem(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String name = cause.getClass().getSimpleName();
            String message = String.valueOf(cause.getMessage()).toLowerCase();
            if (name.contains("Unauthorized") || name.contains("PermissionDenied")
                    || ACCOUNT_PROBLEMS.stream().anyMatch(message::contains)) {
                return true;
            }
        }
        return false;
    }

    private static void putIfSet(Map<String, Object> settings, String name, Object value) {
        if (value != null) {
            settings.put(name, value);
        }
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry", e);
        }
    }
}
