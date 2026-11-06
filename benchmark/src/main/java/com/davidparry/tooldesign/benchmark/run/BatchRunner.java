package com.davidparry.tooldesign.benchmark.run;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import com.davidparry.tooldesign.benchmark.app.AdminClient;
import com.davidparry.tooldesign.benchmark.config.BenchProperties;
import com.davidparry.tooldesign.benchmark.config.Redact;
import com.davidparry.tooldesign.benchmark.mcp.Catalogs;
import com.davidparry.tooldesign.benchmark.mcp.McpSession;
import com.davidparry.tooldesign.benchmark.model.Gateways;
import com.davidparry.tooldesign.benchmark.model.ModelGateway;

/**
 * Plans a batch, records its manifest, and runs it one run at a time against a single app. A batch that stops can
 * be started again with the same arguments: finished runs are skipped, and the manifest must match.
 */
@Component
public class BatchRunner {

    private static final Logger log = LoggerFactory.getLogger(BatchRunner.class);

    private final RunExecutor executor;

    private final Gateways gateways;

    private final AdminClient admin;

    private final BenchProperties properties;

    private final JsonMapper json;

    private final JsonMapper pretty;

    public BatchRunner(RunExecutor executor, Gateways gateways, AdminClient admin, BenchProperties properties,
            JsonMapper json) {
        this.executor = executor;
        this.gateways = gateways;
        this.admin = admin;
        this.properties = properties;
        this.json = json;
        this.pretty = json.rebuild().enable(SerializationFeature.INDENT_OUTPUT).build();
    }

    public record Request(String phase, String batch, List<String> models, List<String> arms, List<String> splits,
            List<String> taskIds, int repetitions, long seed) {
    }

    public Path run(Request request) {
        if (!this.admin.reachable()) {
            throw new IllegalStateException("The app isn't answering at " + this.properties.appUrl());
        }
        List<TaskSpec> tasks = Tasks.load(fixtures().resolve("tasks"), request.splits(), this.json).stream()
                .filter(t -> request.taskIds().isEmpty() || request.taskIds().contains(t.id())).toList();
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("No tasks match " + request.taskIds() + " in " + request.splits());
        }
        Map<String, Map<String, Object>> arms = new LinkedHashMap<>();
        for (String arm : request.arms()) {
            arms.put(arm, arm(arm));
        }
        for (String model : request.models()) {
            preflight(this.gateways.get(model));
        }

        List<RunSpec> plan = RunPlan.plan(request.phase(), request.models(), request.arms(), tasks,
                request.repetitions(), request.seed());
        Path dir = Path.of(this.properties.resultsDir(), request.phase(), request.batch());
        writeOrCheckManifest(dir.resolve("manifest.json"), manifest(request, tasks, arms, plan));

