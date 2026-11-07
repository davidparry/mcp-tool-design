package com.davidparry.tooldesign.benchmark.config;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class BenchPropertiesTest {

    @Test
    void theHostedModelsCarryTheirListPricesUnderTheKeysTheExportReads() throws IOException {
        BenchProperties properties = bind();

        assertThat(properties.models().get("claude").model()).isEqualTo("claude-opus-4-8");
        assertThat(properties.models().get("claude").prices()).containsOnly(entry("input", 5.0),
                entry("output", 25.0), entry("cacheRead", 0.5), entry("cacheWrite", 6.25));
        assertThat(properties.models().get("gpt").model()).isEqualTo("gpt-5");
        assertThat(properties.models().get("gpt").prices()).containsOnly(entry("input", 1.25), entry("output", 10.0),
                entry("cacheRead", 0.125));
        assertThat(properties.models().get("qwen").prices()).isNull();
    }

    private static BenchProperties bind() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
                .bind("bench", BenchProperties.class).get();
    }
}
