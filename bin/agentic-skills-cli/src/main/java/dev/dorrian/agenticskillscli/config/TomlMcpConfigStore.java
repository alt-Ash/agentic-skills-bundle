package dev.dorrian.agenticskillscli.config;

import dev.dorrian.agenticskillscli.registry.McpConfigDef;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Text-level editor for the {@code [mcp_servers.<name>]} tables of a TOML config file — Codex
 * CLI's {@code ~/.codex/config.toml} (learn.chatgpt.com/docs/extend/mcp). Used instead of a TOML
 * library round-trip because that would drop the user's comments and reformat the file, and
 * instead of {@code codex mcp add} because that needs the Codex CLI on PATH.
 *
 * <p>Guarantees: every byte outside the tables/keys belonging to the servers we touch is left as
 * is; the file (and its directory) is only ever created by an install/overwrite, never by a
 * read, uninstall or migration. A server counts as present however it is spelled — a
 * {@code [mcp_servers.name]} header (bare or quoted name, with or without
 * {@code [mcp_servers.name.sub]} sub-tables), a dotted key under {@code [mcp_servers]}
 * ({@code name.command = ...}), an inline table ({@code name = { ... }}), or a root-level dotted
 * key ({@code mcp_servers.name.command = ...}).
 *
 * <p>The scanner is a small TOML statement lexer (strings incl. multi-line, arrays and inline
 * tables spanning lines, comments) — just enough to find statement boundaries correctly, so a
 * header-looking line inside a multi-line string is never mistaken for a table.
 */
public final class TomlMcpConfigStore {

    /** {@link McpConfigDef#serverFormat()} value that routes a tool's MCP config here. */
    public static final String FORMAT = "toml";

