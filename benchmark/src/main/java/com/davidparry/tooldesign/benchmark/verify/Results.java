package com.davidparry.tooldesign.benchmark.verify;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.davidparry.tooldesign.benchmark.config.BenchProperties;
import com.davidparry.tooldesign.benchmark.config.BenchProperties.ModelSpec;
import com.davidparry.tooldesign.benchmark.run.RecordFile;
import com.davidparry.tooldesign.benchmark.run.RunRecord;
import com.davidparry.tooldesign.benchmark.run.TaskSpec;
import com.davidparry.tooldesign.benchmark.run.Tasks;

/**
 * Verifies recorded batches and exports them. Records are never rewritten: each batch gets a
 * {@code verification.jsonl} beside its {@code runs.jsonl}, and the last record for a run ID is the one graded.
 */
@Component
public class Results {

    static final List<String> SPLITS = List.of("dev", "heldout", "scenario");

    private final Verifier verifier;

    private final Exporter exporter;

    private final BenchProperties properties;

    private final JsonMapper json;

    public Results(Verifier verifier, Exporter exporter, BenchProperties properties, JsonMapper json) {
        this.verifier = verifier;
        this.exporter = exporter;
        this.properties = properties;
        this.json = json;
    }

    public List<Exporter.Row> verify(String phase, List<String> batches) {
        Map<String, TaskSpec> tasks = Tasks.load(Path.of(this.properties.fixturesDir(), "tasks"), SPLITS, this.json)
                .stream().collect(Collectors.toMap(TaskSpec::id, Function.identity()));
        List<Exporter.Row> rows = new ArrayList<>();
        for (String batch : batches) {
            Path dir = Path.of(this.properties.resultsDir(), phase, batch);
            Map<String, RunRecord> latest = new LinkedHashMap<>();
            for (Map<String, Object> line : new RecordFile(dir.resolve("runs.jsonl"), this.json).read()) {
                RunRecord record = this.json.convertValue(line, RunRecord.class);
                latest.put(record.runId(), record);
            }
            if (latest.isEmpty()) {
                throw new IllegalArgumentException("No runs recorded in " + dir.resolve("runs.jsonl"));
            }
            List<Verification> verifications = new ArrayList<>();
            for (RunRecord record : latest.values()) {
                TaskSpec task = tasks.get(record.taskId());
                if (task == null) {
                    throw new IllegalStateException(record.runId() + " ran task " + record.taskId()
                            + ", which isn't in fixtures/tasks");
                }
                Verification verification = this.verifier.verify(record, task);
                verifications.add(verification);
                rows.add(new Exporter.Row(record, verification, provider(record.model())));
            }
            write(dir.resolve("verification.jsonl"), verifications);
        }
        return rows;
    }

    public Path export(String phase, List<String> batches, String name) {
        if ("final".equals(phase)) {
            sameSetup(phase, batches);
        }
        List<Exporter.Row> rows = verify(phase, batches);
        return this.exporter.export(rows, Path.of(this.properties.resultsDir(), phase, name, "export"));
    }

    /** Final batches are compared only if they ran against the same limits, contracts, fixtures, and catalogs. */
    private void sameSetup(String phase, List<String> batches) {
        String first = null;
        String setup = null;
        for (String batch : batches) {
            Path file = Path.of(this.properties.resultsDir(), phase, batch, "manifest.json");
            Map<String, Object> manifest;
            try {
                manifest = this.json.readValue(Files.readString(file), new TypeReference<Map<String, Object>>() {
                });
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            String current = String.valueOf(manifest.get("hashes")) + String.valueOf(manifest.get("limits"));
            if (first == null) {
                first = batch;
                setup = current;
            }
            else if (!setup.equals(current)) {
                throw new IllegalStateException("Batches " + first + " and " + batch + " ran with different hashes "
                        + "or limits, so they can't be exported together.");
            }
        }
    }

    private String provider(String model) {
        ModelSpec spec = this.properties.models().get(model);
        return spec == null ? "unknown" : spec.provider();
    }

    private void write(Path file, List<Verification> verifications) {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (Verification verification : verifications) {
                writer.write(this.json.writeValueAsString(verification));
                writer.newLine();
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
