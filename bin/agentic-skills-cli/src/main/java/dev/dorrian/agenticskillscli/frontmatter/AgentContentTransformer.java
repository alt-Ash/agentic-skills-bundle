package dev.dorrian.agenticskillscli.frontmatter;

import dev.dorrian.agenticskillscli.config.TomlMcpConfigStore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Java port of {@code bin/install.js}'s {@code transformAgentContent()} —
 * rewrites an agent markdown file (written in OpenCode frontmatter format)
 * into the correct format for the target AI tool. Ported field-for-field
 * from the source read directly on 2026-09-30.
 *
 * <p>OpenCode source frontmatter keys used: {@code mode}, {@code
 * description}, {@code temperature}, {@code color}, {@code model}, {@code
 * permission}, {@code hidden}, {@code steps}, {@code top_p}.
 *
 * <p>Target mappings:
 * <ul>
 *   <li>{@code opencode} — no change (source format).
 *   <li>{@code claude} — name/description/color/model passed through;
 *       {@code permission} is mapped to {@code disallowedTools} on a
 *       best-effort basis. Claude Code's tools/disallowedTools are coarse
 *       allow/deny-by-tool-name, so OpenCode's per-bash-command-pattern
 *       {@code ask} gating has no equivalent and is intentionally not
 *       simulated.
 *   <li>{@code vscode} — rewritten as VS Code {@code .agent.md} format:
 *       description, user-invocable, tools (derived from permission), model.
 *   <li>{@code gemini} — Gemini CLI subagent Markdown ({@code ~/.gemini/agents/*.md}):
 *       required {@code name} (lowercase slug) + {@code description}, {@code kind: local},
 *       {@code temperature} (0–2), and — only for agents whose OpenCode permission denies
 *       both edit and write — a {@code tools} allowlist of Gemini's read-only built-ins (plus
 *       {@code run_shell_command} unless bash is fully denied, the web tools unless webfetch
 *       is denied, and {@code mcp_<server>_<tool>} for each allowed MCP grant). Omitting
 *       {@code tools} inherits every tool. Body = system prompt. Tool names verified against
 *       context7 {@code /google-gemini/gemini-cli} (docs/reference/tools.md,
 *       docs/core/subagents.md) on 2026-10-01. OpenCode {@code model} ids are not Gemini
 *       models, so {@code model} is not carried over.
 *   <li>{@code codex} — a standalone Codex custom-agent TOML file ({@code ~/.codex/agents/*.toml}):
 *       {@code name}, {@code description}, {@code developer_instructions} (the body), and
 *       {@code sandbox_mode = "read-only"} when edit+write are denied (allowed values
 *       {@code read-only | workspace-write | danger-full-access} — context7
 *       {@code /llmstxt/learn_chatgpt_llms-full_txt}, config reference).
 * </ul>
 *
 * <p>Cursor/Windsurf/Zed don't support agents — the original guards this
 * case by returning the content unchanged since {@code supportsAgents} is
 * false for them and this function should never actually be reached for
 * those tool keys; preserved here for parity.
 */
public final class AgentContentTransformer {

    private static final Map<String, String> VSCODE_TOOL_MAP = Map.of(
        "read", "read",
        "edit", "edit",
        "bash", "terminal",
        "webfetch", "web/fetch",
        "glob", "search/codebase",
        "grep", "search/codebase"
    );

    /** Gemini CLI built-ins of kind Read/Search (docs/reference/tools.md) — never write_file/replace. */
    private static final List<String> GEMINI_READ_ONLY_TOOLS =
        List.of("read_file", "read_many_files", "list_directory", "glob", "grep_search");

    private AgentContentTransformer() {
    }

    public static String transform(String content, String toolKey, String agentName) {
        if ("opencode".equals(toolKey)) {
            return content; // source format — no change
        }

        FrontmatterParser.FrontmatterAndBody split = FrontmatterParser.splitFrontmatterAndBody(content);
        if (split == null) {
            // Gemini/Codex need their own required keys even for a bare body; others pass through.
            if ("gemini".equals(toolKey)) return transformForGemini(Map.of(), content, agentName);
            if ("codex".equals(toolKey)) return transformForCodex(Map.of(), content, agentName);
            return content; // no frontmatter — pass through unchanged
        }

        Map<String, Object> fm = FrontmatterParser.parse(content);
        String body = split.body();

        if ("gemini".equals(toolKey)) {
            return transformForGemini(fm, body, agentName);
        }
        if ("codex".equals(toolKey)) {
            return transformForCodex(fm, body, agentName);
        }

        if ("claude".equals(toolKey)) {
            return transformForClaude(fm, body, agentName);
        }
        if ("vscode".equals(toolKey)) {
            return transformForVsCode(fm, body);
        }

        // Cursor / Windsurf / Zed don't support agents — should never reach
        // here since supportsAgents is false for them, but guard just in case.
        return content;
    }

    private static String transformForClaude(Map<String, Object> fm, String body, String agentName) {
        List<String> lines = new ArrayList<>();
        lines.add("---");
        if (agentName != null) {
            lines.add("name: " + agentName);
        }
        Object description = fm.get("description");
        if (description != null) {
            lines.add("description: " + description);
        }

        Object permObj = fm.get("permission");
        Map<?, ?> perm = permObj instanceof Map<?, ?> m ? m : Map.of();

        List<String> disallowedTools = new ArrayList<>();
        if ("deny".equals(perm.get("edit"))) disallowedTools.add("Edit");
        if ("deny".equals(perm.get("write"))) disallowedTools.add("Write");
        if ("deny".equals(perm.get("webfetch"))) disallowedTools.add("WebFetch");
        if ("deny".equals(perm.get("task"))) disallowedTools.add("Task");

        Object bashPerm = perm.get("bash");
        boolean bashFullyDenied = "deny".equals(bashPerm)
            || (bashPerm instanceof Map<?, ?> bashMap && "deny".equals(bashMap.get("*")));
        if (bashFullyDenied) disallowedTools.add("Bash");

        if (!disallowedTools.isEmpty()) {
            lines.add("disallowedTools: " + String.join(", ", disallowedTools));
        }

        Object model = fm.get("model");
        if (model != null) {
            lines.add("model: " + model);
        }
        Object color = fm.get("color");
        if (color != null) {
            // Quoted: an unquoted leading '#' (hex color) is parsed as a YAML comment.
            lines.add("color: \"" + color + "\"");
        }
        lines.add("---");
        lines.add("");
        lines.add(stripLeading(body));
        return String.join("\n", lines);
    }

    private static String transformForVsCode(Map<String, Object> fm, String body) {
        List<String> lines = new ArrayList<>();
        lines.add("---");
        Object description = fm.get("description");
        if (description != null) {
            lines.add("description: " + description);
        }

        Object mode = fm.get("mode");
        boolean isPrimary = "primary".equals(mode) || "all".equals(mode) || mode == null;
        lines.add("user-invocable: " + isPrimary);

        Object permObj = fm.get("permission");
        Map<?, ?> perm = permObj instanceof Map<?, ?> m ? m : Map.of();

        Set<String> uniqueTools = new LinkedHashSet<>();
        for (Map.Entry<?, ?> entry : perm.entrySet()) {
            if (!"allow".equals(entry.getValue())) continue;
            String mapped = VSCODE_TOOL_MAP.get(String.valueOf(entry.getKey()));
            if (mapped != null) uniqueTools.add(mapped);
        }
        if (!uniqueTools.isEmpty()) {
            String joined = uniqueTools.stream().map(t -> "'" + t + "'").reduce((a, b) -> a + ", " + b).orElse("");
            lines.add("tools: [" + joined + "]");
        }

        Object model = fm.get("model");
        if (model != null) {
            lines.add("model: " + model);
        }
        lines.add("---");
        lines.add("");
        lines.add(stripLeading(body));
        return String.join("\n", lines);
    }

    static String transformForGemini(Map<String, Object> fm, String body, String agentName) {
        String name = geminiSlug(agentName);
        Map<?, ?> perm = fm.get("permission") instanceof Map<?, ?> m ? m : Map.of();

        List<String> lines = new ArrayList<>();
        lines.add("---");
        lines.add("name: " + name);
        lines.add("description: " + yamlQuote(descriptionOr(fm, name)));
        lines.add("kind: local");
        if (fm.get("temperature") instanceof Number t && t.doubleValue() >= 0 && t.doubleValue() <= 2) {
            lines.add("temperature: " + t);
        }
        if (isReadOnly(perm)) {
            lines.add("tools:");
            for (String tool : geminiReadOnlyTools(perm)) {
                lines.add("  - " + yamlQuote(tool));
            }
        }
        lines.add("---");
        lines.add("");
        lines.add(stripLeading(body));
        return String.join("\n", lines);
    }

    private static List<String> geminiReadOnlyTools(Map<?, ?> perm) {
        List<String> tools = new ArrayList<>(GEMINI_READ_ONLY_TOOLS);
        if (!"deny".equals(perm.get("webfetch"))) {
            tools.add("web_fetch");
            tools.add("google_web_search");
        }
        if (!isBashFullyDenied(perm)) {
            // Shell stays available (subject to Gemini's own confirmation policy) so read-only
            // agents can still run `git diff` & co.; OpenCode's per-command patterns have no
            // subagent-level equivalent in Gemini.
            tools.add("run_shell_command");
        }
        if (perm.get("mcp") instanceof Map<?, ?> mcp) {
            for (Map.Entry<?, ?> e : mcp.entrySet()) {
                if ("deny".equals(e.getValue())) continue;
                // OpenCode "server/tool" (or "server/*") -> Gemini "mcp_server_tool" (or "mcp_server_*").
                tools.add("mcp_" + String.valueOf(e.getKey()).replace('/', '_'));
            }
        }
        return tools;
    }

    static String transformForCodex(Map<String, Object> fm, String body, String agentName) {
        String name = agentName == null || agentName.isBlank() ? "agent" : agentName;
        Map<?, ?> perm = fm.get("permission") instanceof Map<?, ?> m ? m : Map.of();

        StringBuilder sb = new StringBuilder();
        sb.append("name = ").append(TomlMcpConfigStore.basicString(name)).append('\n');
        sb.append("description = ").append(TomlMcpConfigStore.basicString(descriptionOr(fm, name))).append('\n');
        if (isReadOnly(perm)) {
            sb.append("sandbox_mode = \"read-only\"\n");
        }
        sb.append("developer_instructions = ").append(tomlMultiline(normalizeBody(body))).append('\n');
        return sb.toString();
    }

    /** CRLF normalised, leading blank space and trailing whitespace trimmed, ending in exactly one newline. */
    private static String normalizeBody(String body) {
        String b = stripLeading(body.replace("\r\n", "\n"));
        int end = b.length();
        while (end > 0 && Character.isWhitespace(b.charAt(end - 1))) end--;
        return b.substring(0, end) + "\n";
    }

    /**
     * TOML multi-line string: a literal {@code '''...'''} (no escaping at all) when the text
     * allows it, else a multi-line basic string with backslashes, double quotes and control
     * characters escaped. TOML trims the newline right after the opening delimiter.
     */
    static String tomlMultiline(String text) {
        boolean literalOk = !text.contains("'''");
        for (int i = 0; i < text.length() && literalOk; i++) {
            char c = text.charAt(i);
            if ((c < 0x20 && c != '\n' && c != '\t') || c == 0x7f) literalOk = false;
        }
        if (literalOk) {
            return "'''\n" + text + "'''";
        }
        StringBuilder sb = new StringBuilder("\"\"\"\n");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append('\n');
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f) sb.append(String.format("\\u%04X", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append("\"\"\"").toString();
    }

    private static boolean isReadOnly(Map<?, ?> perm) {
        return "deny".equals(perm.get("edit")) && "deny".equals(perm.get("write"));
    }

    private static boolean isBashFullyDenied(Map<?, ?> perm) {
        Object bashPerm = perm.get("bash");
        return "deny".equals(bashPerm)
            || (bashPerm instanceof Map<?, ?> bashMap && "deny".equals(bashMap.get("*")));
    }

    private static String descriptionOr(Map<String, Object> fm, String fallbackName) {
        Object d = fm.get("description");
        return d == null || String.valueOf(d).isBlank() ? "The " + fallbackName + " agent" : String.valueOf(d).strip();
    }

    /** Gemini requires a lowercase slug (letters, digits, '-', '_'). */
    static String geminiSlug(String agentName) {
        String slug = (agentName == null ? "" : agentName).toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_-]+", "-")
            .replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "agent" : slug;
    }

    /** YAML double-quoted scalar — safe for colons, '#', leading quotes, etc. */
    private static String yamlQuote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\x%02X", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String stripLeading(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }
}
