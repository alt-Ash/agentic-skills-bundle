package dev.dorrian.agenticskillshooks.hooks;

import dev.dorrian.agenticskillshooks.EventLog;
import dev.dorrian.agenticskillshooks.HookInput;
import dev.dorrian.agenticskillshooks.IdentityResolver;
import dev.dorrian.agenticskillshooks.ProviderDetector;
import dev.dorrian.usagestore.SecretRedactor;
import dev.dorrian.usagestore.UsageEvent;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Opt-in {@code Stop} gate: runs the commands in {@code <project>/.agentic-skills/verify.json} and,
 * if one fails, blocks Claude from stopping (HookDispatcher: exit 2, reason on stderr). Timeouts and
 * start failures fail open but are recorded. A loop guard lets Claude stop after
 * {@code maxConsecutiveBlocks} consecutive blocks in a session (counted from stored events).
 */
public final class VerifyHook {

    static final int TAIL_LINES = 40;
    static final int TAIL_CHARS = 3000;

    private VerifyHook() {
    }

    /** Returns the reason to keep Claude working, or empty to let it stop. */
    public static Optional<String> evaluate(HookInput input) {
        try {
            return evaluate(input, EventLog::sessionEvents, CommandRunner::run, EventLog::recordEvent);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    interface Runner {
        CommandRunner.Result run(String command, File dir, int timeoutSeconds);
    }

    static Optional<String> evaluate(HookInput input, Function<String, List<UsageEvent>> history,
                                     Runner runner, Consumer<UsageEvent> sink) {
        String cwd = input.cwd() != null && !input.cwd().isBlank() ? input.cwd() : System.getProperty("user.dir");
        Path project = Path.of(cwd);
        Optional<VerifyConfig> maybe = VerifyConfig.load(project);
        if (maybe.isEmpty()) return Optional.empty();
        VerifyConfig config = maybe.get();

        String sessionId = input.sessionId();
        boolean active = Boolean.TRUE.equals(input.stopHookActive());
        // Only a continuation caused by a stop hook can extend a block chain.
        int priorBlocks = active && sessionId != null ? trailingBlocks(history.apply(sessionId)) : 0;

        if (active && (sessionId == null || priorBlocks >= config.maxConsecutiveBlocks())) {
            sink.accept(event(input, "verify_error", null, 0L, "gave up after " + priorBlocks
                + " consecutive blocked stops; allowing stop", "max_blocks"));
            return Optional.empty();
        }

        String last = null;
        long total = 0;
        for (String command : config.commands()) {
            last = command;
            CommandRunner.Result r = runner.run(command, project.toFile(), config.timeoutSeconds());
            total += r.durationMs();
            if (r.timedOut() || r.startFailed()) {
                String why = r.timedOut() ? "timed out after " + config.timeoutSeconds() + "s" : "could not start";
                sink.accept(event(input, "verify_error", command, total, why + ": " + tail(r.output()),
                    r.timedOut() ? "timeout" : "start_failed"));
                return Optional.empty(); // fail open
            }
            if (r.exitCode() != 0) {
                String tail = tail(r.output());
                sink.accept(event(input, "verify_block", command, total, "exit " + r.exitCode() + ": " + tail, null));
                return Optional.of("Verification failed: `" + SecretRedactor.redact(command) + "` exited "
                    + r.exitCode() + "." + (tail.isEmpty() ? "" : "\n" + tail));
            }
        }
        sink.accept(event(input, "verify_pass", last, total, null, null));
        return Optional.empty();
    }

    /** Consecutive verify_block events at the end of a session's history. */
    static int trailingBlocks(List<UsageEvent> events) {
        int n = 0;
        for (int i = events.size() - 1; i >= 0; i--) {
            String kind = events.get(i).event;
            if ("verify_block".equals(kind)) n++;
            else if ("verify_pass".equals(kind) || "verify_error".equals(kind)) break;
        }
        return n;
    }

    /** Last ~40 lines / 3000 chars, redacted. */
    static String tail(String output) {
        if (output == null) return "";
        String[] lines = output.strip().split("\\R");
        int from = Math.max(0, lines.length - TAIL_LINES);
        String t = String.join("\n", Arrays.copyOfRange(lines, from, lines.length));
        if (t.length() > TAIL_CHARS) t = t.substring(t.length() - TAIL_CHARS);
        return SecretRedactor.redact(t);
    }

    private static UsageEvent event(HookInput input, String kind, String command, Long durationMs,
                                    String error, String reason) {
        String provider = ProviderDetector.detect(input);
        IdentityResolver.ResolvedIdentity identity = IdentityResolver.resolveIdentity(input, provider);
        UsageEvent e = new UsageEvent();
        e.ts = Instant.now().toString();
        e.event = kind;
        e.sessionId = input.sessionId();
        e.provider = provider;
        e.user = identity.user;
        e.project = identity.project;
        e.client = identity.client;
        e.command = command == null ? null : SecretRedactor.redact(command);
        e.durationMs = durationMs;
        e.reason = reason;
        e.stopHookActive = input.stopHookActive();
        e.error = error == null ? null : SecretRedactor.redactError(error);
        return e;
    }
}
