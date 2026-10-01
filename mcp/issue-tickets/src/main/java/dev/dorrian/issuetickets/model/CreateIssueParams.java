package dev.dorrian.issuetickets.model;

import java.util.List;

/** Port of the TS {@code CreateIssueParams} interface. */
public record CreateIssueParams(
    String title,
    String description,
    String projectId,
    String type,
    List<String> labels,
    String assignee,
    String account
) {
}
