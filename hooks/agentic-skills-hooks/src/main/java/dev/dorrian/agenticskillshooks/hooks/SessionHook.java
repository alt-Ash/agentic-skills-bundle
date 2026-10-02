package dev.dorrian.agenticskillshooks.hooks;

import dev.dorrian.agenticskillshooks.AnalyticsServiceClient;
import dev.dorrian.agenticskillshooks.EventLog;
import dev.dorrian.agenticskillshooks.GitProcess;
import dev.dorrian.agenticskillshooks.GitSessionDiffResolver;
import dev.dorrian.agenticskillshooks.HookInput;
import dev.dorrian.agenticskillshooks.IdentityResolver;
import dev.dorrian.agenticskillshooks.ProviderDetector;
import dev.dorrian.usagestore.UsageDb;
import dev.dorrian.usagestore.UsageEvent;

import java.time.Instant;
import java.util.List;

/**
 * Java port of hooks/session.ts. One source registered against both SessionStart and
 * SessionEnd; the `hook_event_name` discriminates which.
 */
public final class SessionHook {

    private SessionHook() {
    }

    // Maps each CLI's start/end event names to our normalized kind. Unknown names default
    // to a start event.
    private static String eventKind(HookInput input) {
        String name = input.hookEventName() != null ? input.hookEventName().toLowerCase() : "";
        if (name.contains("end") || name.contains("stop")) return "session_end";
        return "session_start";
    }

    public static UsageEvent buildEvent(HookInput input) {
        String provider = ProviderDetector.detect(input);
        String kind = eventKind(input);
        IdentityResolver.ResolvedIdentity identity = IdentityResolver.resolveIdentity(input, provider);

        UsageEvent event = new UsageEvent();
        event.ts = Instant.now().toString();
        event.event = kind;
        event.sessionId = input.sessionId();
        event.provider = provider;
        event.user = identity.user;
        event.project = identity.project;
        event.client = identity.client;
        event.source = "session_start".equals(kind) ? input.source() : null;
        event.reason = "session_end".equals(kind) ? input.reason() : null;

        if ("session_start".equals(kind)) {
            event.gitStartCommit = GitProcess.runGit("rev-parse", "HEAD");
        }

        // Recover the commit sha recorded at session_start so we can diff "what changed
        // this session" against it.
        if ("session_end".equals(kind)) {
            UsageDb.SessionRow stored = EventLog.session(input.sessionId());
            GitSessionDiffResolver.GitChangeSummary summary =
                    GitSessionDiffResolver.summarize(stored != null ? stored.gitStartCommit() : null);
            event.gitCommits = summary.commits;
            event.gitFilesAdded = summary.filesAdded;
            event.gitFilesModified = summary.filesModified;
            event.gitFilesDeleted = summary.filesDeleted;
            event.gitLinesAdded = summary.linesAdded;
            event.gitLinesDeleted = summary.linesDeleted;
        }

        return event;
    }

    public static void run(HookInput input) {
        UsageEvent event = buildEvent(input);
        EventLog.recordEvent(event);

        if ("session_end".equals(event.event)) {
            String id = input.sessionId();
            if (id != null) {
                List<UsageEvent> hooks = EventLog.sessionEvents(id);
                boolean hasSessionStart = hooks.stream().anyMatch(h -> "session_start".equals(h.event));
                if (!hasSessionStart) {
                    EventLog.bump(EventLog.SESSION_END_WITHOUT_START);
                } else {
                    AnalyticsServiceClient.pushSession(id, hooks);
                }
            }
        }
    }
}
