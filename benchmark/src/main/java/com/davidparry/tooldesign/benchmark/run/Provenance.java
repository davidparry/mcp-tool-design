package com.davidparry.tooldesign.benchmark.run;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

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
            versions.put(artifact[0], version(artifact[1], artifact[2]));
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

    private static String version(String group, String artifact) {
        String resource = "META-INF/maven/" + group + "/" + artifact + "/pom.properties";
        try (InputStream in = Provenance.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return manifestVersion(artifact);
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version", "unknown");
        }
        catch (IOException e) {
            return "unknown";
        }
    }

    /** Jars with no Maven metadata, like the Anthropic and OpenAI SDKs, name and version themselves in their manifest. */
    private static String manifestVersion(String artifact) throws IOException {
        Enumeration<URL> manifests = Provenance.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
        while (manifests.hasMoreElements()) {
            try (InputStream in = manifests.nextElement().openStream()) {
                Attributes main = new Manifest(in).getMainAttributes();
                if (artifact.equals(main.getValue(Attributes.Name.IMPLEMENTATION_TITLE))) {
                    String version = main.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
                    return version == null ? "unknown" : version;
                }
            }
        }
        return "unknown";
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
