package com.davidparry.tooldesign.benchmark.run;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One benchmark task as written in {@code fixtures/tasks}. A task is one customer message; a submission scenario
 * adds follow-up messages, each of which can change the world first (advance the clock, change stock).
 *
 * @param expected what the verifier checks, kept as written
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskSpec(String id, String split, String category, String kind, String prompt,
        List<FollowUp> followUps, Map<String, Object> expected) {

    public TaskSpec {
        followUps = followUps == null ? List.of() : List.copyOf(followUps);
    }

    /** The customer messages in order, starting with the prompt. */
    public List<String> messages() {
        return Stream.concat(Stream.of(this.prompt), this.followUps.stream().map(FollowUp::user)).toList();
    }

    /** A later customer message and what happens in the world before it's sent. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FollowUp(List<WorldChange> before, String user) {

        public FollowUp {
            before = before == null ? List.of() : List.copyOf(before);
        }
    }

    /** Exactly one of the two is set. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorldChange(Integer advanceClockMinutes, StockChange setStock) {
    }

    public record StockChange(String sku, String warehouseId, int available) {
    }
}
