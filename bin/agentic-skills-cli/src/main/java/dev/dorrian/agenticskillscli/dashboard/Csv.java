package dev.dorrian.agenticskillscli.dashboard;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Minimal RFC 4180 CSV writer with spreadsheet formula-injection protection: a text cell that
 * starts with {@code = + - @} or a tab/CR gets a leading single quote so Excel/Sheets show it
 * as text instead of evaluating it. Numbers are written verbatim (a negative number stays a number).
 */
final class Csv {

    private Csv() {
    }

    static byte[] write(List<String> columns, List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", columns)).append("\r\n");
        for (Map<String, Object> row : rows) {
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(cell(row.get(columns.get(i))));
            }
            sb.append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String cell(Object value) {
        if (value == null) return "";
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        String s = String.valueOf(value);
        if (!s.isEmpty()) {
            char c = s.charAt(0);
            if (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r') {
                s = "'" + s;
            }
        }
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
