package dev.dorrian.issuetickets.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dorrian.issuetickets.credentials.AzureAccount;
import dev.dorrian.issuetickets.credentials.AzureAccountsResolver;
import dev.dorrian.issuetickets.model.CreateIssueParams;
import dev.dorrian.issuetickets.model.CreateIssueResult;
import dev.dorrian.issuetickets.model.CreatePrParams;
import dev.dorrian.issuetickets.model.NormalizedTicket;
import dev.dorrian.issuetickets.model.PullRequestResult;
import dev.dorrian.issuetickets.model.PullTicketParams;
import dev.dorrian.issuetickets.model.TicketComment;
import dev.dorrian.issuetickets.text.TicketTextExtractor;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Port of providers/azure.ts. Auth: {@code Authorization: Basic base64("PAT:" + token)} (the
 * exact scheme {@code azure-devops-node-api}'s {@code PersonalAccessTokenCredentialHandler}
 * uses under the hood) plus {@code X-TFS-FedAuthRedirect: Suppress}.
 *
 * <p>Exact endpoints/API versions preserved from the source — see each method's javadoc.
 */
@Component
public class AzureDevOpsProvider implements TicketProvider {

    private final AzureAccountsResolver accountsResolver;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public AzureDevOpsProvider(AzureAccountsResolver accountsResolver) {
        this(accountsResolver, HttpClient.newHttpClient());
    }

    AzureDevOpsProvider(AzureAccountsResolver accountsResolver, HttpClient httpClient) {
        this.accountsResolver = accountsResolver;
        this.httpClient = httpClient;
    }

    public List<NormalizedTicket> pullTicket(PullTicketParams params) {
        AzureAccount account = accountsResolver.getAccount(params.account());

        if (params.ticketIds() != null && !params.ticketIds().isEmpty()) {
            List<Integer> ids = params.ticketIds().stream()
                .map(this::toIntOrNull)
                .filter(id -> id != null)
                .toList();
            List<JsonNode> workItems = getWorkItems(account, ids);
            List<NormalizedTicket> tickets = new ArrayList<>();
            for (JsonNode item : workItems) {
                List<TicketComment> comments = getCommentsBestEffort(account, item);
                tickets.add(normalise(account, item, comments));
            }
            return tickets;
        }

        if (params.projectId() != null && !params.projectId().isEmpty()) {
            String project = params.projectId();
            String wiql = "SELECT [System.Id] FROM WorkItems WHERE [System.TeamProject] = '" + project
                + "' AND [System.State] <> 'Closed' ORDER BY [System.ChangedDate] DESC";
            List<Integer> ids = queryByWiql(account, project, wiql).stream()
                .limit(params.pageSizeOrDefault())
                .toList();
            List<JsonNode> workItems = getWorkItems(account, ids);
            return workItems.stream().map(item -> normalise(account, item, List.of())).toList();
        }

        throw new IllegalArgumentException("[issue-tickets/azure] Provide ticketIds or a projectId.");
    }

    public CreateIssueResult createIssue(CreateIssueParams params) {
        AzureAccount account = accountsResolver.getAccount(params.account());
        if (params.projectId() == null || params.projectId().isEmpty()) {
            throw new IllegalArgumentException("[issue-tickets/azure] createIssue requires projectId.");
        }
        String type = params.type() != null && !params.type().isEmpty() ? params.type() : "Task";

        ArrayNode patch = mapper.createArrayNode();
        patch.add(patchOp("/fields/System.Title", params.title()));
        if (params.description() != null) {
            patch.add(patchOp("/fields/System.Description", params.description()));
        }
        if (params.labels() != null && !params.labels().isEmpty()) {
            patch.add(patchOp("/fields/System.Tags", String.join("; ", params.labels())));
        }
        if (params.assignee() != null) {
            patch.add(patchOp("/fields/System.AssignedTo", params.assignee()));
        }

        String url = account.orgUrl() + "/" + encodePathSegment(params.projectId())
            + "/_apis/wit/workitems/" + encodePathSegment("$" + type) + "?api-version=7.1-preview.3";

        JsonNode response = send(account, "POST", url, writeJson(patch), "application/json-patch+json");
        int id = response.path("id").asInt();
        String htmlUrl = response.path("_links").path("html").path("href").asText(null);
        String resolvedUrl = htmlUrl != null ? htmlUrl : account.orgUrl() + "/_workitems/edit/" + id;
        return new CreateIssueResult("azure", String.valueOf(id), resolvedUrl, params.title());
    }