        RecordFile records = new RecordFile(dir.resolve("runs.jsonl"), this.json);
        Set<String> finished = records.finished();
        Instant batchStarted = Instant.now();
        int done = 0;
        for (RunSpec spec : plan) {
            if (finished.contains(spec.runId())) {
                continue;
            }
            String catalog = (String) arms.get(spec.arm()).get("catalogSha256");
            RunRecord record = this.executor.execute(spec, request.batch(), request.phase(),
                    this.gateways.get(spec.model()), catalog);
            records.append(record);
            done++;
            log.info("[{}/{}] {} {} | {} model calls, {} tool calls, {} in + {} out tokens | {}s", spec.position(),
                    plan.size(), spec.runId(), record.stopReason(), record.totals().modelCalls(),
                    record.totals().toolCalls(), record.totals().inputTokens(), record.totals().outputTokens(),
                    record.durationMs() / 1000);
            if ("setup_error".equals(record.stopReason())) {
                throw new IllegalStateException("Stopping the batch: " + record.errors());
            }
        }
        log.info("Batch {} finished {} runs in {} minutes; records are in {}", request.batch(), done,
                Duration.between(batchStarted, Instant.now()).toMinutes(), records.path());
        return dir;
    }

    /**
     * Lists the arm's catalog and checks it against its snapshot. In {@code verify} mode a missing or different
     * snapshot stops the batch before any run starts.
     */
    public Map<String, Object> arm(String arm) {
        String url = this.properties.servers().get(arm);
        if (url == null) {
            throw new IllegalArgumentException("No server configured for arm " + arm);
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("server", url);
        try (McpSession session = McpSession.open(url, this.json)) {
            String sha = Catalogs.sha256(session.listTools());
            info.put("protocolVersion", session.protocolVersion());
            info.put("catalogSha256", sha);
            String snapshot = Catalogs.snapshotHash(Catalogs.snapshot(fixtures().resolve("catalogs"), arm));
            info.put("snapshotSha256", snapshot);
            if ("verify".equals(this.properties.catalogMode()) && !sha.equals(snapshot)) {
                throw new IllegalStateException("The " + arm + " server lists catalog " + sha + " but its snapshot is "
                        + snapshot + ". Rebuild the server from the frozen source, or write a new snapshot before "
                        + "the catalogs are frozen.");
            }
        }
        List<Map<String, Object>> definitions = this.executor.definitionsFor(arm);
        info.put("toolDefinitionsSha256", Catalogs.sha256Of(definitions));
        info.put("toolDefinitions", definitions);
        return info;
    }

    /** One short request without tools, so a bad key or model ID stops the batch before it records anything. */
    public void preflight(ModelGateway model) {
        try {
            model.call(List.of(new UserMessage("Reply with the single word: ready")), List.of(), null, 0,
                    Duration.ZERO);
        }
        catch (RuntimeException e) {
            throw new IllegalStateException(model.key() + " (" + model.spec().model() + ") isn't usable: "
                    + e.getClass().getSimpleName() + ": " + Redact.secrets(firstLine(e.getMessage())));
        }
    }

    private Map<String, Object> manifest(Request request, List<TaskSpec> tasks, Map<String, Map<String, Object>> arms,
            List<RunSpec> plan) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("batch", request.batch());
        manifest.put("phase", request.phase());
        manifest.put("seed", request.seed());
        manifest.put("repetitions", request.repetitions());
        manifest.put("source", Provenance.source());
        manifest.put("versions", Provenance.versions());
        Map<String, Object> hashes = new LinkedHashMap<>();
        hashes.put("fixtures", Tasks.sha256Of(fixtures().resolve("seed")));
        hashes.put("tasks", Tasks.sha256Of(fixtures().resolve("tasks")));
        hashes.put("conversation", Catalogs.sha256Text(Conversation.SYSTEM_PROMPT + "\n"
                + Conversation.FINAL_ANSWER_DESCRIPTION + "\n" + Conversation.FINAL_ANSWER_SCHEMA + "\n"
                + Conversation.REMINDER));
        manifest.put("hashes", hashes);
        Map<String, Object> models = new LinkedHashMap<>();
        request.models().forEach(m -> models.put(m, this.gateways.get(m).settings()));
        manifest.put("models", models);
        manifest.put("limits", this.properties.limits());
        manifest.put("arms", arms);
        manifest.put("conversation", Map.of("systemPrompt", Conversation.SYSTEM_PROMPT, "reminder",
                Conversation.REMINDER));
        manifest.put("tasks", tasks.stream().map(TaskSpec::id).toList());
        manifest.put("runs", plan.stream().map(r -> {
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("position", r.position());
            run.put("runId", r.runId());
            run.put("model", r.model());
            run.put("arm", r.arm());
            run.put("task", r.task().id());
            run.put("repetition", r.repetition());
            run.put("seed", r.seed());
            return run;
        }).toList());
        return manifest;
    }

    /** A resumed batch must have been planned with the same inputs, or its runs can't be compared. */
    private void writeOrCheckManifest(Path file, Map<String, Object> manifest) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Map<String, Object> current = this.json.readValue(this.json.writeValueAsString(manifest),
                    new TypeReference<Map<String, Object>>() {
                    });
            if (Files.isRegularFile(file)) {
                Map<String, Object> existing = this.json.readValue(Files.readString(file),
                        new TypeReference<Map<String, Object>>() {
                        });
                for (String key : List.of("hashes", "models", "limits", "arms", "runs", "conversation")) {
                    if (!String.valueOf(existing.get(key)).equals(String.valueOf(current.get(key)))) {
                        throw new IllegalStateException("Batch " + manifest.get("batch") + " was planned with a "
                                + "different " + key + ". Use a new batch name.");
                    }
                }
                return;
            }
            current.put("createdAt", Instant.now().toString());
            Files.writeString(file, this.pretty.writeValueAsString(current) + "\n");
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path fixtures() {
        return Path.of(this.properties.fixturesDir());
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
