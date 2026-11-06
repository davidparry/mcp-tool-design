package com.davidparry.tooldesign.app.calls;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

/**
 * Every {@code /api} call since the last reset, labeled with the benchmark run that was active. Runs are sequential,
 * so the active run tag is enough to attribute a call; nothing has to pass the run ID through the MCP servers.
 */
@Component
public class CallLog {

    public record Entry(long seq, String runId, String caller, String method, String path, String query, int status,
            long durationMs, Instant at) {
    }

    private final AtomicReference<String> runId = new AtomicReference<>();

    private final AtomicLong seq = new AtomicLong();

    private final List<Entry> entries = new ArrayList<>();

    public void startRun(String runId) {
        this.runId.set(runId);
    }

    public String currentRun() {
        return this.runId.get();
    }

    public Entry record(String caller, String method, String path, String query, int status, long durationMs) {
        Entry entry = new Entry(this.seq.incrementAndGet(), this.runId.get(), caller, method, path, query, status,
                durationMs, Instant.now());
        synchronized (this.entries) {
            this.entries.add(entry);
        }
        return entry;
    }

    /** The calls made while {@code runId} was active, or every call when it's null. */
    public List<Entry> entries(String runId) {
        synchronized (this.entries) {
            return this.entries.stream().filter(e -> runId == null || runId.equals(e.runId())).toList();
        }
    }

    public void clear() {
        synchronized (this.entries) {
            this.entries.clear();
        }
        this.runId.set(null);
        this.seq.set(0);
    }
}
