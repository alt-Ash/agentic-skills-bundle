package dev.dorrian.agenticskillshooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Java port of hooks/lib/event-log.ts's file-writing fan-out (appendEvent, appendHookTypeData,
 * appendSessionConsolidatedEvent, recordEvent). All paths resolve relative to the JVM process's
 * working directory (System.getProperty("user.dir")), never a payload field — matching the TS
 * behavior exactly. Each writer does its own read-modify-write with no locking, preserving the
 * same race condition the TS version has between concurrent hook invocations: this is a known,
 * pre-existing gap, deliberately not fixed as part of this port (see the migration plan).
 */
public final class EventLog {
    private static final String OUTPUT_FILE = "ai-usage-events.json";
    private static final String HOOKS_DATA_DIR = ".hooks-data";
    private static final String CONSOLIDATED_FILE = "hooks-events.json";
    public static final int MAX_SESSION_GROUPS = 50;

    private EventLog() {
    }

    private static Path cwdFile(String name) {
        return Paths.get(System.getProperty("user.dir"), name);
    }

    private static List<JsonNode> readExistingEvents(Path path) {
        try {
            if (!Files.exists(path)) return new ArrayList<>();
            JsonNode parsed = JsonSupport.MAPPER.readTree(Files.readString(path));
            List<JsonNode> list = new ArrayList<>();
            if (parsed != null && parsed.isArray()) {
                parsed.forEach(list::add);
            }
            return list;
        } catch (Exception ignored) {
            // missing file, unreadable, or corrupt JSON — start fresh
            return new ArrayList<>();
        }
    }

    private static void writeJsonArray(Path path, List<?> items) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        String json = JsonSupport.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(items);
        Files.writeString(path, json);
    }

    /** Returns the full path to the output file, matching TS appendEvent's return value. */
    public static Path appendEvent(UsageEvent event) {
        Path path = cwdFile(OUTPUT_FILE);
        try {
            List<JsonNode> events = readExistingEvents(path);
            events.add(JsonSupport.MAPPER.valueToTree(event));
            writeJsonArray(path, events);
        } catch (Exception ignored) {
            // never let telemetry break the host CLI
        }
        AnalyticsServiceClient.pushEvent(event);
        return path;
    }

    // One array per hook type under `.hooks-data/<hookType>.json`, so a single hook's raw
    // output can be inspected without filtering the combined log.
    public static Path appendHookTypeData(String hookType, UsageEvent event) {
        Path path = Paths.get(System.getProperty("user.dir"), HOOKS_DATA_DIR, hookType + ".json");
        try {
            Files.createDirectories(path.getParent());
            List<JsonNode> events = readExistingEvents(path);
            events.add(JsonSupport.MAPPER.valueToTree(event));
            writeJsonArray(path, events);
        } catch (Exception ignored) {
        }
        return path;
    }

    // Groups every event by sessionId into hooks-events.json. Debug/analytics view only —
    // nothing reads it back for identity-cache or gitStartCommit lookups (see
    // SessionBaselineStore), so its per-session growth doesn't affect hook latency.
    public static Path appendSessionConsolidatedEvent(UsageEvent event) {
        Path path = cwdFile(CONSOLIDATED_FILE);
        try {
            List<JsonNode> rawGroups = readExistingEvents(path);
            List<ObjectNode> groups = new ArrayList<>();
            for (JsonNode n : rawGroups) {
                if (n.isObject()) groups.add((ObjectNode) n);
            }

            String key = event.sessionId != null ? event.sessionId : "unknown";
            ObjectNode match = null;
            for (ObjectNode g : groups) {
                JsonNode sid = g.get("sessionId");
                if (sid != null && key.equals(sid.asText())) {
                    match = g;
                    break;
                }
            }
            if (match != null) {
                ArrayNode hooks = (ArrayNode) match.get("hooks");
                hooks.add(JsonSupport.MAPPER.valueToTree(event));
            } else {
                ObjectNode newGroup = JsonSupport.MAPPER.createObjectNode();
                newGroup.put("sessionId", key);
                ArrayNode hooks = newGroup.putArray("hooks");
                hooks.add(JsonSupport.MAPPER.valueToTree(event));
                groups.add(newGroup);
            }
            // Evict the oldest (by insertion order) session groups once over the cap.
            while (groups.size() > MAX_SESSION_GROUPS) {
                groups.remove(0);
            }
            writeJsonArray(path, groups);
        } catch (Exception ignored) {
        }
        return path;
    }

    // Looks up a session's prior hook events from the consolidated log — used by SessionHook
    // to check for a missing session_start before pushing the group to the analytics service.
    public static List<UsageEvent> readSessionGroup(String sessionId) {
        if (sessionId == null) return List.of();
        try {
            Path path = cwdFile(CONSOLIDATED_FILE);
            if (!Files.exists(path)) return List.of();
            JsonNode parsed = JsonSupport.MAPPER.readTree(Files.readString(path));
            if (parsed != null && parsed.isArray()) {
                for (JsonNode g : parsed) {
                    JsonNode sid = g.get("sessionId");
                    if (sid != null && sessionId.equals(sid.asText())) {
                        List<UsageEvent> hooks = new ArrayList<>();
                        JsonNode hooksNode = g.get("hooks");
                        if (hooksNode != null && hooksNode.isArray()) {
                            for (JsonNode h : hooksNode) {
                                hooks.add(JsonSupport.MAPPER.treeToValue(h, UsageEvent.class));
                            }
                        }
                        return hooks;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return List.of();
    }

    // Fan-out used by every hook entrypoint: the flat analytics log, the per-hook-type debug
    // capture, the session-consolidated view, and the session baseline cache all derive from
    // the same event object. TS runs these four writers concurrently via Promise.all; each is
    // independently try/caught so one writer's failure never skips the others.
    public static void recordEvent(String hookType, UsageEvent event) {
        if (event.eventId == null) {
            event.eventId = UUID.randomUUID().toString();
        }
        CompletableFuture<Void> f1 = CompletableFuture.runAsync(safely(() -> appendEvent(event)));
        CompletableFuture<Void> f2 = CompletableFuture.runAsync(safely(() -> appendHookTypeData(hookType, event)));
        CompletableFuture<Void> f3 = CompletableFuture.runAsync(safely(() -> appendSessionConsolidatedEvent(event)));
        CompletableFuture<Void> f4 = CompletableFuture.runAsync(safely(() -> SessionBaselineStore.update(event)));
        CompletableFuture.allOf(f1, f2, f3, f4).join();
    }

    private static Runnable safely(Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (Exception ignored) {
            }
        };
    }
}
