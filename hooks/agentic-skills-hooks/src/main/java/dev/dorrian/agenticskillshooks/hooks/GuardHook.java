package dev.dorrian.agenticskillshooks.hooks;

import dev.dorrian.agenticskillshooks.HookInput;
import dev.dorrian.agenticskillshooks.IdentityResolver;
import dev.dorrian.agenticskillshooks.ProviderDetector;
import dev.dorrian.usagestore.SecretRedactor;
import dev.dorrian.usagestore.UsageEvent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Opt-in {@code PreToolUse} guardrail. Unlike the analytics hooks it can block: a denial is
 * reported to Claude Code as exit 2 with the reason on stderr (see {@code HookDispatcher}).
 * Rules are deliberately narrow so a false positive is rare; anything it cannot parse is allowed.
 * Tunable through {@code guard.json} (see {@link GuardConfig}); all matching is time-bounded.
 */
public final class GuardHook {

    // Stable ids, used only by guard.json "disable". The reason texts below are stored data: do not change them.
    private static final String ID_RECURSIVE_DELETE = "recursive-delete";
    private static final String ID_FORCE_PUSH = "force-push";
    private static final String ID_ENV_READ = "env-read";
    private static final String ID_SECRETS_FILE = "secrets-file";

    /** Example/template variants (.env.example, .env.local.sample, ...) are not secrets. */
    private static final Pattern SECRET_FILE = Pattern.compile(
        "(^|[/\\\\])(\\.env(\\.(?!(?:[^/\\\\]*\\.)?(?:example|sample|template|dist)$)[^/\\\\]+)?"
            + "|id_rsa|id_ed25519|id_ecdsa|id_dsa|credentials|[^/\\\\]+\\.(pem|key|p12|pfx))$",
        Pattern.CASE_INSENSITIVE);

    private static final String END = "(?=\\s|$|[;&|)])";
    private static final String TOKENS = "(?:[^\\s;&|]+\\s+)*?";
    /** A root, home or bare-wildcard target, optionally quoted, optionally with trailing / or /*. */
    private static final String DANGEROUS_TARGET =
        "[\"']?(?:(?:/|~|\\$HOME|\\$\\{HOME\\})/?\\*?|\\*)[\"']?";
    /** A main/master ref token: not a path suffix such as feature/main. */
    private static final String MAIN_REF = "(?:refs/heads/)?(?:main|master)" + END;

    private static final List<Rule> BASH_RULES = List.of(
        new Rule(ID_RECURSIVE_DELETE,
            Pattern.compile("(?<![\\w-])rm\\b\\s+" + TOKENS + "(?:-[a-zA-Z]*[rR][a-zA-Z]*|--recursive)\\s+"
                + TOKENS + DANGEROUS_TARGET + END),
            "recursive delete of a root, home or wildcard path"),
        new Rule(ID_FORCE_PUSH,
            Pattern.compile("\\bgit\\s+(?:-[cC]\\s+\\S+\\s+)*push\\b(?:"
                + "(?=[^|;&]*\\s(?:--force\\b(?!-)|-f\\b))[^|;&]*(?<![^\\s:+])" + MAIN_REF
                + "|[^|;&]*\\s\\+(?:\\S*:)?" + MAIN_REF + ")"),
            "force-push to main/master"),
        new Rule(ID_ENV_READ,
            Pattern.compile("\\b(?:cat|less|more|head|tail|grep|rg|cp|scp|curl|source|bat|tac|nl|sed|awk|base64|xxd|od|strings)\\b"
                + "[^|;&]*\\s[\"']?[^\\s\"'\\\\]*\\.env(?:\\.(?!(?:example|sample|template|dist)\\b)\\w+)?[\"']?" + END),
            "reading an .env file")
    );

    private static final List<String> PATH_TOOLS = List.of("Read", "Edit", "Write", "MultiEdit", "NotebookEdit");

    private GuardHook() {
    }

    /** Returns the denial, or empty to allow. Config comes from guard.json; any internal error allows. */
    public static Optional<Denial> evaluate(HookInput input) {
        try {
            return evaluate(input, GuardConfig.load(input.cwd()));
        } catch (Throwable e) {
            return Optional.empty();
        }
    }

    static Optional<Denial> evaluate(HookInput input, GuardConfig config) {
        try {
            String tool = input.toolName();
            if (tool == null) return Optional.empty();

            boolean bash = "Bash".equals(tool);
            String subject;
            if (bash) {
                subject = input.toolInputText("command");
            } else if (PATH_TOOLS.contains(tool)) {
                subject = input.toolInputText("file_path");
                if (subject == null) subject = input.toolInputText("notebook_path");
            } else {
                subject = null;
            }
            if (subject == null || config.isAllowed(subject)) return Optional.empty();

            String shown = bash ? SecretRedactor.redact(subject) : subject;
            if (bash) {
                for (Rule rule : BASH_RULES) {
                    if (!config.isDisabled(rule.id()) && BoundedMatcher.find(rule.pattern(), subject)) {
                        return Optional.of(new Denial(rule.reason(), tool, shown));
                    }
                }
            } else if (!config.isDisabled(ID_SECRETS_FILE) && BoundedMatcher.find(SECRET_FILE, subject)) {
                return Optional.of(new Denial("secrets file access", tool, shown));
            }
            for (GuardConfig.DenyRule rule : config.denyRules()) {
                if (rule.tools().contains(tool) && BoundedMatcher.find(rule.pattern(), subject)) {
                    return Optional.of(new Denial(rule.reason(), tool, shown));
                }
            }
            return Optional.empty();
        } catch (Throwable e) {
            return Optional.empty();
        }
    }

    /** A rule match: what was blocked ({@code subject} is the redacted command or the file path). */
    public record Denial(String rule, String tool, String subject) {
        /** The stderr text Claude Code shows the model. */
        public String message() {
            return "Blocked by agentic-skills guard: " + rule + " (" + tool + ")";
        }
    }

    /** The usage event recording a block, so the dashboard can show what the guard stops. */
    public static UsageEvent denialEvent(HookInput input, Denial denial) {
        String provider = ProviderDetector.detect(input);
        IdentityResolver.ResolvedIdentity identity = IdentityResolver.resolveIdentity(input, provider);
        UsageEvent event = new UsageEvent();
        event.ts = Instant.now().toString();
        event.event = "guard_block";
        event.sessionId = input.sessionId();
        event.provider = provider;
        event.user = identity.user;
        event.project = identity.project;
        event.client = identity.client;
        event.toolName = denial.tool();
        event.command = denial.subject();
        event.guardRule = denial.rule();
        return event;
    }

    private record Rule(String id, Pattern pattern, String reason) {
    }
}
