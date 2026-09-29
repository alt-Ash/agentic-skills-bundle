package dev.dorrian.issuetickets.providers;

import dev.dorrian.issuetickets.model.CreateIssueParams;
import dev.dorrian.issuetickets.model.CreateIssueResult;
import dev.dorrian.issuetickets.model.CreatePrParams;
import dev.dorrian.issuetickets.model.NormalizedTicket;
import dev.dorrian.issuetickets.model.PullRequestResult;
import dev.dorrian.issuetickets.model.PullTicketParams;
import java.util.List;

/** Common shape both {@link AzureDevOpsProvider} and {@link GithubProvider} implement, so the
 * tool layer can dispatch on {@link dev.dorrian.issuetickets.source.Source} without an if/else
 * per method call. */
public interface TicketProvider {

    List<NormalizedTicket> pullTicket(PullTicketParams params);

    CreateIssueResult createIssue(CreateIssueParams params);

    PullRequestResult createPullRequest(CreatePrParams params);
}
