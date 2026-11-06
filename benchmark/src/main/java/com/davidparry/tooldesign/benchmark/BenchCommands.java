package com.davidparry.tooldesign.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import com.davidparry.tooldesign.benchmark.config.BenchProperties;
import com.davidparry.tooldesign.benchmark.config.Redact;
import com.davidparry.tooldesign.benchmark.mcp.Catalogs;
import com.davidparry.tooldesign.benchmark.mcp.McpSession;
import com.davidparry.tooldesign.benchmark.model.Gateways;
import com.davidparry.tooldesign.benchmark.model.ModelGateway;
import com.davidparry.tooldesign.benchmark.run.BatchRunner;
import com.davidparry.tooldesign.benchmark.verify.Exporter;
import com.davidparry.tooldesign.benchmark.verify.Results;
import com.davidparry.tooldesign.benchmark.verify.Verification;

/**
 * The runner's commands, chosen with {@code --command}:
 * <ul>
 * <li>{@code check}: checks the app, each arm's catalog against its snapshot, and each model with one short
 * request</li>
 * <li>{@code catalog --arms=workflow}: writes an arm's catalog snapshot from its live server; {@code --force}
 * replaces an existing one</li>
 * <li>{@code run --phase=pilot --models=qwen --arms=endpoint,workflow --splits=dev --reps=3 --seed=20261106}:
 * runs a batch, optionally limited with {@code --tasks=C1,H1} and named with {@code --batch}</li>
 * <li>{@code verify --phase=pilot --batch=pilot-qwen}: grades a batch and writes {@code verification.jsonl}</li>
 * <li>{@code export --phase=final --batches=final-claude,final-gpt,final-qwen --name=final}: grades and writes
 * the summary, CSVs, and the order 1042 token series to {@code results/<phase>/<name>/export}</li>
 * </ul>
 */
@Component
public class BenchCommands implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(BenchCommands.class);

    private final BatchRunner batches;

    private final Results results;

    private final Gateways gateways;

    private final BenchProperties properties;

    private final JsonMapper json;

    private int exitCode;

    public BenchCommands(BatchRunner batches, Results results, Gateways gateways, BenchProperties properties,
            JsonMapper json) {
        this.batches = batches;
        this.results = results;
        this.gateways = gateways;
        this.properties = properties;
        this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) {
        String command = one(args, "command", "check");
        try {
            switch (command) {
                case "check" -> check(args);
                case "catalog" -> catalog(args);
                case "run" -> runBatch(args);
                case "verify" -> verify(args);
                case "export" -> export(args);
                default -> throw new IllegalArgumentException("Unknown command " + command
                        + "; use check, catalog, run, verify, or export");
            }
        }
        catch (RuntimeException e) {
            log.error("{} failed: {}", command, Redact.secrets(e.getMessage()));
            this.exitCode = 1;
        }
    }

    @Override
    public int getExitCode() {
        return this.exitCode;
    }

    private void check(ApplicationArguments args) {
        for (String arm : list(args, "arms", "endpoint,workflow")) {
            Map<String, Object> info = this.batches.arm(arm);
            log.info("{}: catalog {} (snapshot {}), {} tool definitions, protocol {}", arm, info.get("catalogSha256"),
                    info.get("snapshotSha256"), ((List<?>) info.get("toolDefinitions")).size(),
                    info.get("protocolVersion"));
        }
        boolean allUsable = true;
        for (String key : list(args, "models", String.join(",", this.gateways.all().keySet()))) {
            ModelGateway model = this.gateways.get(key);
            try {
                this.batches.preflight(model);
                log.info("{}: {} answers", key, model.spec().model());
            }
            catch (RuntimeException e) {
                allUsable = false;
                log.warn("{}", e.getMessage());
            }
        }
        if (!allUsable) {
            this.exitCode = 2;
        }
    }

    private void catalog(ApplicationArguments args) {
        boolean force = args.containsOption("force");
        for (String arm : list(args, "arms", "workflow")) {
            Path file = Catalogs.snapshot(Path.of(this.properties.fixturesDir(), "catalogs"), arm);
            if (Files.exists(file) && !force) {
                throw new IllegalStateException(file + " exists; catalogs are frozen by hash. Use --force to replace it.");
            }
            try (McpSession session = McpSession.open(this.properties.servers().get(arm), this.json)) {
                var tools = session.listTools();
                Catalogs.write(file, tools);
                log.info("Wrote {} with {} tools, sha256 {}", file, tools.size(), Catalogs.sha256(tools));
            }
        }
    }

    private void runBatch(ApplicationArguments args) {
        String phase = one(args, "phase", "pilot");
        List<String> models = list(args, "models", "qwen");
        BatchRunner.Request request = new BatchRunner.Request(phase,
                one(args, "batch", phase + "-" + String.join("-", models)), models,
                list(args, "arms", "endpoint,workflow"), list(args, "splits", "dev"), list(args, "tasks", ""),
                Integer.parseInt(one(args, "reps", "3")), Long.parseLong(one(args, "seed", "20261106")));
        this.batches.run(request);
    }

    private void verify(ApplicationArguments args) {
        List<Exporter.Row> rows = this.results.verify(one(args, "phase", "pilot"), batches(args));
        Map<String, List<Exporter.Row>> groups = new TreeMap<>(rows.stream()
                .collect(Collectors.groupingBy(r -> r.run().model() + " " + r.run().arm() + " " + r.run().split())));
        groups.forEach((group, members) -> {
            long valid = members.stream().filter(r -> r.verification().valid()).count();
            long succeeded = members.stream().filter(r -> r.verification().success()).count();
            log.info("{}: {} of {} valid runs succeeded", group, succeeded, valid);
        });
        rows.stream().filter(r -> r.verification().valid() && !r.verification().success()).forEach(r -> log.info(
                "  {} failed: {}", r.run().runId(), r.verification().failures().stream()
                        .filter(Verification.Failure::failsTask)
                        .map(f -> f.failureClass() + " (" + f.detail() + ")").collect(Collectors.joining("; "))));
    }

    private void export(ApplicationArguments args) {
        String phase = one(args, "phase", "pilot");
        List<String> batches = batches(args);
        log.info("Wrote {}", this.results.export(phase, batches, one(args, "name", String.join("+", batches))));
    }

    /** {@code --batches=a,b}, or the single {@code --batch}. */
    private static List<String> batches(ApplicationArguments args) {
        List<String> batches = list(args, "batches", one(args, "batch", ""));
        if (batches.isEmpty()) {
            throw new IllegalArgumentException("Name the batch with --batch or --batches");
        }
        return batches;
    }

    private static String one(ApplicationArguments args, String name, String fallback) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? fallback : values.get(0);
    }

    private static List<String> list(ApplicationArguments args, String name, String fallback) {
        return Arrays.stream(one(args, name, fallback).split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .toList();
    }
}
