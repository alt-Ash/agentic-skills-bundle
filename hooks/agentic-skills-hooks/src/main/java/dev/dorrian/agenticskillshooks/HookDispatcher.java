package dev.dorrian.agenticskillshooks;

import dev.dorrian.agenticskillshooks.hooks.AntigravityHook;
import dev.dorrian.agenticskillshooks.hooks.ContextHook;
import dev.dorrian.agenticskillshooks.hooks.GuardHook;
import dev.dorrian.agenticskillshooks.hooks.PostToolUseFailureHook;
import dev.dorrian.agenticskillshooks.hooks.PostToolUseHook;
import dev.dorrian.agenticskillshooks.hooks.SessionHook;
import dev.dorrian.agenticskillshooks.hooks.StopHook;
import dev.dorrian.agenticskillshooks.hooks.SubagentHook;
import dev.dorrian.agenticskillshooks.hooks.UserPromptSubmitHook;
import dev.dorrian.agenticskillshooks.hooks.VerifyHook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Entry point for the single fat jar backing all 5 hooks: `java -jar agentic-skills-hooks.jar
 * <hookType>` reads all of stdin, dispatches on args[0], and always exits 0 — hooks must never
 * block/fail the host CLI, matching every hooks/*.ts file's `main().catch(() => { exitCode = 0 })`.
 * The exceptions are the opt-in {@code guard} and {@code verify} hooks: a rule match / failed gate
 * exits 2 (Claude Code's "block" code) with the reason on stderr. Any error inside them still fails
 * open (exit 0). {@code context} prints text to stdout for Claude Code to add to the session. {@code agy
 * <event>} is the Antigravity CLI adapter, which always exits 0 and answers in Antigravity's JSON format.
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
                case "subagent" -> SubagentHook.run(input);
                case "verify" -> {
                    var reason = VerifyHook.evaluate(input);
                    if (reason.isPresent()) {
                        exitCode = 2;
                        System.err.println(reason.get());
                    }
                }
                case "agy" -> System.out.println(AntigravityHook.run(args.length > 1 ? args[1] : "",
                    java.util.Arrays.asList(args).contains("--verify"), raw));
                case "context" -> ContextHook.contextFor(input).ifPresent(System.out::println);
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
