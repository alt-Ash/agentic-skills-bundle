package dev.dorrian.issuetickets.model;

/** Port of the TS {@code CreatePrParams} interface. */
public record CreatePrParams(
    String title,
    String sourceBranch,
    String targetBranch,
    String repositoryId,
    String repo,
    String description,
    String account
) {
}
