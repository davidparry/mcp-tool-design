package com.davidparry.tooldesign.benchmark.model;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;

import com.davidparry.tooldesign.benchmark.config.BenchProperties.ModelSpec;

import static org.assertj.core.api.Assertions.assertThat;

class ModelGatewayTest {

    @Test
    void rateLimitsOverloadsAndIoFailuresAreRetriedButBadKeysAreNot() {
        assertThat(ModelGateway.retryable(new RuntimeException("429 Too Many Requests"))).isTrue();
        assertThat(ModelGateway.retryable(new RuntimeException("Overloaded"))).isTrue();
        assertThat(ModelGateway.retryable(new RuntimeException("wrapped", new IOException("connection reset"))))
                .isTrue();
        assertThat(ModelGateway.retryable(new RuntimeException("401: invalid x-api-key"))).isFalse();
        assertThat(ModelGateway.retryable(new RuntimeException("invalid_api_key"))).isFalse();
        assertThat(ModelGateway.retryable(new IllegalArgumentException("bad schema"))).isFalse();
    }

    @Test
    void theRecordedSettingsLeaveOutWhatWasntSet() {
        ModelSpec spec = new ModelSpec("ollama", "qwen3.6:35b-a3b-nvfp4", 8192, 0.7, null, null, 32768, false, null,
                null, null);

        assertThat(new ModelGateway("qwen", spec, null).settings()).containsExactly(
                Map.entry("provider", "ollama"), Map.entry("model", "qwen3.6:35b-a3b-nvfp4"),
                Map.entry("maxTokens", 8192), Map.entry("temperature", 0.7), Map.entry("numCtx", 32768),
                Map.entry("think", false));
    }

    @Test
    void nativeUsageIsReadByFieldNameWithoutTheProviderTypes() {
        DefaultUsage usage = new DefaultUsage(120, 30, 150, new FakeAnthropicUsage(), 40L, null);

        assertThat(UsageReader.usage(usage)).containsEntry("inputTokens", 120).containsEntry("cacheReadTokens", 40L);
        assertThat(UsageReader.nativeUsage(usage)).containsEntry("inputTokens", 120L)
                .containsEntry("cacheReadInputTokens", 40L).doesNotContainKey("cacheCreationInputTokens")
                .containsEntry("type", FakeAnthropicUsage.class.getName());
    }

    @Test
    void ollamaCountsAreTakenFromTheResponseMetadata() {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().keyValue("prompt-eval-count", 2906)
                .keyValue("eval-duration", java.time.Duration.ofMillis(379)).keyValue("ignored", new Object()).build();

        assertThat(UsageReader.metadata(metadata)).containsEntry("prompt-eval-count", 2906)
                .containsEntry("eval-durationMs", 379L).doesNotContainKey("ignored");
    }

    public static final class FakeAnthropicUsage {

        public long inputTokens() {
            return 120;
        }

        public long outputTokens() {
            return 30;
        }

        public Optional<Long> cacheCreationInputTokens() {
            return Optional.empty();
        }

        public Optional<Long> cacheReadInputTokens() {
            return Optional.of(40L);
        }
    }
}
