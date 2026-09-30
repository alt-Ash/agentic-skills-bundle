package dev.dorrian.agenticskillshooks.hooks;

import dev.dorrian.agenticskillshooks.EventLog;
import dev.dorrian.agenticskillshooks.HookInput;
import dev.dorrian.agenticskillshooks.IdentityResolver;
import dev.dorrian.agenticskillshooks.ProviderDetector;
import dev.dorrian.agenticskillshooks.SlashCommandExtractor;
import dev.dorrian.agenticskillshooks.UsageEvent;

import java.time.Instant;

/**
 * Java port of hooks/user-prompt-submit.ts. Fires every time a user submits a prompt, before
 * the model runs. The prompt text is never stored — only its length.
 */
public final class UserPromptSubmitHook {

    private UserPromptSubmitHook() {
    }

    public static UsageEvent buildEvent(HookInput input) {
        String provider = ProviderDetector.detect(input);
        IdentityResolver.ResolvedIdentity identity = IdentityResolver.resolveIdentity(input, provider);

        String prompt = input.prompt();
        Integer promptCharLength = prompt != null ? prompt.length() : null;

        UsageEvent event = new UsageEvent();
        event.ts = Instant.now().toString();
        event.event = "user_prompt";
        event.sessionId = input.sessionId();
        event.provider = provider;
        event.user = identity.user;
        event.project = identity.project;
        event.client = identity.client;
        event.promptCharLength = promptCharLength;
        event.estimatedInputTokens = promptCharLength != null ? Math.round(promptCharLength / 4.0f) : null;
        event.permissionMode = input.permissionMode();
        event.promptId = input.promptId();
        event.slashCommand = SlashCommandExtractor.extract(prompt);
        return event;
    }

    public static void run(HookInput input) {
        EventLog.recordEvent("user-prompt-submit", buildEvent(input));
    }
}
