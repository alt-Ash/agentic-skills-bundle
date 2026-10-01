package dev.dorrian.issuetickets.model;

/** Port of the TS {@code CreateIssueResult} interface. */
public record CreateIssueResult(String source, String id, String url, String title) {
}
