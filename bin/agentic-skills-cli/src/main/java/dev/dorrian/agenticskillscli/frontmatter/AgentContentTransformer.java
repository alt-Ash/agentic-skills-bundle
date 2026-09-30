package dev.dorrian.agenticskillscli.frontmatter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

    private AgentContentTransformer() {
    }

    public static String transform(String content, String toolKey, String agentName) {
        if ("opencode".equals(toolKey)) {
            return content; // source format — no change
        }

        FrontmatterParser.FrontmatterAndBody split = FrontmatterParser.splitFrontmatterAndBody(content);
        if (split == null) {
            return content; // no frontmatter — pass through unchanged
        }

        Map<String, Object> fm = FrontmatterParser.parse(content);
        String body = split.body();

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

    private static String stripLeading(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }
}
