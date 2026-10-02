package dev.dorrian.agenticskillshooks;

import dev.dorrian.usagestore.UsageDb;
import dev.dorrian.usagestore.UsageEvent;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Where every hook's {@link UsageEvent} goes: one transaction into the global usage database
 * ({@link UsageDb#defaultPath()}), then the optional POST to ANALYTICS_SERVICE_URL. Hooks must
 * never block or fail the host CLI, so every store error is swallowed here; a dropped event bumps
 * a counter in {@code meta} (best effort) so the loss is visible in the dashboard.
 *
 * <p>The connection is opened lazily and shared for the lifetime of the JVM (one hook firing);
 * {@code HookDispatcher} calls {@link #close()} before exiting.
 */
public final class EventLog {
    public static final String SESSION_END_WITHOUT_START = "session_end_without_start";

    private static Path dbPathOverride;
    private static UsageDb db;
    private static boolean openFailed;

    private EventLog() {
    }

    /** Stores the event, then pushes it to the analytics service if one is configured. */
    public static synchronized void recordEvent(UsageEvent event) {
        if (event.eventId == null) {
            event.eventId = UUID.randomUUID().toString();
        }
        if (event.ts == null) {
            event.ts = Instant.now().toString();
        }
        if (event.cwd == null) {
            event.cwd = System.getProperty("user.dir");
        }
        try {
            UsageDb open = db();
            if (open == null) {
                throw new IllegalStateException("usage database unavailable");
            }
            open.record(event);
        } catch (Exception e) {
            countDropped();
        }
        AnalyticsServiceClient.pushEvent(event);
    }

    /** The stored session identity/baseline, or null if unknown or the store is unavailable. */
    public static synchronized UsageDb.SessionRow session(String sessionId) {
        try {
            UsageDb open = db();
            return open == null ? null : open.session(sessionId);
        } catch (Exception e) {
            return null;
        }
    }

    /** A session's stored events in time order; empty if unknown or the store is unavailable. */
    public static synchronized List<UsageEvent> sessionEvents(String sessionId) {
        try {
            UsageDb open = db();
            return open == null ? List.of() : open.eventsForSession(sessionId);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Best-effort counter bump in {@code meta}. */
    public static synchronized void bump(String key) {
        try {
            UsageDb open = db();
            if (open != null) open.incrementMeta(key);
        } catch (Exception ignored) {
            // counters are advisory
        }
    }

    public static synchronized void close() {
        if (db != null) {
            db.close();
            db = null;
        }
    }

    // Test seams: production resolves the path from the environment, never from a payload field.
    static synchronized void useDatabaseForTesting(Path path) {
        close();
        dbPathOverride = path;
        openFailed = false;
    }

    static synchronized void resetForTesting() {
        close();
        dbPathOverride = null;
        openFailed = false;
    }

    private static UsageDb db() {
        if (db != null) return db;
        if (openFailed) return null;
        try {
            db = UsageDb.open(dbPathOverride != null ? dbPathOverride : UsageDb.defaultPath());
        } catch (Exception e) {
            openFailed = true;
        }
        return db;
    }

    private static void countDropped() {
        try {
            if (db != null) db.incrementMeta(UsageDb.DROPPED_EVENTS);
        } catch (Exception ignored) {
            // the store itself is failing; nothing more we can do
        }
    }
}
