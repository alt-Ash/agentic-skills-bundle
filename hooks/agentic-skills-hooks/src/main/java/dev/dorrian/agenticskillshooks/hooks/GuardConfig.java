package dev.dorrian.agenticskillshooks.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Optional guard tuning, merged from {@code ~/.agentic-skills/guard.json} (user) and
 * {@code <cwd>/.agentic-skills/guard.json} (project):
 * <pre>{"disable": ["env-read"], "allow": ["git push --force-with-lease origin \\S+"],
 *  "deny": [{"pattern": "terraform\\s+destroy", "reason": "no destroys", "tools": ["Bash"]}]}</pre>
 * Merge: only the USER file can {@code disable} rules or {@code allow} commands (an {@code allow} regex must match
 * the whole command); the project file can only add {@code deny} rules, because it is repository-controlled.
 * {@code deny} entries are unioned and a project entry with the same pattern replaces the user's. Every problem (missing/oversized/invalid file,
 * bad or catastrophic regex) is ignored, never thrown: the result is always usable.
 */
public final class GuardConfig {

    static final long MAX_FILE_BYTES = 64 * 1024;
    static final int MAX_PATTERN_LENGTH = 500;
    static final int MAX_ENTRIES = 100;
    static final String CUSTOM_PREFIX = "custom: ";

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Nested unbounded quantifier such as {@code (a+)+} or {@code (.*)*}: the classic ReDoS shape. */
    private static final Pattern NESTED_QUANTIFIER = Pattern.compile("\\([^()]*[+*][^()]*\\)\\s*[+*{]");

    /** A user deny rule; {@code reason} is already prefixed with {@code custom: }. */
    public record DenyRule(Pattern pattern, String reason, Set<String> tools) {
    }

    private static final GuardConfig DEFAULTS = new GuardConfig(Set.of(), List.of(), List.of());

    private final Set<String> disabled;
    private final List<Pattern> allow;
    private final List<DenyRule> deny;

    GuardConfig(Set<String> disabled, List<Pattern> allow, List<DenyRule> deny) {
        this.disabled = disabled;
        this.allow = allow;
        this.deny = deny;
    }

    public static GuardConfig defaults() {
        return DEFAULTS;
    }

    /** Loads from the real user home and the given project dir (null falls back to {@code user.dir}). */
    public static GuardConfig load(String cwd) {
        try {
            Path home = Path.of(System.getProperty("user.home"));
            Path project = Path.of(cwd == null || cwd.isBlank() ? System.getProperty("user.dir") : cwd);
            return load(home, project);
        } catch (Throwable e) {
            return DEFAULTS;
        }
    }

    public static GuardConfig load(Path userHome, Path projectDir) {
        try {
            GuardConfig user = userHome == null ? DEFAULTS : parseFile(userHome.resolve(".agentic-skills/guard.json"));
            GuardConfig project = projectDir == null ? DEFAULTS : parseFile(projectDir.resolve(".agentic-skills/guard.json"));
            return merge(user, project);
        } catch (Throwable e) {
            return DEFAULTS;
        }
    }

    static GuardConfig merge(GuardConfig user, GuardConfig project) {
        // The project file lives in the repository, so a cloned repo (or the model, which can write files) could
        // use it to switch the guard off. It may therefore only make the guard STRICTER: its `disable` and
        // `allow` are ignored, only the user-level file can loosen anything.
        Set<String> disabled = new LinkedHashSet<>(user.disabled);
        List<Pattern> allow = new ArrayList<>(user.allow);
        Map<String, DenyRule> deny = new LinkedHashMap<>();
        for (DenyRule r : user.deny) deny.put(r.pattern().pattern(), r);
        for (DenyRule r : project.deny) deny.put(r.pattern().pattern(), r);
        return new GuardConfig(disabled, allow, new ArrayList<>(deny.values()));
    }

    static GuardConfig parseFile(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES) return DEFAULTS;
            return parse(Files.readString(file));
        } catch (Throwable e) {
            return DEFAULTS;
        }
    }

    static GuardConfig parse(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            if (root == null || !root.isObject()) return DEFAULTS;
            Set<String> disabled = new LinkedHashSet<>();
            for (String s : strings(root.get("disable"))) disabled.add(s.trim());
            List<Pattern> allow = new ArrayList<>();
            for (String s : strings(root.get("allow"))) {
                Pattern p = safeCompile(s);
                if (p != null) allow.add(p);
            }
            List<DenyRule> deny = new ArrayList<>();
            JsonNode denyNode = root.get("deny");
            if (denyNode != null && denyNode.isArray()) {
                int n = 0;
                for (JsonNode d : denyNode) {
                    if (++n > MAX_ENTRIES) break;
                    if (!d.isObject() || !d.path("pattern").isTextual()) continue;
                    Pattern p = safeCompile(d.get("pattern").asText());
                    if (p == null) continue;
                    String reason = d.path("reason").isTextual() ? d.get("reason").asText().strip() : "";
                    if (reason.isEmpty()) reason = "rule " + d.get("pattern").asText();
                    if (reason.length() > 200) reason = reason.substring(0, 200);
                    Set<String> tools = new LinkedHashSet<>(strings(d.get("tools")));
                    if (tools.isEmpty()) tools.add("Bash");
                    deny.add(new DenyRule(p, CUSTOM_PREFIX + reason, tools));
                }
            }
            return new GuardConfig(disabled, allow, deny);
        } catch (Throwable e) {
            return DEFAULTS;
        }
    }

    /** Compiles a user regex, or returns null for an invalid, oversized or catastrophic-looking one. */
    static Pattern safeCompile(String regex) {
        if (regex == null || regex.isBlank() || regex.length() > MAX_PATTERN_LENGTH) return null;
        if (NESTED_QUANTIFIER.matcher(regex).find()) return null;
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException | StackOverflowError e) {
            return null;
        }
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) return out;
        for (JsonNode n : node) {
            if (n.isTextual() && out.size() < MAX_ENTRIES) out.add(n.asText());
        }
        return out;
    }

    public boolean isDisabled(String ruleId) {
        return disabled.contains(ruleId);
    }

    /**
     * True when a user {@code allow} regex matches the WHOLE subject (time-bounded). Whole-match, not
     * find: otherwise {@code ^git push --force-with-lease} would also exempt
     * {@code git push --force-with-lease && rm -rf /}.
     */
    public boolean isAllowed(String subject) {
        for (Pattern p : allow) {
            if (BoundedMatcher.matches(p, subject)) return true;
        }
        return false;
    }

    public List<DenyRule> denyRules() {
        return deny;
    }
}
