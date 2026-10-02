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
 */
public final class GuardHook {

    private static final Pattern SECRET_FILE = Pattern.compile(
        "(^|[/\\\\])(\\.env(\\.(?!example$|sample$|template$|dist$)[^/\\\\]+)?|id_rsa|id_ed25519|credentials|[^/\\\\]+\\.(pem|key|p12|pfx))$");

    private static final List<Rule> BASH_RULES = List.of(
        new Rule(Pattern.compile("\\brm\\s+(-[a-zA-Z]*[rR][a-zA-Z]*\\s+|--recursive\\s+)+(-[a-zA-Z]+\\s+)*(/|~|\\$HOME|\\*)(\\s|$|/\\*)"),
            "recursive delete of a root, home or wildcard path"),
        new Rule(Pattern.compile("\\bgit\\s+push\\b(?=[^|;&]*\\s(--force\\b(?!-with-lease)|-f\\b))[^|;&]*\\b(main|master)\\b"),
            "force-push to main/master"),
        new Rule(Pattern.compile("\\b(cat|less|more|head|tail|grep|rg|cp|scp|curl)\\b[^|;&]*\\s\\S*\\.env(\\.(?!example\\b|sample\\b|template\\b|dist\\b)\\w+)?(\\s|$)"),
            "reading an .env file")
    );

    private static final List<String> PATH_TOOLS = List.of("Read", "Edit", "Write", "MultiEdit", "NotebookEdit");

    private GuardHook() {
    }

    /** Returns the denial, or empty to allow. */
    public static Optional<Denial> evaluate(HookInput input) {
        String tool = input.toolName();
        if (tool == null) return Optional.empty();

        if ("Bash".equals(tool)) {
            String command = input.toolInputText("command");
            if (command == null) return Optional.empty();
            for (Rule rule : BASH_RULES) {
                if (rule.pattern().matcher(command).find()) {
                    return Optional.of(new Denial(rule.reason(), tool, SecretRedactor.redact(command)));
                }
            }
        } else if (PATH_TOOLS.contains(tool)) {
            String path = input.toolInputText("file_path");
            if (path == null) path = input.toolInputText("notebook_path");
            if (path != null && SECRET_FILE.matcher(path).find()) {
                return Optional.of(new Denial("secrets file access", tool, path));
            }
        }
        return Optional.empty();
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

    private record Rule(Pattern pattern, String reason) {
    }
}
