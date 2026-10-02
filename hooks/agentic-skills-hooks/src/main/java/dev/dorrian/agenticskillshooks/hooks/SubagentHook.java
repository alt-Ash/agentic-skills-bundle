package dev.dorrian.agenticskillshooks.hooks;

import dev.dorrian.agenticskillshooks.EventLog;
import dev.dorrian.agenticskillshooks.HookInput;
import dev.dorrian.agenticskillshooks.IdentityResolver;
import dev.dorrian.agenticskillshooks.ProviderDetector;
import dev.dorrian.usagestore.UsageEvent;

import java.time.Instant;

/**
 * Records {@code SubagentStart} / {@code SubagentStop} (discriminated by the payload's
 * {@code hook_event_name}) as usage events, like the other analytics hooks: never blocks, always exit 0.
 * Stores only the agent type and id; never the sub-agent's transcript or last message.
 */
public final class SubagentHook {

    private SubagentHook() {
    }

    public static void run(HookInput input) {
        String name = input.hookEventName();
        String kind;
        if ("SubagentStart".equals(name)) kind = "subagent_start";
        else if ("SubagentStop".equals(name)) kind = "subagent_stop";
        else return;

        String provider = ProviderDetector.detect(input);
        IdentityResolver.ResolvedIdentity identity = IdentityResolver.resolveIdentity(input, provider);

        UsageEvent event = new UsageEvent();
        event.ts = Instant.now().toString();
        event.event = kind;
        event.sessionId = input.sessionId();
        event.provider = provider;
        event.user = identity.user;
        event.project = identity.project;
        event.client = identity.client;
        event.agentName = input.agentType();
        event.toolUseId = input.agentId();
        event.stopHookActive = input.stopHookActive();

        EventLog.recordEvent(event);
    }
}
