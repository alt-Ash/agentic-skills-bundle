package dev.dorrian.usagestore;

import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The global usage database. One row per hook event in {@code events}, one row per session in
 * {@code sessions}. Safe for many concurrent short-lived writers (WAL + busy timeout); writes are
 * a single transaction and idempotent on {@code event_id}.
 *
 * <p>Callers on the hook hot path must treat every {@link UsageStoreException} as non-fatal.
 */
public final class UsageDb implements AutoCloseable {

    public static final String ENV_DB_PATH = "AGENTIC_SKILLS_DB";
    public static final int SCHEMA_VERSION = 1;
    public static final String DROPPED_EVENTS = "dropped_events";
    private static final int BUSY_TIMEOUT_MS = 2000;
    private static final int OPEN_ATTEMPTS = 8;

    private final Connection connection;

    private UsageDb(Connection connection) {
        this.connection = connection;
    }

    /** {@code $AGENTIC_SKILLS_DB} if set, else {@code ~/.agentic-skills/data/usage.db}. */
    public static Path defaultPath() {
        String override = System.getenv(ENV_DB_PATH);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".agentic-skills", "data", "usage.db");
    }

    /**
     * Opens for writing, creating the file and migrating the schema as needed. Many hook JVMs can
     * race to create the database, so a busy result is retried briefly before giving up.
     */
    public static UsageDb open(Path path) {
        SQLException last = null;
        for (int attempt = 0; attempt < OPEN_ATTEMPTS; attempt++) {
            try {
                return openOnce(path);
            } catch (SQLException e) {
                if (!isBusy(e)) throw wrap("open " + path, e);
                last = e;
                pause(attempt);
            } catch (IOException e) {
                throw wrap("open " + path, e);
            }
        }
        throw wrap("open " + path, last);
    }

    private static UsageDb openOnce(Path path) throws SQLException, IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        // journal_mode is deliberately not set via SQLiteConfig: it applies that pragma before
        // busy_timeout, so concurrent first-time opens would fail instantly instead of waiting.
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(BUSY_TIMEOUT_MS);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.enforceForeignKeys(true);
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + path, config.toProperties());
        try {
            UsageDb db = new UsageDb(c);
            db.enableWal();
            db.migrate();
            return db;
        } catch (SQLException | RuntimeException e) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // the original failure is the one that matters
            }
            throw e;
        }
    }

    /** Switches to WAL only if needed; the switch needs a brief exclusive lock, so it is the racy step. */
    private void enableWal() throws SQLException {
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("PRAGMA journal_mode")) {
            if (rs.next() && "wal".equalsIgnoreCase(rs.getString(1))) return;
        }
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode = WAL");
        }
    }

    private static boolean isBusy(SQLException e) {
        String message = e.getMessage();
        return message != null && (message.contains("SQLITE_BUSY") || message.contains("SQLITE_LOCKED"));
    }

    private static void pause(int attempt) {
        try {
            Thread.sleep(20L + (long) (Math.random() * 30) * (attempt + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Opens an existing database read-only, for the dashboard. Never creates or migrates. */
    public static UsageDb openReadOnly(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new UsageStoreException("No usage database at " + path, null);
        }
        try {
            SQLiteConfig config = new SQLiteConfig();
            config.setReadOnly(true);
            config.setBusyTimeout(BUSY_TIMEOUT_MS);
            return new UsageDb(DriverManager.getConnection("jdbc:sqlite:" + path, config.toProperties()));
        } catch (SQLException e) {
            throw wrap("open read-only " + path, e);
        }
    }

    public Connection connection() {
        return connection;
    }

    // ─── Schema ─────────────────────────────────────────────────────────────

    private void migrate() throws SQLException {
        int version = userVersion();
        if (version > SCHEMA_VERSION) {
            throw new UsageStoreException("Database schema v" + version + " is newer than this build (v"
                + SCHEMA_VERSION + "); upgrade agentic-skills", null);
        }
        if (version == SCHEMA_VERSION) {
            return;
        }
        // IF NOT EXISTS everywhere: two hooks may race through first-time migration.
        try (Statement s = connection.createStatement()) {
            s.execute("BEGIN IMMEDIATE");
            try {
                for (String ddl : SchemaV1.STATEMENTS) {
                    s.execute(ddl);
                }
                s.execute("PRAGMA user_version = " + SCHEMA_VERSION);
                s.execute("COMMIT");
            } catch (SQLException e) {
                s.execute("ROLLBACK");
                throw e;
            }
        }
    }

    private int userVersion() throws SQLException {
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    // ─── Writes ─────────────────────────────────────────────────────────────

    /**
     * Stores one event and upserts its session, in one transaction. A repeated {@code eventId}
     * is ignored. Assigns an {@code eventId} if the event has none. Returns true if a new row
     * was inserted.
     */
    public boolean record(UsageEvent e) {
        if (e.eventId == null) {
            e.eventId = UUID.randomUUID().toString();
        }
        try {
            connection.setAutoCommit(false);
            try {
                boolean inserted = insertEvent(e);
                if (inserted) {
                    insertGitRows(e);
                    upsertSession(e);
                }
                connection.commit();
                return inserted;
            } catch (SQLException | RuntimeException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw wrap("record event " + e.eventId, ex);
        }
    }

    private boolean insertEvent(UsageEvent e) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement(SchemaV1.INSERT_EVENT)) {
            int i = 1;
            p.setString(i++, e.eventId);
            p.setString(i++, e.ts);
            p.setString(i++, e.event);
            p.setString(i++, e.sessionId);
            p.setString(i++, e.provider);
            p.setString(i++, e.user);
            p.setString(i++, e.project);
            p.setString(i++, e.client);
            p.setString(i++, e.cwd);
            p.setString(i++, e.source);
            p.setString(i++, e.reason);
            p.setString(i++, e.model);
            setInt(p, i++, e.inputTokens);
            setInt(p, i++, e.cachedTokens);
            p.setString(i++, e.toolName);
            p.setString(i++, e.toolUseId);
            p.setString(i++, e.error);
            setLong(p, i++, e.durationMs);
            setBool(p, i++, e.stopHookActive);
            setInt(p, i++, e.lastMessageCharLength);
            setInt(p, i++, e.estimatedOutputTokens);
            setInt(p, i++, e.backgroundTaskCount);
            setInt(p, i++, e.promptCharLength);
            setInt(p, i++, e.estimatedInputTokens);
            p.setString(i++, e.permissionMode);
            p.setString(i++, e.promptId);
            p.setString(i++, e.gitStartCommit);
            setInt(p, i++, e.gitLinesAdded);
            setInt(p, i++, e.gitLinesDeleted);
            p.setString(i++, e.command);
            p.setString(i++, e.slashCommand);
            p.setString(i, e.guardRule);
            return p.executeUpdate() == 1;
        }
    }

    private void insertGitRows(UsageEvent e) throws SQLException {
        if (e.gitCommits != null && !e.gitCommits.isEmpty()) {
            try (PreparedStatement p = connection.prepareStatement(
                "INSERT INTO git_commits(event_id, hash, message) VALUES (?,?,?)")) {
                for (GitCommitInfo c : e.gitCommits) {
                    p.setString(1, e.eventId);
                    p.setString(2, c.hash);
                    p.setString(3, c.message);
                    p.addBatch();
                }
                p.executeBatch();
            }
        }
        insertFiles(e.eventId, "A", e.gitFilesAdded);
        insertFiles(e.eventId, "M", e.gitFilesModified);
        insertFiles(e.eventId, "D", e.gitFilesDeleted);
    }

    private void insertFiles(String eventId, String change, List<String> paths) throws SQLException {
        if (paths == null || paths.isEmpty()) return;
        try (PreparedStatement p = connection.prepareStatement(
            "INSERT INTO git_files(event_id, path, change) VALUES (?,?,?)")) {
            for (String path : paths) {
                p.setString(1, eventId);
                p.setString(2, path);
                p.setString(3, change);
                p.addBatch();
            }
            p.executeBatch();
        }
    }

    /** Fields already known win over nulls; started_at only moves earlier, ended_at is set by session_end. */
    private void upsertSession(UsageEvent e) throws SQLException {
        if (e.sessionId == null) return;
        boolean ends = "session_end".equals(e.event);
        try (PreparedStatement p = connection.prepareStatement(SchemaV1.UPSERT_SESSION)) {
            p.setString(1, e.sessionId);
            p.setString(2, e.user);
            p.setString(3, e.project);
            p.setString(4, e.client);
            p.setString(5, e.cwd);
            p.setString(6, e.gitStartCommit);
            p.setString(7, e.ts);
            p.setString(8, ends ? e.ts : null);
            p.executeUpdate();
        }
    }

    /** Best-effort counter in {@code meta}, e.g. {@link #DROPPED_EVENTS}. */
    public void incrementMeta(String key) {
        try (PreparedStatement p = connection.prepareStatement(
            "INSERT INTO meta(key, value) VALUES (?, 1) ON CONFLICT(key) DO UPDATE SET value = value + 1")) {
            p.setString(1, key);
            p.executeUpdate();
        } catch (SQLException ex) {
            throw wrap("increment " + key, ex);
        }
    }

    /** Deletes events with {@code ts} before the ISO-8601 cutoff, and sessions left with none. Returns events deleted. */
    public int pruneBefore(String isoCutoff) {
        try (PreparedStatement events = connection.prepareStatement("DELETE FROM events WHERE ts < ?");
             Statement sessions = connection.createStatement()) {
            events.setString(1, isoCutoff);
            int deleted = events.executeUpdate();
            sessions.executeUpdate(
                "DELETE FROM sessions WHERE NOT EXISTS (SELECT 1 FROM events e WHERE e.session_id = sessions.session_id)");
            return deleted;
        } catch (SQLException ex) {
            throw wrap("prune", ex);
        }
    }

    // ─── Reads ──────────────────────────────────────────────────────────────

    /** A session's stored identity and git baseline, or null if unknown. */
    public SessionRow session(String sessionId) {
        if (sessionId == null) return null;
        try (PreparedStatement p = connection.prepareStatement(
            "SELECT session_id, user_name, project, client, cwd, git_start_commit, started_at, ended_at"
                + " FROM sessions WHERE session_id = ?")) {
            p.setString(1, sessionId);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return null;
                return new SessionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8));
            }
        } catch (SQLException ex) {
            throw wrap("read session " + sessionId, ex);
        }
    }

    /** A session's events in time order, with git details rehydrated. */
    public List<UsageEvent> eventsForSession(String sessionId) {
        List<UsageEvent> out = new ArrayList<>();
        if (sessionId == null) return out;
        try (PreparedStatement p = connection.prepareStatement(
            "SELECT * FROM events WHERE session_id = ? ORDER BY ts, rowid")) {
            p.setString(1, sessionId);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    out.add(readEvent(rs));
                }
            }
            for (UsageEvent e : out) {
                loadGitRows(e);
            }
            return out;
        } catch (SQLException ex) {
            throw wrap("read events for " + sessionId, ex);
        }
    }

    public long countEvents() {
        return scalarLong("SELECT COUNT(*) FROM events");
    }

    public long meta(String key) {
        try (PreparedStatement p = connection.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            p.setString(1, key);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException ex) {
            throw wrap("read meta " + key, ex);
        }
    }

    private long scalarLong(String sql) {
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException ex) {
            throw wrap(sql, ex);
        }
    }

    private void loadGitRows(UsageEvent e) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("SELECT hash, message FROM git_commits WHERE event_id = ?")) {
            p.setString(1, e.eventId);
            try (ResultSet rs = p.executeQuery()) {
                List<GitCommitInfo> commits = new ArrayList<>();
                while (rs.next()) commits.add(new GitCommitInfo(rs.getString(1), rs.getString(2)));
                e.gitCommits = commits.isEmpty() ? null : commits;
            }
        }
        try (PreparedStatement p = connection.prepareStatement("SELECT path, change FROM git_files WHERE event_id = ?")) {
            p.setString(1, e.eventId);
            try (ResultSet rs = p.executeQuery()) {
                List<String> added = new ArrayList<>();
                List<String> modified = new ArrayList<>();
                List<String> deleted = new ArrayList<>();
                while (rs.next()) {
                    switch (rs.getString(2)) {
                        case "A" -> added.add(rs.getString(1));
                        case "M" -> modified.add(rs.getString(1));
                        default -> deleted.add(rs.getString(1));
                    }
                }
                e.gitFilesAdded = added.isEmpty() ? null : added;
                e.gitFilesModified = modified.isEmpty() ? null : modified;
                e.gitFilesDeleted = deleted.isEmpty() ? null : deleted;
            }
        }
    }

    private static UsageEvent readEvent(ResultSet rs) throws SQLException {
        UsageEvent e = new UsageEvent();
        e.eventId = rs.getString("event_id");
        e.ts = rs.getString("ts");
        e.event = rs.getString("event");
        e.sessionId = rs.getString("session_id");
        e.provider = rs.getString("provider");
        e.user = rs.getString("user_name");
        e.project = rs.getString("project");
        e.client = rs.getString("client");
        e.cwd = rs.getString("cwd");
        e.source = rs.getString("source");
        e.reason = rs.getString("reason");
        e.model = rs.getString("model");
        e.inputTokens = getInt(rs, "input_tokens");
        e.cachedTokens = getInt(rs, "cached_tokens");
        e.toolName = rs.getString("tool_name");
        e.toolUseId = rs.getString("tool_use_id");
        e.error = rs.getString("error");
        long duration = rs.getLong("duration_ms");
        e.durationMs = rs.wasNull() ? null : duration;
        int stopActive = rs.getInt("stop_hook_active");
        e.stopHookActive = rs.wasNull() ? null : stopActive != 0;
        e.lastMessageCharLength = getInt(rs, "last_message_char_length");
        e.estimatedOutputTokens = getInt(rs, "estimated_output_tokens");
        e.backgroundTaskCount = getInt(rs, "background_task_count");
        e.promptCharLength = getInt(rs, "prompt_char_length");
        e.estimatedInputTokens = getInt(rs, "estimated_input_tokens");
        e.permissionMode = rs.getString("permission_mode");
        e.promptId = rs.getString("prompt_id");
        e.gitStartCommit = rs.getString("git_start_commit");
        e.gitLinesAdded = getInt(rs, "git_lines_added");
        e.gitLinesDeleted = getInt(rs, "git_lines_deleted");
        e.command = rs.getString("command");
        e.slashCommand = rs.getString("slash_command");
        e.guardRule = rs.getString("guard_rule");
        return e;
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private static Integer getInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static void setInt(PreparedStatement p, int i, Integer v) throws SQLException {
        if (v == null) p.setNull(i, Types.INTEGER); else p.setInt(i, v);
    }

    private static void setLong(PreparedStatement p, int i, Long v) throws SQLException {
        if (v == null) p.setNull(i, Types.INTEGER); else p.setLong(i, v);
    }

    private static void setBool(PreparedStatement p, int i, Boolean v) throws SQLException {
        if (v == null) p.setNull(i, Types.INTEGER); else p.setInt(i, v ? 1 : 0);
    }

    private static UsageStoreException wrap(String what, Exception cause) {
        if (cause instanceof UsageStoreException u) return u;
        return new UsageStoreException("Usage store: could not " + what + ": " + cause.getMessage(), cause);
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // nothing useful to do on close
        }
    }

    /** A session's identity and baseline, as stored. */
    public record SessionRow(String sessionId, String user, String project, String client, String cwd,
                             String gitStartCommit, String startedAt, String endedAt) {
    }
}
