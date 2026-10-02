package dev.dorrian.agenticskillscli.dashboard;

import dev.dorrian.usagestore.UsageDb;
import dev.dorrian.usagestore.UsageStoreException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only aggregate queries behind the dashboard API. "Context tokens" for a tool call is
 * {@code input_tokens + cached_tokens} of the last assistant message the hook saw — a snapshot of
 * how big the context was, not a billed total — so sums are labelled "processed", not spend.
 */
final class DashboardQueries {

    // GROUP BY uses ordinals throughout: SQLite resolves a bare name to the events *column* before
    // a SELECT alias, so "GROUP BY command" would silently group by the raw command, not the alias.

    /** input + cached, only meaningful on tool_use rows. */
    private static final String CONTEXT = "(COALESCE(e.input_tokens,0) + COALESCE(e.cached_tokens,0))";
    private static final String TOOL_CONTEXT = "CASE WHEN e.event = 'tool_use' THEN " + CONTEXT + " END";

    private final UsageDb db;

    DashboardQueries(UsageDb db) {
        this.db = db;
    }

    Map<String, Object> filters() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projects", column("SELECT DISTINCT project FROM events WHERE project IS NOT NULL ORDER BY project"));
        out.put("models", column("SELECT DISTINCT model FROM events WHERE model IS NOT NULL ORDER BY model"));
        Map<String, Object> range = one("SELECT MIN(ts) AS first_ts, MAX(ts) AS last_ts FROM events e WHERE 1=1", Filters.NONE);
        out.put("range", range);
        out.put("health", health());
        return out;
    }

    Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("droppedEvents", db.meta(UsageDb.DROPPED_EVENTS));
        out.put("sessionEndsWithoutStart", db.meta("session_end_without_start"));
        out.put("totalEvents", db.countEvents());
        return out;
    }

    Map<String, Object> summary(Filters f) {
        Map<String, Object> row = one("""
            SELECT COUNT(DISTINCT e.session_id) AS sessions,
                   COALESCE(SUM(e.event = 'user_prompt'), 0) AS prompts,
                   COALESCE(SUM(e.event = 'tool_use'), 0) AS tool_calls,
                   COALESCE(SUM(e.event = 'tool_failure'), 0) AS tool_failures,
                   COALESCE(SUM(e.event = 'guard_block'), 0) AS guard_blocks,
                   COALESCE(SUM(e.event = 'turn_stop'), 0) AS turns,
                   AVG(%1$s) AS avg_context,
                   MAX(%1$s) AS peak_context,
                   COALESCE(SUM(%1$s), 0) AS tokens_processed,
                   COALESCE(SUM(CASE WHEN e.event = 'session_end' THEN e.git_lines_added END), 0) AS lines_added,
                   COALESCE(SUM(CASE WHEN e.event = 'session_end' THEN e.git_lines_deleted END), 0) AS lines_deleted
            FROM events e WHERE 1=1""".formatted(TOOL_CONTEXT), f);
        long calls = ((Number) row.get("tool_calls")).longValue();
        long failures = ((Number) row.get("tool_failures")).longValue();
        row.put("failure_rate", calls + failures == 0 ? 0.0 : (double) failures / (calls + failures));
        return row;
    }

    List<Map<String, Object>> timeseries(Filters f) {
        return rows("""
            SELECT substr(e.ts, 1, 10) AS day,
                   SUM(e.event = 'user_prompt') AS prompts,
                   SUM(e.event = 'tool_use') AS tool_calls,
                   SUM(e.event = 'tool_failure') AS tool_failures,
                   SUM(e.event = 'guard_block') AS guard_blocks,
                   COUNT(DISTINCT e.session_id) AS sessions,
                   COALESCE(SUM(%s), 0) AS tokens_processed
            FROM events e WHERE 1=1""".formatted(TOOL_CONTEXT), f, "GROUP BY 1 ORDER BY 1");
    }

    List<Map<String, Object>> models(Filters f) {
        return rows("""
            SELECT COALESCE(e.model, 'unknown') AS model,
                   COUNT(*) AS tool_calls,
                   COALESCE(SUM(%1$s), 0) AS tokens_processed,
                   AVG(%1$s) AS avg_context,
                   MAX(%1$s) AS peak_context
            FROM events e WHERE e.event = 'tool_use'""".formatted(CONTEXT), f, "GROUP BY 1 ORDER BY tool_calls DESC");
    }

    /** Per tool: successful calls, failures and rate. Older rows without a recorded tool name group as "(not recorded)". */
    List<Map<String, Object>> tools(Filters f) {
        List<Map<String, Object>> rows = rows("""
            SELECT COALESCE(e.tool_name, '(not recorded)') AS tool,
                   SUM(e.event = 'tool_use') AS calls,
                   SUM(e.event = 'tool_failure') AS failures,
                   AVG(CASE WHEN e.event = 'tool_use' THEN e.duration_ms END) AS avg_duration_ms
            FROM events e WHERE e.event IN ('tool_use', 'tool_failure')""", f, "GROUP BY 1 ORDER BY calls + failures DESC");
        for (Map<String, Object> r : rows) {
            long calls = ((Number) r.get("calls")).longValue();
            long failures = ((Number) r.get("failures")).longValue();
            r.put("failure_rate", calls + failures == 0 ? 0.0 : (double) failures / (calls + failures));
        }
        return rows;
    }

    List<Map<String, Object>> errors(Filters f) {
        return rows("""
            SELECT COALESCE(e.tool_name, '(not recorded)') AS tool, substr(e.error, 1, 140) AS error, COUNT(*) AS count
            FROM events e WHERE e.event = 'tool_failure' AND e.error IS NOT NULL""", f,
            "GROUP BY 1, 2 ORDER BY count DESC LIMIT 15");
    }

    /** First word of recorded Bash commands (already secret-redacted at capture time). */
    List<Map<String, Object>> bashCommands(Filters f) {
        return rows("""
            SELECT CASE WHEN instr(e.command, ' ') > 0 THEN substr(e.command, 1, instr(e.command, ' ') - 1) ELSE e.command END AS command,
                   COUNT(*) AS count,
                   SUM(e.event = 'tool_failure') AS failures
            FROM events e WHERE e.command IS NOT NULL AND e.event IN ('tool_use', 'tool_failure')""", f,
            "GROUP BY 1 ORDER BY count DESC LIMIT 20");
    }

    List<Map<String, Object>> slashCommands(Filters f) {
        return rows("""
            SELECT e.slash_command AS command, COUNT(*) AS count, COUNT(DISTINCT e.session_id) AS sessions
            FROM events e WHERE e.event = 'user_prompt' AND e.slash_command IS NOT NULL""", f,
            "GROUP BY 1 ORDER BY count DESC");
    }

    List<Map<String, Object>> sessions(Filters f) {
        return rows("""
            SELECT s.session_id AS session_id, s.project AS project, s.user_name AS user, s.started_at AS started_at, s.ended_at AS ended_at,
                   COALESCE(SUM(e.event = 'user_prompt'), 0) AS prompts,
                   COALESCE(SUM(e.event = 'tool_use'), 0) AS tool_calls,
                   COALESCE(SUM(e.event = 'tool_failure'), 0) AS failures,
                   MAX(%1$s) AS peak_context,
                   COALESCE(SUM(%1$s), 0) AS tokens_processed,
                   COALESCE(SUM(CASE WHEN e.event = 'session_end' THEN e.git_lines_added END), 0) AS lines_added,
                   COALESCE(SUM(CASE WHEN e.event = 'session_end' THEN e.git_lines_deleted END), 0) AS lines_deleted
            FROM events e JOIN sessions s ON s.session_id = e.session_id WHERE 1=1""".formatted(TOOL_CONTEXT), f,
            "GROUP BY s.session_id ORDER BY s.started_at DESC LIMIT 300");
    }

    List<Map<String, Object>> sessionEvents(String sessionId) {
        return query("""
            SELECT e.ts AS ts, e.event AS event, e.tool_name AS tool, e.command AS command, e.error AS error,
                   e.slash_command AS slash_command, e.guard_rule AS guard_rule, e.model AS model,
                   %s AS context_tokens, e.prompt_char_length AS prompt_chars, e.estimated_output_tokens AS output_tokens_est
            FROM events e WHERE e.session_id = ? ORDER BY e.ts, e.rowid LIMIT 2000""".formatted(CONTEXT), List.of(sessionId));
    }

    List<Map<String, Object>> guardByRule(Filters f) {
        return rows("""
            SELECT e.guard_rule AS rule, COALESCE(e.tool_name, '?') AS tool, COUNT(*) AS blocks, MAX(e.ts) AS last
            FROM events e WHERE e.event = 'guard_block'""", f, "GROUP BY 1, 2 ORDER BY blocks DESC");
    }

    List<Map<String, Object>> guardRecent(Filters f) {
        return rows("""
            SELECT e.ts AS ts, e.project AS project, e.guard_rule AS rule, e.tool_name AS tool, e.command AS subject
            FROM events e WHERE e.event = 'guard_block'""", f, "ORDER BY e.ts DESC LIMIT 50");
    }

    List<Map<String, Object>> hotFiles(Filters f) {
        return rows("""
            SELECT gf.path AS path, COUNT(*) AS changes, COUNT(DISTINCT e.session_id) AS sessions
            FROM git_files gf JOIN events e ON e.event_id = gf.event_id WHERE gf.change IN ('A', 'M')""", f,
            "GROUP BY gf.path ORDER BY changes DESC, sessions DESC LIMIT 20");
    }

    // ─── plumbing ───────────────────────────────────────────────────────────

    private List<Map<String, Object>> rows(String selectWhere, Filters f, String tail) {
        Filters.Clause c = f.clause();
        return query(selectWhere + c.sql() + " " + tail, List.of(), c);
    }

    private Map<String, Object> one(String selectWhere, Filters f) {
        List<Map<String, Object>> rows = rows(selectWhere, f, "");
        return rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
    }

    private List<Object> column(String sql) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> r : query(sql, List.of())) out.add(r.values().iterator().next());
        return out;
    }

    private List<Map<String, Object>> query(String sql, List<String> params) {
        return query(sql, params, null);
    }

    private List<Map<String, Object>> query(String sql, List<String> params, Filters.Clause clause) {
        try (PreparedStatement p = db.connection().prepareStatement(sql)) {
            int i = 0;
            for (String param : params) p.setString(++i, param);
            if (clause != null) clause.bind(p, i);
            try (ResultSet rs = p.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= md.getColumnCount(); c++) {
                        row.put(md.getColumnLabel(c), rs.getObject(c));
                    }
                    out.add(row);
                }
                return out;
            }
        } catch (SQLException e) {
            throw new UsageStoreException("Dashboard query failed: " + e.getMessage(), e);
        }
    }
}
