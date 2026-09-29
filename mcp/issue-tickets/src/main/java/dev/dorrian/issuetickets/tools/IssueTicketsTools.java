package dev.dorrian.issuetickets.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dorrian.issuetickets.model.CreateIssueParams;
import dev.dorrian.issuetickets.model.CreatePrParams;
import dev.dorrian.issuetickets.model.NormalizedTicket;
import dev.dorrian.issuetickets.model.PullTicketParams;
import dev.dorrian.issuetickets.providers.AzureDevOpsProvider;
import dev.dorrian.issuetickets.providers.GithubProvider;
import dev.dorrian.issuetickets.providers.TicketProvider;
import dev.dorrian.issuetickets.source.Source;
import dev.dorrian.issuetickets.source.SourceDetector;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * The 3 MCP tools this server exposes. Port of index.ts's {@code registerTool} calls — same
 * names, same descriptions (including {@code pull_ticket}'s "always call with allProjects=true
 * for generic requests" emphasis text), same schemas, same fan-out-to-both-providers behavior
 * when the source is ambiguous.
 *
 * <p>One deliberate deviation from the source, forced by the platform: the source dynamically
 * embeds the live list of configured account names into each tool's {@code account} parameter
 * description at server startup (computed once, e.g. "one of: work, personal"). Spring AI's
 * {@code @Tool}/{@code @ToolParam} descriptions must be compile-time constants, so that dynamic
 * text isn't reproducible here without dropping to a lower-level, hand-built {@code
 * ToolCallback} registration. Functionally this is a minor loss: {@link
 * dev.dorrian.issuetickets.credentials.AzureAccountsResolver#getAccount}/{@link
 * dev.dorrian.issuetickets.credentials.GithubAccountsResolver#getAccount} still list the
 * configured names in their error message the first time a multi-account call omits
 * {@code account}, so the information isn't lost — it just surfaces one round-trip later than
 * in the source.
 */
@Service
public class IssueTicketsTools {

    private static final String SOURCE_UNRESOLVED_MESSAGE =
        "Could not determine ticket source (azure or github) — pass `source` explicitly, or "
            + "configure AZURE_DEVOPS_* / GITHUB_* credentials.";

    private final SourceDetector sourceDetector;
    private final AzureDevOpsProvider azureProvider;
    private final GithubProvider githubProvider;
    private final ObjectMapper mapper = new ObjectMapper();

    public IssueTicketsTools(SourceDetector sourceDetector, AzureDevOpsProvider azureProvider, GithubProvider githubProvider) {
        this.sourceDetector = sourceDetector;
        this.azureProvider = azureProvider;
        this.githubProvider = githubProvider;
    }

    @Tool(name = "pull_ticket", description = "Fetch one or more tickets/issues by ID, or list "
        + "tickets for a project, or list all tickets across all projects. Supports Azure DevOps "
        + "and GitHub. The source is auto-detected from project config files when not specified "
        + "explicitly. IMPORTANT: For any generic request like \"get my tickets\", \"list my "
        + "tickets\", \"get my last ticket\", \"show open tickets\", or any ticket request "
        + "without a specific ID or projectId — always call this tool with allProjects=true. Do "
        + "NOT ask the user for a projectId first. Supply ticketIds to get full detail, including "
        + "the comment/discussion thread, author-flagged asides (e.g. color-styled notes), and "
        + "explicit \"to be elaborated\" open items. Supply projectId to list tickets for a "
        + "specific project. Supply allProjects=true to fetch tickets across all projects "
        + "automatically.")
    public String pullTicket(
        @ToolParam(required = false, description = "Ticket source. Auto-detected from project config files when omitted.")
        Source source,
        @ToolParam(required = false, description = "Which configured account/organization to use. "
            + "Optional if only one is configured for the resolved source.")
        String account,
        @ToolParam(required = false, description = "One or more ticket/work-item IDs to fetch full detail for.")
        List<String> ticketIds,
        @ToolParam(required = false, description = "Azure DevOps project name/ID, or GitHub \"owner/repo\", to list tickets for.")
        String projectId,
        @ToolParam(required = false, description = "Fetch tickets across all projects automatically.")
        Boolean allProjects,
        @ToolParam(required = false, description = "Max tickets to return when listing (default 25).")
        Integer pageSize
    ) {
        Path projectRoot = Path.of(System.getProperty("user.dir"));
        var params = new PullTicketParams(ticketIds, projectId, allProjects, pageSize, account);

        if (source == null && sourceDetector.isAmbiguous(projectRoot)) {
            return pullFromAllProviders(params);
        }

        Source resolved = source != null ? source : sourceDetector.detect(projectRoot)
            .orElseThrow(() -> new IllegalStateException(SOURCE_UNRESOLVED_MESSAGE));
        TicketProvider provider = resolved == Source.azure ? azureProvider : githubProvider;
        List<NormalizedTicket> tickets = provider.pullTicket(params);

        ObjectNode response = mapper.createObjectNode();
        response.put("source", resolved.name());
        response.put("total", tickets.size());
        response.set("tickets", mapper.valueToTree(tickets));
        return toJson(response);
    }

    private String pullFromAllProviders(PullTicketParams params) {
        List<NormalizedTicket> all = new ArrayList<>();
        try {
            all.addAll(azureProvider.pullTicket(params));
        } catch (Exception ignored) {
            // Per-provider errors are swallowed in fan-out mode — one provider's outage doesn't
            // block the other's results.
        }
        try {
            all.addAll(githubProvider.pullTicket(params));
        } catch (Exception ignored) {
            // See above.
        }

        ObjectNode response = mapper.createObjectNode();
        response.put("source", "all");
        response.put("total", all.size());
        response.set("tickets", mapper.valueToTree(all));
        return toJson(response);
    }

    @Tool(name = "create_issue", description = "Create a new ticket, issue, or work item on "
        + "Azure DevOps or GitHub. The source is auto-detected from project config files when not "
        + "specified explicitly. For Azure DevOps, provide a project name or ID and optionally a "
        + "work item type (default: \"Task\"). For GitHub, provide projectId in \"owner/repo\" "
        + "format.")
    public String createIssue(
        @ToolParam(required = false, description = "Ticket source. Auto-detected from project config files when omitted.")
        Source source,
        @ToolParam(required = false, description = "Which configured account/organization to use.")
        String account,
        @ToolParam(description = "Issue/work item title.")
        String title,
        @ToolParam(required = false, description = "Issue/work item description (markdown).")
        String description,
        @ToolParam(description = "Azure DevOps project name/ID, or GitHub \"owner/repo\".")
        String projectId,
        @ToolParam(required = false, description = "Azure DevOps work item type (default \"Task\"). Ignored by GitHub.")
        String type,
        @ToolParam(required = false, description = "Labels/tags to apply.")
        List<String> labels,
        @ToolParam(required = false, description = "Assignee — display name/email for Azure, username for GitHub.")
        String assignee
    ) {
        Source resolved = resolveSource(source);
        TicketProvider provider = resolved == Source.azure ? azureProvider : githubProvider;
        var params = new CreateIssueParams(title, description, projectId, type, labels, assignee, account);
        return toJson(provider.createIssue(params));
    }

    @Tool(name = "create_pull_request", description = "Create a pull request on Azure DevOps or "
        + "GitHub. The source is auto-detected from project config files when not specified "
        + "explicitly. For Azure DevOps, supply repositoryId (repo GUID or name). For GitHub, "
        + "supply repo in \"owner/repo\" format.")
    public String createPullRequest(
        @ToolParam(required = false, description = "Ticket source. Auto-detected from project config files when omitted.")
        Source source,
        @ToolParam(required = false, description = "Which configured account/organization to use.")
        String account,
        @ToolParam(description = "Pull request title.")
        String title,
        @ToolParam(description = "Head/source branch.")
        String sourceBranch,
        @ToolParam(description = "Base/target branch.")
        String targetBranch,
        @ToolParam(required = false, description = "Azure DevOps repository GUID or name.")
        String repositoryId,
        @ToolParam(required = false, description = "GitHub repository in \"owner/repo\" format.")
        String repo,
        @ToolParam(required = false, description = "Pull request description/body.")
        String description
    ) {
        Source resolved = resolveSource(source);
        TicketProvider provider = resolved == Source.azure ? azureProvider : githubProvider;
        var params = new CreatePrParams(title, sourceBranch, targetBranch, repositoryId, repo, description, account);
        return toJson(provider.createPullRequest(params));
    }

    private Source resolveSource(Source explicitSource) {
        if (explicitSource != null) {
            return explicitSource;
        }
        Path projectRoot = Path.of(System.getProperty("user.dir"));
        return sourceDetector.detect(projectRoot).orElseThrow(() -> new IllegalStateException(SOURCE_UNRESOLVED_MESSAGE));
    }

    private String toJson(Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize tool response", e);
        }
    }
}
