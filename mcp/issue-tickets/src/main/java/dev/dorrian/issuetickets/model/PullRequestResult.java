package dev.dorrian.issuetickets.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Port of the TS {@code PullRequestResult} interface. {@code alreadyExisted} is only set true
 * by GitHub's 422-fallback path (an existing open PR for the same head/base was found instead
 * of creating a new one) — Azure DevOps has no equivalent fallback.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PullRequestResult(
    String source, String id, String url, String title,
    String sourceBranch, String targetBranch, Boolean alreadyExisted
) {
}
