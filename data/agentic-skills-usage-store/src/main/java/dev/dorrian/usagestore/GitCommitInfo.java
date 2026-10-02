package dev.dorrian.usagestore;

public final class GitCommitInfo {
    public String hash;
    public String message;

    public GitCommitInfo() {
    }

    public GitCommitInfo(String hash, String message) {
        this.hash = hash;
        this.message = message;
    }
}
