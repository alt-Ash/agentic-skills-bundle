package dev.dorrian.agenticskillshooks;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dorrian.usagestore.UsageDb;
import dev.dorrian.usagestore.UsageEvent;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared helper for black-box ITs of the packaged hooks jar. Each IT class should use this rather
 * than copy helpers, and put its tests in its own *IT file. Every run gets a private database next
 * to (never inside) the hook's working directory and no analytics endpoint.
 */
public final class HookJarHarness {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path HOOKS_JAR = Path.of(System.getProperty("hooks.jar", "target/agentic-skills-hooks.jar"));

    private HookJarHarness() {
    }

    /** Outcome of one hook process. */
    public record Result(int exitCode, String stdout, String stderr) {
    }

    public static Path jar() {
        assertTrue(Files.isRegularFile(HOOKS_JAR), "packaged jar not found: " + HOOKS_JAR.toAbsolutePath());
        return HOOKS_JAR.toAbsolutePath();
    }

    /** This run's database file: a sibling of {@code workDir}, so it never shows up in the repo under test. */
    public static Path dbFor(Path workDir) {
        return workDir.resolveSibling(workDir.getFileName() + "-usage.db");
    }

    /** A private, empty home for the hook JVM, so a developer's real ~/.agentic-skills/guard.json never leaks into tests. */
    public static Path homeFor(Path workDir) {
        return workDir.resolveSibling(workDir.getFileName() + "-home");
    }

    public static Result run(Path workDir, String hookType, Object payload) throws Exception {
        return run(workDir, hookType, payload, Map.of());
    }

    public static Result run(Path workDir, String hookType, Object payload, Map<String, String> extraEnv) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("java", "-Duser.home=" + homeFor(workDir), "-jar", jar().toString(), hookType).directory(workDir.toFile());
        pb.environment().remove("ANALYTICS_SERVICE_URL");
        pb.environment().put(UsageDb.ENV_DB_PATH, dbFor(workDir).toString());
        pb.environment().putAll(extraEnv);
        Path out = Files.createTempFile("hook-out", ".txt");
        Path err = Files.createTempFile("hook-err", ".txt");
        try {
            pb.redirectOutput(out.toFile()).redirectError(err.toFile());
            Process process = pb.start();
            try (OutputStream stdin = process.getOutputStream()) {
                JSON.writeValue(stdin, payload);
            }
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "hook process timed out: " + hookType);
            return new Result(process.exitValue(), Files.readString(out, StandardCharsets.UTF_8),
                Files.readString(err, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
    }

    /** All events this test's database holds, oldest first; empty if nothing was written. */
    public static List<UsageEvent> events(Path workDir) {
        if (!Files.exists(dbFor(workDir))) return List.of();
        try (UsageDb db = UsageDb.openReadOnly(dbFor(workDir))) {
            return db.allEvents();
        }
    }
}
