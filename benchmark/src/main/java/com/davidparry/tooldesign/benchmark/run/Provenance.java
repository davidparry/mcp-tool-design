package com.davidparry.tooldesign.benchmark.run;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.SpringBootVersion;

/** Where a batch came from: the source commit and the versions of everything between the runner and the models. */
public final class Provenance {

    private static final String[][] ARTIFACTS = {
            { "springAi", "org.springframework.ai", "spring-ai-model" },
            { "mcpSdk", "io.modelcontextprotocol.sdk", "mcp-core" },
            { "anthropicSdk", "com.anthropic", "anthropic-java-core" },
            { "openaiSdk", "com.openai", "openai-java-core" } };

    private Provenance() {
    }

    public static Map<String, Object> versions() {
        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("java", System.getProperty("java.runtime.version"));
        versions.put("springBoot", SpringBootVersion.getVersion());
        for (String[] artifact : ARTIFACTS) {
            versions.put(artifact[0], pomVersion(artifact[1], artifact[2]));
        }
        return versions;
    }

    /** The current commit and whether the working tree has uncommitted changes. */
    public static Map<String, Object> source() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("commit", git("rev-parse", "HEAD"));
        String status = git("status", "--porcelain");
        source.put("dirty", status == null ? null : !status.isBlank());
        return source;
    }

    private static String pomVersion(String group, String artifact) {
        String resource = "META-INF/maven/" + group + "/" + artifact + "/pom.properties";
        try (InputStream in = Provenance.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return "unknown";
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version", "unknown");
        }
        catch (IOException e) {
            return "unknown";
        }
    }

    private static String git(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes()).strip();
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return null;
            }
            return output;
        }
        catch (IOException e) {
            return null;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
