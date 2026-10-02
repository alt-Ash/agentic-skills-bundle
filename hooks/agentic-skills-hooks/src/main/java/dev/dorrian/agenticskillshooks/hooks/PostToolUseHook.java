package dev.dorrian.agenticskillshooks.hooks;

import dev.dorrian.agenticskillshooks.EventLog;
import dev.dorrian.agenticskillshooks.HookInput;
import dev.dorrian.agenticskillshooks.IdentityResolver;
import dev.dorrian.agenticskillshooks.ProviderDetector;
import dev.dorrian.usagestore.UsageEvent;
import dev.dorrian.agenticskillshooks.transcript.ClaudeTranscriptParser;
import dev.dorrian.agenticskillshooks.transcript.CodexTranscriptParser;

import java.time.Instant;

/** Java port of hooks/post-tool-use.ts. Fires after each successful tool call. */
public final class PostToolUseHook {

    private PostToolUseHook() {
    }

    public static final class UsageRecord {
        public String provider;
        public String model;
        public Integer inputTokens;
        public Integer cachedTokens;
        public Integer outputTokens;
        public Integer cacheReadTokens;
        public Integer cacheCreationTokens;
    }

    public static UsageRecord buildRecord(HookInput input) {
        String provider = ProviderDetector.detect(input);
        String transcript = input.transcriptPath();

        String model = null;
        Integer inputTokens = null;
        Integer cachedTokens = null;
        Integer outputTokens = null;
        Integer cacheReadTokens = null;
        Integer cacheCreationTokens = null;

        if ("claude".equals(provider) && transcript != null) {
            ClaudeTranscriptParser.Extracted e = ClaudeTranscriptParser.extract(transcript);
            model = e.model;
            inputTokens = e.inputTokens;
            cachedTokens = e.cachedTokens;
            outputTokens = e.outputTokens;
            cacheReadTokens = e.cacheReadTokens;
            cacheCreationTokens = e.cacheCreationTokens;
        } else if ("codex".equals(provider)) {
            if (transcript != null) {
                CodexTranscriptParser.Extracted e = CodexTranscriptParser.extract(transcript);
                model = e.model;
                inputTokens = e.inputTokens;
                cachedTokens = e.cachedTokens;
            outputTokens = e.outputTokens;
            cacheReadTokens = e.cacheReadTokens;
            cacheCreationTokens = e.cacheCreationTokens;
            }
            if (input.model() != null) model = input.model();
        } else if ("antigravity".equals(provider)) {
            model = input.model(); // from the payload's modelName; token usage is not read from its transcript
        } else if ("cursor".equals(provider)) {
            model = input.model();
            // tokens intentionally left null — Cursor hooks do not expose them.
        }

        UsageRecord record = new UsageRecord();
        record.provider = provider;
        record.model = model != null ? model : "unavailable";
        record.inputTokens = inputTokens;
        record.cachedTokens = cachedTokens;
        record.outputTokens = outputTokens;
        record.cacheReadTokens = cacheReadTokens;
        record.cacheCreationTokens = cacheCreationTokens;
        return record;
    }

    public static void run(HookInput input) {
        UsageRecord record = buildRecord(input);
        String provider = ProviderDetector.detect(input);
        IdentityResolver.ResolvedIdentity identity = IdentityResolver.resolveIdentity(input, provider);

        UsageEvent event = new UsageEvent();
        event.ts = Instant.now().toString();
        event.event = "tool_use";
        event.sessionId = input.sessionId();
        event.provider = record.provider;
        event.user = identity.user;
        event.project = identity.project;
        event.client = identity.client;
        event.model = !"unavailable".equals(record.model) ? record.model : null;
        event.inputTokens = record.inputTokens;
        event.cachedTokens = record.cachedTokens;
        event.outputTokens = record.outputTokens;
        event.cacheReadTokens = record.cacheReadTokens;
        event.cacheCreationTokens = record.cacheCreationTokens;
        event.toolName = input.toolName();
        event.toolUseId = input.toolUseId();
        event.durationMs = input.durationMs();
        event.command = input.extractBashCommand();
        ToolAttribution.apply(input, event);

        EventLog.recordEvent(event);
    }
}
