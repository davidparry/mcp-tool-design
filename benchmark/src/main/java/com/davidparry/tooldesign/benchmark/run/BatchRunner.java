package com.davidparry.tooldesign.benchmark.run;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import com.davidparry.tooldesign.benchmark.app.AdminClient;
import com.davidparry.tooldesign.benchmark.config.BenchProperties;
import com.davidparry.tooldesign.benchmark.config.Redact;
import com.davidparry.tooldesign.benchmark.mcp.Catalogs;
import com.davidparry.tooldesign.benchmark.mcp.McpSession;
import com.davidparry.tooldesign.benchmark.model.DefinitionOnlyTool;
import com.davidparry.tooldesign.benchmark.model.Gateways;
import com.davidparry.tooldesign.benchmark.model.ModelGateway;

/**
 * Plans a batch, records its manifest, and runs it one run at a time against a single app. A batch that stops can
 * be started again with the same arguments: finished runs are skipped, and the manifest must match.
 */
@Component
public class BatchRunner {

    private static final Logger log = LoggerFactory.getLogger(BatchRunner.class);

    public static final String FINAL = "final";

    public static final String FREEZE_FILE = "freeze.json";

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
        Map<String, Object> hashes = hashes(arms);
        if (FINAL.equals(request.phase())) {
            checkFrozen(hashes);
        }
        for (String model : request.models()) {
            preflight(this.gateways.get(model), arms);
        }

        List<RunSpec> plan = RunPlan.plan(request.phase(), request.models(), request.arms(), tasks,
                request.repetitions(), request.seed());
        Path dir = Path.of(this.properties.resultsDir(), request.phase(), request.batch());
        writeOrCheckManifest(dir.resolve("manifest.json"), manifest(request, tasks, arms, hashes, plan));

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

    /**
     * One short request per arm, carrying that arm's tool definitions, so a bad key, a wrong model ID, or a tool
     * schema the provider rejects stops the batch before it records anything.
     *
     * @param arms each arm's details from {@link #arm(String)}
     */
    public void preflight(ModelGateway model, Map<String, Map<String, Object>> arms) {
        arms.forEach((arm, details) -> {
            try {
                model.call(List.of(new UserMessage("Reply with the single word: ready")), tools(details), null, 0,
                        Duration.ZERO);
            }
            catch (RuntimeException e) {
                throw new IllegalStateException(model.key() + " (" + model.spec().model() + ") isn't usable with the "
                        + arm + " tools: " + e.getClass().getSimpleName() + ": "
                        + Redact.secrets(firstLine(e.getMessage())));
            }
        });
    }

    @SuppressWarnings("unchecked")
    private List<ToolCallback> tools(Map<String, Object> arm) {
        return ((List<Map<String, Object>>) arm.get("toolDefinitions")).stream()
                .<ToolCallback>map(d -> DefinitionOnlyTool.of((String) d.get("name"), (String) d.get("description"),
                        this.json.writeValueAsString(d.get("inputSchema"))))
                .toList();
    }

