package dev.dorrian.agenticskillscli.dashboard;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The dashboard's {@code from}/{@code to}/{@code project}/{@code model} query filters. Dates are
 * validated as ISO local dates; values are only ever bound as parameters, never concatenated.
 */
record Filters(String from, String to, String project, String model, boolean interactive) {

    static final Filters NONE = new Filters(null, null, null, null, false);

    /** Sessions with at least this many prompts count as "substantive" (or any with a tool call). */
    static final int MIN_PROMPTS = 2;

    /**
     * Whole-session test, written once and reused by every query: the session id must belong to a
     * session with >= 2 prompts or >= 1 tool call. Uncorrelated, so SQLite evaluates it once.
     */
    private static final String INTERACTIVE_SQL = " AND e.session_id IN (SELECT i.session_id FROM events i"
        + " WHERE i.session_id IS NOT NULL GROUP BY 1"
        + " HAVING SUM(i.event = 'user_prompt') >= " + MIN_PROMPTS + " OR SUM(i.event = 'tool_use') >= 1)";

    /** Parses filters from decoded query parameters; throws IllegalArgumentException on a bad date. */
    static Filters parse(Map<String, String> query) {
        return new Filters(
            date(query.get("from"), 0),
            date(query.get("to"), 1),
            blankToNull(query.get("project")),
            blankToNull(query.get("model")),
            flag(query.get("interactive")));
    }

    /** Validates {@code compare}: absent or {@code prev}; anything else is a client error. */
    static boolean comparePrev(Map<String, String> query) {
        String v = query.get("compare");
        if (v == null || v.isBlank()) return false;
        if (!"prev".equals(v.trim())) throw new IllegalArgumentException("Invalid compare value: " + v + " (expected prev)");
        return true;
    }

    private static boolean flag(String v) {
        return v != null && ("1".equals(v.trim()) || "true".equalsIgnoreCase(v.trim()));
    }

    /**
     * The window of equal length immediately before this one. Needs both {@code from} and
     * {@code to}; {@code from} is inclusive and {@code to} exclusive here (already shifted by parse).
     */
    Filters previous() {
        if (from == null || to == null) {
            throw new IllegalArgumentException("compare=prev needs both from and to");
        }
        LocalDate f = LocalDate.parse(from);
        long days = ChronoUnit.DAYS.between(f, LocalDate.parse(to));
        if (days <= 0) throw new IllegalArgumentException("compare=prev needs from to be before or equal to to");
        return new Filters(f.minusDays(days).toString(), from, project, model, interactive);
    }

    /** ISO date → the string bound against {@code ts}: {@code from} inclusive, {@code to} exclusive next day. */
    private static String date(String value, int plusDays) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim()).plusDays(plusDays).toString();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date: " + value);
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    /** SQL conditions (each starting with AND) over events aliased {@code e}, plus their bind values in order. */
    Clause clause() {
        StringBuilder sql = new StringBuilder();
        List<String> binds = new ArrayList<>();
        if (from != null) {
            sql.append(" AND e.ts >= ?");
            binds.add(from);
        }
        if (to != null) {
            sql.append(" AND e.ts < ?");
            binds.add(to);
        }
        if (project != null) {
            sql.append(" AND e.project = ?");
            binds.add(project);
        }
        if (model != null) {
            sql.append(" AND e.model = ?");
            binds.add(model);
        }
        if (interactive) {
            sql.append(INTERACTIVE_SQL);
        }
        return new Clause(sql.toString(), binds);
    }

    record Clause(String sql, List<String> binds) {
        /** Binds this clause's values starting after {@code alreadyBound} parameters. */
        void bind(PreparedStatement p, int alreadyBound) throws SQLException {
            for (int i = 0; i < binds.size(); i++) {
                p.setString(alreadyBound + i + 1, binds.get(i));
            }
        }
    }
}
