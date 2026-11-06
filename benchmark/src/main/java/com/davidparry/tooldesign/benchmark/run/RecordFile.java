package com.davidparry.tooldesign.benchmark.run;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** A batch's {@code runs.jsonl}: one record per line, appended as each run finishes, so a batch can resume. */
public final class RecordFile {

    private final Path file;

    private final JsonMapper json;

    public RecordFile(Path file, JsonMapper json) {
        this.file = file;
        this.json = json;
    }

    public Path path() {
        return this.file;
    }

    /** Run IDs already recorded, other than setup errors, which are worth running again. */
    public Set<String> finished() {
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> record : read()) {
            if (!"setup_error".equals(record.get("stopReason"))) {
                ids.add((String) record.get("runId"));
            }
        }
        return ids;
    }

    public List<Map<String, Object>> read() {
        List<Map<String, Object>> records = new ArrayList<>();
        if (!Files.isRegularFile(this.file)) {
            return records;
        }
        try {
            for (String line : Files.readAllLines(this.file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    records.add(this.json.readValue(line, new TypeReference<Map<String, Object>>() {
                    }));
                }
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return records;
    }

    public synchronized void append(RunRecord record) {
        try {
            Files.createDirectories(this.file.toAbsolutePath().getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(this.file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(this.json.writeValueAsString(record));
                writer.newLine();
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
