package dev.dorrian.agenticskillshooks.hooks;

import dev.dorrian.agenticskillshooks.EventLog;
import dev.dorrian.agenticskillshooks.HookInput;
import dev.dorrian.agenticskillshooks.IdentityResolver;
import dev.dorrian.agenticskillshooks.ProviderDetector;
import dev.dorrian.usagestore.UsageEvent;

import java.time.Instant;

/**
 * Java port of hooks/stop.ts. Fires at the end of every turn. Records output size as a
 * character-length proxy — the message content itself is not stored.
 */
public final class StopHook {

    private StopHook() {
    }

    public static void run(HookInput input) {
        String provider = ProviderDetector.detect(input);
        String lastMessage = input.lastAssistantMessage();
        int msgLength = lastMessage != null ? lastMessage.length() : 0;

        IdentityResolver.ResolvedIdentity identity = IdentityResolver.resolveIdentity(input, provider);

        UsageEvent event = new UsageEvent();
        event.ts = Instant.now().toString();
        event.event = "turn_stop";
        event.sessionId = input.sessionId();
        event.provider = provider;
        event.user = identity.user;
        event.project = identity.project;
        event.client = identity.client;
        event.stopHookActive = input.stopHookActive();
        event.lastMessageCharLength = msgLength;
        event.estimatedOutputTokens = Math.round(msgLength / 4.0f);
        event.backgroundTaskCount = input.backgroundTaskCount();
        applyUsage(input, event);

        EventLog.recordEvent(event);
    }

    /** Copies real model/token usage from the transcript onto the event; any failure leaves it untouched. */
    static void applyUsage(HookInput input, UsageEvent event) {
        try {
            PostToolUseHook.UsageRecord r = PostToolUseHook.buildRecord(input);
            if (!"unavailable".equals(r.model)) event.model = r.model;
            event.inputTokens = r.inputTokens;
            // The turn's total across all its API messages; falls back to the last message's number.
            event.outputTokens = r.turnOutputTokens != null ? r.turnOutputTokens : r.outputTokens;
            event.cacheReadTokens = r.cacheReadTokens;
            event.cacheCreationTokens = r.cacheCreationTokens;
            event.cachedTokens = r.cachedTokens;
        } catch (RuntimeException ignored) {
            // fail open: the turn_stop event is still recorded without usage
        }
    }
}