    /**
     * Writes {@code fixtures/freeze.json} after the pilot: the run limits and the hashes of the seed data, tasks,
     * conversation, OpenAPI document, and both catalogs. Every final batch has to match it.
     */
    public Path freeze(List<String> arms, List<String> pilotBatches, boolean force) {
        Path file = fixtures().resolve(FREEZE_FILE);
        if (Files.exists(file) && !force) {
            throw new IllegalStateException(file + " exists. Use --force to freeze again; final batches run "
                    + "against the old freeze would then need to be run again.");
        }
        Map<String, Map<String, Object>> live = new LinkedHashMap<>();
        for (String arm : arms) {
            live.put(arm, arm(arm));
        }
        Map<String, Object> freeze = new LinkedHashMap<>();
        freeze.put("frozenAt", Instant.now().toString());
        freeze.put("source", Provenance.source());
        freeze.put("pilotBatches", pilotBatches);
        freeze.put("limits", this.properties.limits());
        freeze.put("hashes", hashes(live));
        try {
            Files.writeString(file, this.pretty.writeValueAsString(freeze) + "\n");
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    /** A final batch runs only against the frozen limits, contracts, fixtures, and catalogs. */
    private void checkFrozen(Map<String, Object> hashes) {
        Path file = fixtures().resolve(FREEZE_FILE);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("A final batch needs " + file + ". Run --command=freeze after the pilot.");
        }
        Map<String, Object> frozen;
        try {
            frozen = this.json.readValue(Files.readString(file), new TypeReference<Map<String, Object>>() {
            });
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> current = this.json.readValue(this.json.writeValueAsString(
                Map.of("limits", this.properties.limits(), "hashes", hashes)), new TypeReference<Map<String, Object>>() {
                });
        Map<?, ?> frozenHashes = (Map<?, ?>) frozen.get("hashes");
        Map<?, ?> currentHashes = (Map<?, ?>) current.get("hashes");
        List<String> changed = new ArrayList<>();
        for (String key : List.of("fixtures", "tasks", "conversation", "openapi")) {
            if (!Objects.equals(frozenHashes.get(key), currentHashes.get(key))) {
                changed.add(key);
            }
        }
        Map<?, ?> frozenCatalogs = (Map<?, ?>) frozenHashes.get("catalogs");
        ((Map<?, ?>) currentHashes.get("catalogs")).forEach((arm, sha) -> {
            if (!Objects.equals(frozenCatalogs.get(arm), sha)) {
                changed.add(arm + " catalog");
            }
        });
        if (!Objects.equals(String.valueOf(frozen.get("limits")), String.valueOf(current.get("limits")))) {
            changed.add("limits");
        }
        if (!changed.isEmpty()) {
            throw new IllegalStateException("The final run is frozen, but " + String.join(", ", changed)
                    + " changed since " + frozen.get("frozenAt") + ". Restore them, or freeze again and rerun every "
                    + "final batch.");
        }
    }

    private Map<String, Object> hashes(Map<String, Map<String, Object>> arms) {
        Map<String, Object> hashes = new LinkedHashMap<>();
        hashes.put("fixtures", Tasks.sha256Of(fixtures().resolve("seed")));
        hashes.put("tasks", Tasks.sha256Of(fixtures().resolve("tasks")));
        hashes.put("conversation", Catalogs.sha256Text(Conversation.SYSTEM_PROMPT + "\n"
                + Conversation.FINAL_ANSWER_DESCRIPTION + "\n" + Conversation.FINAL_ANSWER_SCHEMA + "\n"
                + Conversation.REMINDER));
        Map<String, Object> openApi = new LinkedHashMap<>(this.admin.openApi());
        openApi.remove("servers");
        hashes.put("openapi", Catalogs.sha256Of(openApi));
        Map<String, Object> catalogs = new LinkedHashMap<>();
        arms.forEach((arm, info) -> catalogs.put(arm, info.get("snapshotSha256")));
        hashes.put("catalogs", catalogs);
        return hashes;
    }

    private Map<String, Object> manifest(Request request, List<TaskSpec> tasks, Map<String, Map<String, Object>> arms,
            Map<String, Object> hashes, List<RunSpec> plan) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("batch", request.batch());
        manifest.put("phase", request.phase());
        manifest.put("seed", request.seed());
        manifest.put("repetitions", request.repetitions());
        manifest.put("source", Provenance.source());
        manifest.put("versions", Provenance.versions());
        manifest.put("hashes", hashes);
        Map<String, Object> models = new LinkedHashMap<>();
        request.models().forEach(m -> models.put(m, this.gateways.get(m).settings()));
        manifest.put("models", models);
        manifest.put("limits", this.properties.limits());
        manifest.put("arms", arms);
        Map<String, Object> conversation = new LinkedHashMap<>();
        conversation.put("systemPrompt", Conversation.SYSTEM_PROMPT);
        conversation.put("reminder", Conversation.REMINDER);
        manifest.put("conversation", conversation);
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
                    if (!Objects.equals(existing.get(key), current.get(key))) {
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
