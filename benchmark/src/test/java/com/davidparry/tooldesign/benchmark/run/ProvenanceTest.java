package com.davidparry.tooldesign.benchmark.run;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProvenanceTest {

    @Test
    void aManifestKnowsTheVersionOfEverythingBetweenTheRunnerAndTheModels() {
        assertThat(Provenance.versions())
                .containsOnlyKeys("java", "springBoot", "springAi", "mcpSdk", "anthropicSdk", "openaiSdk")
                .doesNotContainValue("unknown");
    }
}
