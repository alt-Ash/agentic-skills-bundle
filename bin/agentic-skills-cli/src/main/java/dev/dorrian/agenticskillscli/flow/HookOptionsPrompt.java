package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.registry.HookInstallOptions;
import dev.dorrian.agenticskillscli.registry.HookToolSupport;
import dev.dorrian.agenticskillscli.ui.Prompter;

import java.util.Collection;

/**
 * The one place the install flows ask about opt-in hooks, so Quick and Full install offer the same
 * choices with the same wording. Every answer defaults to no: each of these can change how the AI
 * tool behaves. Asks nothing unless a selected tool supports hooks.
 */
final class HookOptionsPrompt {

    private HookOptionsPrompt() {
    }

    static HookInstallOptions ask(Prompter prompter, Collection<String> selectedTools) {
        if (selectedTools.stream().noneMatch(HookToolSupport::supports)) {
            return HookInstallOptions.NONE;
        }
        boolean guard = prompter.confirm(
            "Also enable the guardrail hook? It BLOCKS rm -rf on /, ~ or *, force-push to main/master,"
                + " and reading .env/private-key files", false);
        boolean verify = prompter.confirm(
            "Also enable the verification gate? When the AI tries to stop, it runs the checks in your project's"
                + " .agentic-skills/verify.json and keeps it working while they fail", false);
        boolean context = prompter.confirm(
            "Also inject project context at session start and after compaction?", false);
        return new HookInstallOptions(guard, verify, context);
    }
}
