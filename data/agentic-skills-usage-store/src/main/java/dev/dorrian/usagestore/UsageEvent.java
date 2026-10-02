package dev.dorrian.usagestore;

import java.util.List;

/**
 * Unified, self-describing record of one hook firing. Every field is always present, null where
 * it doesn't apply to the event kind. Persisted by {@link UsageDb}; also the JSON shape POSTed
 * to ANALYTICS_SERVICE_URL, so don't rename fields.
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
    public String cwd;
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
    public String guardRule;
}