    private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9_-]+");

    private TomlMcpConfigStore() {
    }

    public static boolean handles(McpConfigDef cfg) {
        return cfg != null && FORMAT.equals(cfg.serverFormat());
    }

    // ─── Public operations ──────────────────────────────────────────────────

    /** Appends each server not already present. Returns the names actually written. */
    public static Set<String> installIfAbsent(String rootKey, Map<String, Object> servers, Path file) {
        String text = read(file);
        Set<String> present = Files.exists(file) ? names(rootKey, text) : Set.of();
        Set<String> installed = new LinkedHashSet<>();
        StringBuilder sb = new StringBuilder(text);
        String nl = newline(text);
        for (Map.Entry<String, Object> e : servers.entrySet()) {
            if (present.contains(e.getKey()) || installed.contains(e.getKey())) continue;
            appendTable(sb, render(rootKey, e.getKey(), asMap(e.getValue())), nl);
            installed.add(e.getKey());
        }
        if (!installed.isEmpty()) write(file, sb.toString());
        return installed;
    }

    /** Removes every table/key belonging to each named server. Returns the names actually removed. */
    public static Set<String> uninstall(String rootKey, List<String> names, Path file) {
        if (!Files.exists(file)) return Set.of();
        String text = read(file);
        List<Span> spans = spans(rootKey, text);
        Set<String> removed = new LinkedHashSet<>();
        List<Edit> edits = new ArrayList<>();
        for (Span s : spans) {
            if (names.contains(s.name)) {
                edits.add(new Edit(removalStart(text, s), s.end, ""));
                removed.add(s.name);
            }
        }
        if (!removed.isEmpty()) write(file, apply(text, edits));
        return removed;
    }

    /** Unconditionally replaces (in place when it is a plain {@code [mcp_servers.name]} table) or appends a server. */
    public static void overwrite(String rootKey, String name, Map<String, Object> serverConfig, Path file) {
        String text = read(file);
        String nl = newline(text);
        String rendered = render(rootKey, name, serverConfig).replace("\n", nl);
        List<Span> mine = spans(rootKey, text).stream().filter(s -> s.name.equals(name)).toList();
        Span anchor = mine.stream().filter(s -> s.header && s.depth == 2).findFirst().orElse(null);

        List<Edit> edits = new ArrayList<>();
        for (Span s : mine) {
            edits.add(s == anchor ? new Edit(s.start, s.end, rendered) : new Edit(removalStart(text, s), s.end, ""));
        }
        String updated = apply(text, edits);
        if (anchor == null) {
            StringBuilder sb = new StringBuilder(updated);
            appendTable(sb, rendered, nl);
            updated = sb.toString();
        }
        write(file, updated);
    }

    /**
     * Replaces entries that still launch via {@code npx} (pre-2.0 configs) with the given config;
     * absent servers and user-customised entries are left alone. Returns the names replaced.
     */
    public static Set<String> migrateLegacyNpx(String rootKey, Map<String, Object> servers, Path file) {
        if (!Files.exists(file)) return Set.of();
        Set<String> replaced = new LinkedHashSet<>();
        for (Map.Entry<String, Object> e : servers.entrySet()) {
            String text = read(file);
            boolean legacy = spans(rootKey, text).stream()
                .filter(s -> s.name.equals(e.getKey()))
                .map(s -> text.substring(s.start, s.end))
                .anyMatch(t -> t.contains("\"npx\"") || t.contains("'npx'"));
            if (legacy) {
                overwrite(rootKey, e.getKey(), asMap(e.getValue()), file);
                replaced.add(e.getKey());
            }
        }
        return replaced;
    }

    public static boolean isRegistered(String rootKey, String name, Path file) {
        return serverNames(rootKey, file).contains(name);
    }

    /** Every server name defined under {@code rootKey}, in file order; empty if the file is missing. */
    public static Set<String> serverNames(String rootKey, Path file) {
        if (!Files.exists(file)) return Set.of();
        return names(rootKey, read(file));
    }

    // ─── Rendering ──────────────────────────────────────────────────────────

    /** Renders one {@code [root.name]} table (ending in a newline). Values: strings, numbers, booleans, lists, maps (inline tables). */
    static String render(String rootKey, String name, Map<String, Object> config) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(key(rootKey)).append('.').append(key(name)).append("]\n");
        for (Map.Entry<String, Object> e : config.entrySet()) {
            if (e.getValue() == null) continue;
            sb.append(key(e.getKey())).append(" = ").append(value(e.getValue())).append('\n');
        }
        return sb.toString();
    }

    static String key(String k) {
        return BARE_KEY.matcher(k).matches() ? k : basicString(k);
    }

    private static String value(Object v) {
        if (v instanceof String s) return basicString(s);
        if (v instanceof Boolean || v instanceof Integer || v instanceof Long) return v.toString();
        if (v instanceof Number n) return Double.toString(n.doubleValue());
        if (v instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object o : list) parts.add(value(o));
            return "[" + String.join(", ", parts) + "]";
        }
        if (v instanceof Map<?, ?> map) {
            if (map.isEmpty()) return "{}";
            List<String> parts = new ArrayList<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getValue() == null) continue;
                parts.add(key(String.valueOf(e.getKey())) + " = " + value(e.getValue()));
            }
            return "{ " + String.join(", ", parts) + " }";
        }
        return basicString(String.valueOf(v));
    }

    /** TOML basic string ({@code "..."}) with every character TOML requires escaped. */
    public static String basicString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\b' -> sb.append("\\b");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\f' -> sb.append("\\f");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < 0x20 || c == 0x7f) sb.append(String.format("\\u%04X", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    // ─── Text editing helpers ───────────────────────────────────────────────

    private record Edit(int start, int end, String replacement) {
    }

    private static String apply(String text, List<Edit> edits) {
        List<Edit> sorted = new ArrayList<>(edits);
        sorted.sort(Comparator.comparingInt(Edit::start).reversed());
        StringBuilder sb = new StringBuilder(text);
        int limit = text.length();
        for (Edit e : sorted) {
            int end = Math.min(e.end, limit);
            if (e.start >= end && e.replacement.isEmpty()) continue;
            sb.replace(e.start, end, e.replacement);
            limit = e.start;
        }
        return sb.toString();
    }

    /** A removed header table also takes the single blank line we put before it on append. */
    private static int removalStart(String text, Span s) {
        if (!s.header || s.start == 0) return s.start;
        int prevEnd = s.start; // start of our line == just after the previous line's '\n'
        int prevStart = text.lastIndexOf('\n', prevEnd - 2) + 1;
        String prevLine = text.substring(prevStart, prevEnd);
        return prevLine.isBlank() ? prevStart : s.start;
    }

    private static void appendTable(StringBuilder sb, String table, String nl) {
        if (sb.length() > 0) {
            if (sb.charAt(sb.length() - 1) != '\n') sb.append(nl);
            sb.append(nl);
        }
        sb.append(table.replace("\n", nl));
    }

    private static String newline(String text) {
        return text.contains("\r\n") ? "\r\n" : "\n";
    }

    private static String read(Path file) {
        if (!Files.exists(file)) return "";
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, String content) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ─── Ownership analysis ─────────────────────────────────────────────────

    /** A byte range [start, end) of whole lines belonging to server {@code name}. */
    private record Span(String name, int start, int end, boolean header, int depth) {
    }

    private static Set<String> names(String rootKey, String text) {
        Set<String> names = new LinkedHashSet<>();
        for (Span s : spans(rootKey, text)) names.add(s.name);
        return names;
    }

    private static List<Span> spans(String rootKey, String text) {
        List<Stmt> stmts = new Lexer(text).statements();
        List<Span> spans = new ArrayList<>();
        List<String> table = List.of();
        boolean inArrayTable = false;
        for (int i = 0; i < stmts.size(); i++) {
            Stmt st = stmts.get(i);
            if (st.kind == Kind.HEADER) {
                table = st.key == null ? List.of("\u0000unparseable") : st.key;
                inArrayTable = st.arrayTable;
                if (!inArrayTable && st.key != null && st.key.size() >= 2 && st.key.get(0).equals(rootKey)) {
                    int last = i;
                    for (int j = i + 1; j < stmts.size() && stmts.get(j).kind != Kind.HEADER; j++) {
                        if (stmts.get(j).kind != Kind.TRIVIA) last = j;
                    }
                    spans.add(new Span(st.key.get(1), st.start, stmts.get(last).end, true, st.key.size()));
                }
            } else if (st.kind == Kind.KEYVAL && st.key != null && !inArrayTable && table.size() < 2) {
                List<String> full = new ArrayList<>(table);
                full.addAll(st.key);
                if (full.size() >= 2 && full.get(0).equals(rootKey)) {
                    spans.add(new Span(full.get(1), st.start, st.end, false, full.size()));
                }
            }
        }
        return spans;
    }

    // ─── Lexer ──────────────────────────────────────────────────────────────

    private enum Kind { HEADER, KEYVAL, TRIVIA }

    /** One TOML statement covering whole lines: [start, end) includes the terminating newline. */
    private record Stmt(Kind kind, List<String> key, boolean arrayTable, int start, int end) {
    }

    private static final class ParseException extends RuntimeException {
        ParseException() {
            super(null, null, false, false);
        }
    }

    private static final class Lexer {
        private final String s;
        private final int n;
        private int pos;

        Lexer(String s) {
            this.s = s;
            this.n = s.length();
        }

        List<Stmt> statements() {
            List<Stmt> out = new ArrayList<>();
            while (pos < n) {
                int start = pos;
                skipSpaces();
                if (pos >= n) {
                    out.add(new Stmt(Kind.TRIVIA, null, false, start, n));
                    break;
                }
                char c = s.charAt(pos);
                if (c == '\n' || c == '\r' || c == '#') {
                    toLineEnd();
                    out.add(new Stmt(Kind.TRIVIA, null, false, start, pos));
                    continue;
                }
                try {
                    if (c == '[') {
                        boolean arr = peek(1) == '[';
                        pos += arr ? 2 : 1;
                        List<String> key = parseKey();
                        expect(']');
                        if (arr) expect(']');
                        toLineEnd();
                        out.add(new Stmt(Kind.HEADER, key, arr, start, pos));
                    } else {
                        List<String> key = parseKey();
                        expect('=');
                        skipValue();
                        toLineEnd();
                        out.add(new Stmt(Kind.KEYVAL, key, false, start, pos));
                    }
                } catch (ParseException | StringIndexOutOfBoundsException e) {
                    // Not something we understand: treat the rest of the line as an opaque statement.
                    pos = Math.max(pos, start);
                    pos = Math.min(pos, n);
                    int lineEnd = s.indexOf('\n', start);
                    pos = Math.max(pos, lineEnd < 0 ? n : lineEnd);
                    toLineEnd();
                    out.add(new Stmt(c == '[' ? Kind.HEADER : Kind.KEYVAL, null, false, start, pos));
                }
            }
            return out;
        }

        private char peek(int off) {
            return pos + off < n ? s.charAt(pos + off) : '\0';
        }

        private void skipSpaces() {
            while (pos < n && (s.charAt(pos) == ' ' || s.charAt(pos) == '\t')) pos++;
        }

        /** Advances past the end of the current line (after its '\n'), or to EOF. */
        private void toLineEnd() {
            int nl = s.indexOf('\n', pos);
            pos = nl < 0 ? n : nl + 1;
        }

        private void expect(char c) {
            skipSpaces();
            if (pos >= n || s.charAt(pos) != c) throw new ParseException();
            pos++;
        }

        private List<String> parseKey() {
            List<String> parts = new ArrayList<>();
            while (true) {
                skipSpaces();
                if (pos >= n) throw new ParseException();
                char c = s.charAt(pos);
                if (c == '"') {
                    parts.add(basic());
                } else if (c == '\'') {
                    int end = s.indexOf('\'', pos + 1);
                    if (end < 0) throw new ParseException();
                    parts.add(s.substring(pos + 1, end));
                    pos = end + 1;
                } else {
                    int b = pos;
                    while (pos < n && isBare(s.charAt(pos))) pos++;
                    if (pos == b) throw new ParseException();
                    parts.add(s.substring(b, pos));
                }
                skipSpaces();
                if (pos < n && s.charAt(pos) == '.') {
                    pos++;
                } else {
                    return parts;
                }
            }
        }

        private static boolean isBare(char c) {
            return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
        }

        /** Single-line basic string at pos; returns its decoded value. */
        private String basic() {
            StringBuilder sb = new StringBuilder();
            pos++;
            while (true) {
                if (pos >= n) throw new ParseException();
                char c = s.charAt(pos);
                if (c == '\n') throw new ParseException();
                if (c == '"') {
                    pos++;
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(pos + 1);
                    switch (e) {
                        case 'b' -> sb.append('\b');
                        case 't' -> sb.append('\t');
                        case 'n' -> sb.append('\n');
                        case 'f' -> sb.append('\f');
                        case 'r' -> sb.append('\r');
                        case 'e' -> sb.append('\u001b');
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case 'u', 'U', 'x' -> {
                            int len = e == 'u' ? 4 : e == 'U' ? 8 : 2;
                            sb.appendCodePoint(Integer.parseInt(s.substring(pos + 2, pos + 2 + len), 16));
                            pos += len;
                        }
                        default -> throw new ParseException();
                    }
                    pos += 2;
                    continue;
                }
                sb.append(c);
                pos++;
            }
        }

        private void skipWsCommentsNewlines() {
            while (pos < n) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    pos++;
                } else if (c == '#') {
                    int nl = s.indexOf('\n', pos);
                    pos = nl < 0 ? n : nl;
                } else {
                    return;
                }
            }
        }

        private void skipValue() {
            skipSpaces();
            if (pos >= n) throw new ParseException();
            if (s.startsWith("\"\"\"", pos)) {
                pos += 3;
                while (true) {
                    if (pos >= n) throw new ParseException();
                    if (s.charAt(pos) == '\\') {
                        pos += 2;
                    } else if (s.startsWith("\"\"\"", pos)) {
                        pos += 3;
                        for (int k = 0; k < 2 && pos < n && s.charAt(pos) == '"'; k++) pos++;
                        return;
                    } else {
                        pos++;
                    }
                }
            }
            if (s.startsWith("'''", pos)) {
                int end = s.indexOf("'''", pos + 3);
                if (end < 0) throw new ParseException();
                pos = end + 3;
                for (int k = 0; k < 2 && pos < n && s.charAt(pos) == '\''; k++) pos++;
                return;
            }
            char c = s.charAt(pos);
            if (c == '"') {
                basic();
            } else if (c == '\'') {
                int end = s.indexOf('\'', pos + 1);
                int nl = s.indexOf('\n', pos + 1);
                if (end < 0 || (nl >= 0 && nl < end)) throw new ParseException();
                pos = end + 1;
            } else if (c == '[') {
                pos++;
                while (true) {
                    skipWsCommentsNewlines();
                    if (pos >= n) throw new ParseException();
                    if (s.charAt(pos) == ']') {
                        pos++;
                        return;
                    }
                    skipValue();
                    skipWsCommentsNewlines();
                    if (pos < n && s.charAt(pos) == ',') pos++;
                }
            } else if (c == '{') {
                pos++;
                while (true) {
                    skipWsCommentsNewlines();
                    if (pos >= n) throw new ParseException();
                    if (s.charAt(pos) == '}') {
                        pos++;
                        return;
                    }
                    parseKey();
                    expect('=');
                    skipValue();
                    skipWsCommentsNewlines();
                    if (pos < n && s.charAt(pos) == ',') pos++;
                }
            } else {
                int b = pos;
                while (pos < n && " \t\r\n,]}#".indexOf(s.charAt(pos)) < 0) pos++;
                if (pos == b) throw new ParseException();
            }
        }
    }
}
