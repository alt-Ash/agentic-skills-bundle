package dev.dorrian.agenticskillshooks;

import dev.dorrian.agenticskillshooks.hooks.GuardHook;
import dev.dorrian.agenticskillshooks.hooks.PostToolUseFailureHook;
import dev.dorrian.agenticskillshooks.hooks.PostToolUseHook;
import dev.dorrian.agenticskillshooks.hooks.SessionHook;
import dev.dorrian.agenticskillshooks.hooks.StopHook;
import dev.dorrian.agenticskillshooks.hooks.UserPromptSubmitHook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Entry point for the single fat jar backing all 5 hooks: `java -jar agentic-skills-hooks.jar
 * <hookType>` reads all of stdin, dispatches on args[0], and always exits 0 — hooks must never
 * block/fail the host CLI, matching every hooks/*.ts file's `main().catch(() => { exitCode = 0 })`.
 * The one exception is the opt-in {@code guard} hook: a rule match exits 2 (Claude Code's
 * "block" code) with the reason on stderr. Any error inside it still fails open (exit 0).
 */
public final class HookDispatcher {

    private HookDispatcher() {
    }

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            String hookType = args.length > 0 ? args[0] : "";
            String raw = readStdin();
            HookInput input = HookInput.parse(raw);

            switch (hookType) {
                case "post-tool-use" -> PostToolUseHook.run(input);
                case "post-tool-use-failure" -> PostToolUseFailureHook.run(input);
                case "session" -> SessionHook.run(input);
                case "user-prompt-submit" -> UserPromptSubmitHook.run(input);
                case "stop" -> StopHook.run(input);
                case "guard" -> {
                    var denial = GuardHook.evaluate(input);
                    if (denial.isPresent()) {
                        exitCode = 2;
                        System.err.println(denial.get().message());
                        EventLog.recordEvent(GuardHook.denialEvent(input, denial.get()));
                    }
                }
                default -> { /* unknown hook type — no-op, still exit 0 */ }
            }
            AnalyticsServiceClient.awaitPending();
        } catch (Throwable ignored) {
            // hooks must never block/fail the host CLI
        } finally {
            EventLog.close();
            System.exit(exitCode);
        }
    }

    private static String readStdin() throws IOException {
        return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
    }
}
