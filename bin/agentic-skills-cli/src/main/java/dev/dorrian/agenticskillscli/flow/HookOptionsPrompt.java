package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.HomeDir;
import dev.dorrian.agenticskillscli.install.HooksInstaller;
import dev.dorrian.agenticskillscli.registry.HookInstallOptions;
import dev.dorrian.agenticskillscli.ui.Prompter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

/**
 * The one place the install flows ask about opt-in hooks, so Quick and Full install offer the same choices
 * with the same wording. Each of these can change how the AI tool behaves, so a fresh install defaults every
 * answer to no. A re-run defaults each answer to what is already installed, because registering converges to
 * the answers: pressing Enter must not silently remove a hook the user enabled earlier.
 *
 * <p>Only questions that do something for the selected tools are asked: the guard works for Claude Code and
 * OpenCode; the verification gate and the context hook for Claude Code and Antigravity.
 */
final class HookOptionsPrompt {

    /** Which questions apply to a tool selection. */
    record Applicable(boolean guard, boolean verifyAndContext) {
        boolean any() {
            return guard || verifyAndContext;
        }
    }

    private HookOptionsPrompt() {
    }

    static Applicable applicable(Collection<String> selectedTools) {
        boolean claude = selectedTools.contains("claude");
        // The guard needs a tool whose hooks can block by exit code or throw (Claude Code, OpenCode). Antigravity
        // gets the verify gate and context but no guard.
        return new Applicable(claude || selectedTools.contains("opencode"), claude || selectedTools.contains("antigravity"));
    }

    static HookInstallOptions ask(Prompter prompter, Collection<String> selectedTools) {
        Applicable applicable = applicable(selectedTools);
        if (!applicable.any()) {
            return HookInstallOptions.NONE;
        }
        HookInstallOptions current = applicable.verifyAndContext()
            ? HooksInstaller.currentOptionsForTool("claude") : HookInstallOptions.NONE;

        boolean guard = prompter.confirm(
            "Also enable the guardrail hook? It BLOCKS rm -rf on /, ~ or *, force-push to main/master,"
                + " and reading .env/private-key files" + enabledNote(current.guard()), current.guard());
        boolean verify = false;
        boolean context = false;
        if (applicable.verifyAndContext()) {
            verify = prompter.confirm(
                "Also enable the verification gate? When the AI tries to stop, it runs the checks in your project's"
                    + " .agentic-skills/verify.json and keeps it working while they fail. Each project's file only"
                    + " runs after you approve it with `agentic-skills verify trust`" + enabledNote(current.verify()),
                current.verify());
            context = prompter.confirm(
                "Also inject project context at session start and after compaction?" + enabledNote(current.context()),
                current.context());
        }
        return new HookInstallOptions(guard, verify, context);
    }

    /**
     * Antigravity CLI is not a tool the installer installs skills for, so its hooks are a separate question, asked
     * only if Antigravity looks installed (its config directories exist) or our hooks are already registered there.
     * Defaults to yes in both cases, like the other tools' analytics hooks.
     */
    static boolean askAntigravity(Prompter prompter) {
        boolean registered = HooksInstaller.isInstalledForTool("antigravity");
        if (!registered && !antigravityPresent()) {
            return false;
        }
        return prompter.confirm("Also register usage hooks with Antigravity CLI (~/.gemini/config/hooks.json)?"
            + enabledNote(registered), true);
    }

    static boolean antigravityPresent() {
        Path gemini = HomeDir.resolve().resolve(".gemini");
        return Files.isDirectory(gemini.resolve("antigravity-cli")) || Files.isDirectory(gemini.resolve("antigravity"))
            || Files.isDirectory(gemini.resolve("config"));
    }

    /** Registers the hooks with Antigravity and says what happened; a failure never aborts the install. */
    static void installAntigravity(HookInstallOptions options) {
        try {
            HooksInstaller.installForTool("antigravity", options);
            System.out.println("  Hooks registered with Antigravity CLI.");
        } catch (RuntimeException e) {
            System.out.println("  Hooks not registered for Antigravity CLI: " + e.getMessage());
        }
    }

    private static String enabledNote(boolean enabled) {
        return enabled ? " (currently enabled)" : "";
    }
}
