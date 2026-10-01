package dev.dorrian.agenticskillshooks;

import java.util.List;

/**
 * Unified, self-describing record — Java port of hooks/lib/event-log.ts's UsageEvent.
 * Unlike the TS version (which uses `undefined` to omit a key from JSON.stringify for
 * fields that don't apply to a given event kind), every field here is always emitted,
 * using null where TS would have omitted the key. Nothing outside this new Java stack
 * reads these files, so this is a deliberate simplification, not a compatibility gap.
 */
public final class UsageEvent {
    public String eventId;
    public String ts;
    public String event;
    public String sessionId;
    public String provider;
    public String user;
    public String project;
    public String client;
    public String source;
    public String reason;
    public String model;
    public Integer inputTokens;
    public Integer cachedTokens;
    public String toolName;
    public String toolUseId;
    public String error;
    public Long durationMs;
    public Boolean stopHookActive;
    public Integer lastMessageCharLength;
    public Integer estimatedOutputTokens;
    public Integer backgroundTaskCount;
    public Integer promptCharLength;
    public Integer estimatedInputTokens;
    public String permissionMode;
    public String promptId;
    public String gitStartCommit;
    public List<GitCommitInfo> gitCommits;
    public List<String> gitFilesAdded;
    public List<String> gitFilesModified;
    public List<String> gitFilesDeleted;
    public Integer gitLinesAdded;
    public Integer gitLinesDeleted;
    public String command;
    public String slashCommand;
}
