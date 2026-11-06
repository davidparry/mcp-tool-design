package com.davidparry.tooldesign.benchmark.run;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import tools.jackson.databind.json.JsonMapper;

import com.davidparry.tooldesign.benchmark.mcp.Catalogs;

/** Loads tasks from {@code fixtures/tasks/<split>/*.json}, sorted by ID. */
public final class Tasks {

    private Tasks() {
    }

    public static List<TaskSpec> load(Path tasksDir, List<String> splits, JsonMapper json) {
        List<TaskSpec> tasks = new ArrayList<>();
        for (String split : splits) {
            for (Path file : files(tasksDir.resolve(split))) {
                TaskSpec task = json.readValue(read(file), TaskSpec.class);
                if (!split.equals(task.split())) {
                    throw new IllegalStateException(file + " says split " + task.split() + " but is under " + split);
                }
                tasks.add(task);
            }
        }
        tasks.sort(Comparator.comparing(TaskSpec::id));
        return tasks;
    }

    /** SHA-256 over every task file's path and content, in path order. */
    public static String sha256(Path tasksDir) {
        return sha256Of(tasksDir);
    }

    /** SHA-256 over every JSON file under a directory, by relative path and content. */
    public static String sha256Of(Path dir) {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                all.append(dir.relativize(file)).append('\n').append(read(file)).append('\n');
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Catalogs.sha256Text(all.toString());
    }

    private static List<Path> files(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("No task directory " + dir);
        }
        try (Stream<Path> paths = Files.list(dir)) {
            return paths.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
