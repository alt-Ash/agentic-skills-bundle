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

        EventLog.recordEvent(event);
    }
}