    public PullRequestResult createPullRequest(CreatePrParams params) {
        AzureAccount account = accountsResolver.getAccount(params.account());
        if (isBlank(params.title()) || isBlank(params.sourceBranch()) || isBlank(params.targetBranch())) {
            throw new IllegalArgumentException(
                "[issue-tickets/azure] createPullRequest requires title, sourceBranch, and targetBranch.");
        }
        if (isBlank(params.repositoryId())) {
            throw new IllegalArgumentException("[issue-tickets/azure] createPullRequest requires repositoryId.");
        }

        String sourceRef = withRefsHeadsPrefix(params.sourceBranch());
        String targetRef = withRefsHeadsPrefix(params.targetBranch());

        ObjectNode body = mapper.createObjectNode();
        body.put("title", params.title());
        body.put("description", params.description() != null ? params.description() : "");
        body.put("sourceRefName", sourceRef);
        body.put("targetRefName", targetRef);

        String url = account.orgUrl() + "/_apis/git/repositories/" + encodePathSegment(params.repositoryId())
            + "/pullrequests?api-version=7.2-preview.2";

        JsonNode response = send(account, "POST", url, writeJson(body), "application/json");
        int id = response.path("pullRequestId").asInt();
        String prUrl = response.path("_links").path("web").path("href").asText(
            account.orgUrl() + "/_git/" + params.repositoryId() + "/pullrequest/" + id);
        return new PullRequestResult("azure", String.valueOf(id), prUrl, params.title(),
            params.sourceBranch(), params.targetBranch(), null);
    }

    // ─── HTTP helpers ──────────────────────────────────────────────────────

    private List<JsonNode> getWorkItems(AzureAccount account, List<Integer> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String idsParam = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        String url = account.orgUrl() + "/_apis/wit/workitems?ids=" + idsParam + "&api-version=7.1-preview.3";
        JsonNode response = send(account, "GET", url, null, null);
        List<JsonNode> result = new ArrayList<>();
        response.path("value").forEach(result::add);
        return result;
    }

    /** Best-effort — returns an empty list on any failure, matching the source's try/catch. */
    private List<TicketComment> getCommentsBestEffort(AzureAccount account, JsonNode workItem) {
        try {
            String project = workItem.path("fields").path("System.TeamProject").asText();
            int id = workItem.path("id").asInt();
            String url = account.orgUrl() + "/" + encodePathSegment(project)
                + "/_apis/wit/workItems/" + id + "/comments?api-version=7.1-preview.3";
            JsonNode response = send(account, "GET", url, null, null);
            List<TicketComment> comments = new ArrayList<>();
            response.path("comments").forEach(c -> comments.add(new TicketComment(
                c.path("createdBy").path("displayName").asText(null),
                c.path("text").asText(null),
                c.path("createdDate").asText(null)
            )));
            return comments;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Integer> queryByWiql(AzureAccount account, String project, String wiql) {
        ObjectNode body = mapper.createObjectNode();
        body.put("query", wiql);
        String url = account.orgUrl() + "/" + encodePathSegment(project) + "/_apis/wit/wiql?api-version=7.1-preview.2";
        JsonNode response = send(account, "POST", url, writeJson(body), "application/json");
        List<Integer> ids = new ArrayList<>();
        response.path("workItems").forEach(w -> ids.add(w.path("id").asInt()));
        return ids;
    }

    private NormalizedTicket normalise(AzureAccount account, JsonNode item, List<TicketComment> comments) {
        JsonNode fields = item.path("fields");
        int id = item.path("id").asInt();
        String description = fields.path("System.Description").asText(null);
        String htmlUrl = item.path("_links").path("html").path("href").asText(null);
        String url = htmlUrl != null ? htmlUrl : account.orgUrl() + "/_workitems/edit/" + id;
        String tags = fields.path("System.Tags").asText(null);
        List<String> labels = tags != null && !tags.isEmpty()
            ? List.of(tags.split("\\s*;\\s*"))
            : List.of();

        return new NormalizedTicket(
            "azure",
            String.valueOf(id),
            fields.path("System.Title").asText(null),
            description,
            fields.path("System.State").asText(null),
            url,
            fields.path("System.AssignedTo").path("displayName").asText(null),
            labels,
            fields.path("System.CreatedDate").asText(null),
            fields.path("System.ChangedDate").asText(null),
            comments.isEmpty() ? null : comments,
            TicketTextExtractor.extractFlaggedAsides(description),
            TicketTextExtractor.extractOpenItems(description),
            item
        );
    }

    private JsonNode send(AzureAccount account, String method, String url, String body, String contentType) {
        try {
            String auth = Base64.getEncoder().encodeToString(("PAT:" + account.token()).getBytes(StandardCharsets.UTF_8));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Basic " + auth)
                .header("X-TFS-FedAuthRedirect", "Suppress")
                .header("Accept", "application/json");
            if (contentType != null) {
                builder.header("Content-Type", contentType);
            }
            builder.method(method, body != null
                ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                : HttpRequest.BodyPublishers.noBody());

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("[issue-tickets/azure] Request to " + url
                    + " failed with HTTP " + response.statusCode() + ": " + response.body());
            }
            return mapper.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("[issue-tickets/azure] Request to " + url + " failed.", e);
        }
    }

    private String writeJson(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ObjectNode patchOp(String path, String value) {
        ObjectNode op = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        op.put("op", "add");
        op.put("path", path);
        op.put("value", value);
        return op;
    }

    private static String withRefsHeadsPrefix(String branch) {
        return branch.startsWith("refs/") ? branch : "refs/heads/" + branch;
    }

    private static String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    private Integer toIntOrNull(String s) {
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
