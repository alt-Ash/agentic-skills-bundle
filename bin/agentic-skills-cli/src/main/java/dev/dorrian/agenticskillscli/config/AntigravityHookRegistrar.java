package dev.dorrian.agenticskillscli.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dorrian.agenticskillscli.HomeDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers the hooks jar with Antigravity CLI through {@code ~/.gemini/config/hooks.json}, which every
 * Antigravity product (CLI, desktop, IDE) reads. The schema is the one Antigravity ships: each top-level key
 * is a <em>hook name</em> mapping to its events; tool events ({@code PreToolUse}/{@code PostToolUse}) use
 * grouped {@code {matcher, hooks}} entries and the others ({@code PreInvocation}, {@code Stop}) are flat lists of
 * handlers. Timeouts are seconds.
 *
 * <p>We own exactly one top-level key, {@value #HOOK_NAME}: registering rewrites that key and nothing else, and
 * unregistering removes it, so any other hook the user defined is never touched. A file that exists but cannot be
 * parsed is an error and is left untouched, never rewritten from an empty map.
 *
 * <p>No guard hook is registered for Antigravity: its {@code PreToolUse} has no "no opinion" answer, so a guard
 * would have to answer {@code allow} for everything it doesn't block, silently bypassing the user's own
 * permission prompts.
 */
public final class AntigravityHookRegistrar {

    public static final String HOOK_NAME = "agentic-skills";
    static final int DEFAULT_TIMEOUT_SECONDS = 30;
    /** Matches the verify gate's per-command cap plus headroom, like the Claude Code registration. */
    static final int VERIFY_TIMEOUT_SECONDS = 600;
    static final int CONTEXT_TIMEOUT_SECONDS = 10;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AntigravityHookRegistrar() {
    }

    public static Path defaultFile() {
        return HomeDir.resolve().resolve(".gemini").resolve("config").resolve("hooks.json");
    }

    /** Writes our hook entry, replacing any earlier version of it. */
    public static void register(Path file, Path hooksJar, boolean verify, boolean context) {
        Map<String, Object> config = read(file);
        config.put(HOOK_NAME, entry(hooksJar, verify, context));
        write(file, config);
    }

    /** Removes our hook entry. Returns 1 if it was there. An unparsable file is left exactly as it is (0). */
    public static int unregister(Path file) {
        Map<String, Object> config;
        try {
            config = read(file);
        } catch (UncheckedIOException e) {
            return 0;
        }
        if (config.remove(HOOK_NAME) == null) return 0;
        if (config.isEmpty()) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            write(file, config);
        }
        return 1;
    }

    public static boolean isRegistered(Path file) {
        try {
            return read(file).get(HOOK_NAME) instanceof Map<?, ?>;
        } catch (UncheckedIOException e) {
            return false;
        }
    }

    // ─── our entry ──────────────────────────────────────────────────────────

    private static Map<String, Object> entry(Path jar, boolean verify, boolean context) {
        Map<String, Object> hook = new LinkedHashMap<>();
        hook.put("enabled", true);

        hook.put("PostToolUse", List.of(grouped("*", handler(jar, "post-tool-use", DEFAULT_TIMEOUT_SECONDS))));
        // One JVM handles both analytics and (with --verify) the gate, so a stop costs a single process.
        hook.put("Stop", List.of(handler(jar, verify ? "stop --verify" : "stop",
            verify ? VERIFY_TIMEOUT_SECONDS : DEFAULT_TIMEOUT_SECONDS)));
        if (context) {
            hook.put("PreInvocation", List.of(handler(jar, "pre-invocation", CONTEXT_TIMEOUT_SECONDS)));
        }
        return hook;
    }

    private static Map<String, Object> grouped(String matcher, Map<String, Object> handler) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("matcher", matcher);
        group.put("hooks", new ArrayList<>(List.of(handler)));
        return group;
    }

    /** Antigravity runs {@code command} through {@code sh -c} (or {@code cmd /c}), so the jar path is quoted. */
    private static Map<String, Object> handler(Path jar, String event, int timeoutSeconds) {
        Map<String, Object> handler = new LinkedHashMap<>();
        handler.put("type", "command");
        handler.put("command", "java -jar \"" + jar.toAbsolutePath() + "\" agy " + event);
        handler.put("timeout", timeoutSeconds);
        return handler;
    }

    // ─── file access ────────────────────────────────────────────────────────

    /** A missing file is empty; an existing file that does not parse is an error (never rewritten from nothing). */
    private static Map<String, Object> read(Path file) {
        if (!Files.exists(file)) return new LinkedHashMap<>();
        try {
            return MAPPER.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (IOException e) {
            String message = e.getMessage() == null ? "invalid JSON" : e.getMessage().split("\\n", 2)[0];
            throw new UncheckedIOException("Cannot parse " + file + " (" + message
                + "). It was left untouched: fix or remove the syntax error and run the installer again.", e);
        }
    }

    private static void write(Path file, Map<String, Object> content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(content) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
