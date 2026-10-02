package dev.dorrian.agenticskillshooks.hooks;

import dev.dorrian.agenticskillshooks.EventLog;
import dev.dorrian.agenticskillshooks.HookInput;
import dev.dorrian.agenticskillshooks.IdentityResolver;
import dev.dorrian.agenticskillshooks.ProviderDetector;
import dev.dorrian.usagestore.SecretRedactor;
import dev.dorrian.usagestore.UsageEvent;

import java.time.Instant;

/** Java port of hooks/post-tool-use-failure.ts. Fires when a tool call fails. */
public final class PostToolUseFailureHook {

    private PostToolUseFailureHook() {
    }

    public static void run(HookInput input) {
        String provider = ProviderDetector.detect(input);
        IdentityResolver.ResolvedIdentity identity = IdentityResolver.resolveIdentity(input, provider);

        UsageEvent event = new UsageEvent();
        event.ts = Instant.now().toString();
        event.event = "tool_failure";
        event.sessionId = input.sessionId();
        event.provider = provider;
        event.user = identity.user;
        event.project = identity.project;
        event.client = identity.client;
        event.toolName = input.toolName();
        event.toolUseId = input.toolUseId();
        event.durationMs = input.durationMs();
        event.error = SecretRedactor.redactError(input.error());
        event.command = input.extractBashCommand();

        EventLog.recordEvent(event);
    }
}
