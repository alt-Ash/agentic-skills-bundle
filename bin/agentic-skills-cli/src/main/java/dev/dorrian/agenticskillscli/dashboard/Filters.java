package dev.dorrian.agenticskillscli.dashboard;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The dashboard's {@code from}/{@code to}/{@code project}/{@code model} query filters. Dates are
 * validated as ISO local dates; values are only ever bound as parameters, never concatenated.
 */
record Filters(String from, String to, String project, String model) {

    static final Filters NONE = new Filters(null, null, null, null);

    /** Parses filters from decoded query parameters; throws IllegalArgumentException on a bad date. */
    static Filters parse(Map<String, String> query) {
        return new Filters(
            date(query.get("from"), 0),
            date(query.get("to"), 1),
            blankToNull(query.get("project")),
            blankToNull(query.get("model")));
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
