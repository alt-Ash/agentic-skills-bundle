package dev.dorrian.issuetickets.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dorrian.issuetickets.credentials.GithubAccount;
import dev.dorrian.issuetickets.credentials.GithubAccountsResolver;
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
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Port of providers/github.ts. REST only (never GraphQL). Auth header: {@code token <PAT>}, or
 * {@code bearer <PAT>} if the token string is JWT-shaped (exactly 3 dot-separated segments) —
 * replicates {@code @octokit/auth-token}'s own heuristic exactly, since it's load-bearing for
 * real API compatibility, not an incidental detail.
 */
@Component
public class GithubProvider implements TicketProvider {

    private static final String API_BASE = "https://api.github.com";

    private final GithubAccountsResolver accountsResolver;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiBase;

    @Autowired
    public GithubProvider(GithubAccountsResolver accountsResolver) {
        this(accountsResolver, HttpClient.newHttpClient(), API_BASE);
    }

    /** @param apiBase overridable for tests to point at a local mock server instead of api.github.com. */
    GithubProvider(GithubAccountsResolver accountsResolver, HttpClient httpClient, String apiBase) {
        this.accountsResolver = accountsResolver;
        this.httpClient = httpClient;
        this.apiBase = apiBase;
    }

    public List<NormalizedTicket> pullTicket(PullTicketParams params) {
        GithubAccount account = accountsResolver.getAccount(params.account());

        if (params.ticketIds() != null && !params.ticketIds().isEmpty()) {
            if (params.projectId() == null || params.projectId().isEmpty()) {
                throw new IllegalArgumentException("[issue-tickets/github] pull_ticket requires projectId (\"owner/repo\") when ticketIds are given.");
            }
            String[] ownerRepo = splitOwnerRepo(params.projectId());
            List<NormalizedTicket> tickets = new ArrayList<>();
            for (String ticketId : params.ticketIds()) {
                JsonNode issue = get(account, "/repos/" + ownerRepo[0] + "/" + ownerRepo[1] + "/issues/" + ticketId);
                List<TicketComment> comments = getCommentsBestEffort(account, ownerRepo[0], ownerRepo[1], ticketId);
                tickets.add(normalise(issue, comments));
            }
            return tickets;
        }

        if (params.projectId() != null && !params.projectId().isEmpty()) {
            String[] ownerRepo = splitOwnerRepo(params.projectId());
            String path = "/repos/" + ownerRepo[0] + "/" + ownerRepo[1]
                + "/issues?state=open&sort=updated&direction=desc&per_page=" + params.pageSizeOrDefault();
            JsonNode issues = get(account, path);
            List<NormalizedTicket> tickets = new ArrayList<>();
            issues.forEach(issue -> {
                if (issue.get("pull_request") == null) {
                    tickets.add(normalise(issue, List.of()));
                }
            });
            return tickets;
        }

        throw new IllegalArgumentException("[issue-tickets/github] Provide ticketIds or a projectId (\"owner/repo\").");
    }

    public CreateIssueResult createIssue(CreateIssueParams params) {
        GithubAccount account = accountsResolver.getAccount(params.account());
        if (params.projectId() == null || params.projectId().isEmpty()) {
            throw new IllegalArgumentException("[issue-tickets/github] createIssue requires projectId (\"owner/repo\").");
        }
        String[] ownerRepo = splitOwnerRepo(params.projectId());

        ObjectNode body = mapper.createObjectNode();
        body.put("title", params.title());
        // Deliberately un-defaulted (possibly null/absent), unlike Azure's conditional-omit —
        // preserved asymmetry from the source.
        if (params.description() != null) {
            body.put("body", params.description());
        } else {
            body.putNull("body");
        }
        if (params.labels() != null && !params.labels().isEmpty()) {
            ArrayNode labels = body.putArray("labels");
            params.labels().forEach(labels::add);
        }
        if (params.assignee() != null) {
            ArrayNode assignees = body.putArray("assignees");
            assignees.add(params.assignee());
        }

        JsonNode response = post(account, "/repos/" + ownerRepo[0] + "/" + ownerRepo[1] + "/issues", body);
        return new CreateIssueResult("github", String.valueOf(response.path("number").asInt()),
            response.path("html_url").asText(), params.title());
    }

    public PullRequestResult createPullRequest(CreatePrParams params) {
        GithubAccount account = accountsResolver.getAccount(params.account());
        if (isBlank(params.title()) || isBlank(params.sourceBranch()) || isBlank(params.targetBranch())) {
            throw new IllegalArgumentException(
                "[issue-tickets/github] createPullRequest requires title, sourceBranch, and targetBranch.");
        }
        if (isBlank(params.repo())) {
            throw new IllegalArgumentException("[issue-tickets/github] createPullRequest requires repo (\"owner/repo\").");
        }
        String[] ownerRepo = splitOwnerRepo(params.repo());
        String owner = ownerRepo[0];
        String repo = ownerRepo[1];

        ObjectNode body = mapper.createObjectNode();
        body.put("title", params.title());
        body.put("head", params.sourceBranch());
        body.put("base", params.targetBranch());
        body.put("body", params.description() != null ? params.description() : "");

        try {
            JsonNode response = post(account, "/repos/" + owner + "/" + repo + "/pulls", body);
            return new PullRequestResult("github", String.valueOf(response.path("number").asInt()),
                response.path("html_url").asText(), params.title(), params.sourceBranch(), params.targetBranch(), null);
        } catch (GithubApiException e) {
            if (e.statusCode() != 422) {
                throw e;
            }
            String path = "/repos/" + owner + "/" + repo + "/pulls?head=" + encode(owner + ":" + params.sourceBranch())
                + "&base=" + encode(params.targetBranch()) + "&state=open";
            JsonNode existing = get(account, path);
            if (existing.isArray() && !existing.isEmpty()) {
                JsonNode first = existing.get(0);
                return new PullRequestResult("github", String.valueOf(first.path("number").asInt()),
                    first.path("html_url").asText(), params.title(), params.sourceBranch(), params.targetBranch(), true);
            }
            throw e;
        }
    }

    // ─── HTTP helpers ──────────────────────────────────────────────────────

    private List<TicketComment> getCommentsBestEffort(GithubAccount account, String owner, String repo, String issueNumber) {
        try {
            JsonNode comments = get(account, "/repos/" + owner + "/" + repo + "/issues/" + issueNumber + "/comments");
            List<TicketComment> result = new ArrayList<>();
            comments.forEach(c -> result.add(new TicketComment(
                c.path("user").path("login").asText(null),
                c.path("body").asText(null),
                c.path("created_at").asText(null)
            )));
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private NormalizedTicket normalise(JsonNode issue, List<TicketComment> comments) {
        List<String> labels = new ArrayList<>();
        issue.path("labels").forEach(l -> labels.add(l.isTextual() ? l.asText() : l.path("name").asText()));

        String body = issue.path("body").asText(null);
        return new NormalizedTicket(
            "github",
            String.valueOf(issue.path("number").asInt()),
            issue.path("title").asText(null),
            body,
            issue.path("state").asText(null),
            issue.path("html_url").asText(null),
            issue.path("assignee").path("login").asText(null),
            labels,
            issue.path("created_at").asText(null),
            issue.path("updated_at").asText(null),
            comments.isEmpty() ? null : comments,
            null,
            TicketTextExtractor.extractOpenItems(body),
            issue
        );
    }

    private JsonNode get(GithubAccount account, String path) {
        return send(account, "GET", path, null);
    }

    private JsonNode post(GithubAccount account, String path, JsonNode body) {
        return send(account, "POST", path, body);
    }

    private JsonNode send(GithubAccount account, String method, String path, JsonNode body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiBase + path))
                .header("Authorization", authHeader(account.token()))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28");
            if (body != null) {
                builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new GithubApiException(response.statusCode(),
                    "[issue-tickets/github] Request to " + path + " failed with HTTP " + response.statusCode()
                        + ": " + response.body());
            }
            return mapper.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("[issue-tickets/github] Request to " + path + " failed.", e);
        }
    }

    /** Port of {@code @octokit/auth-token}'s own scheme-selection heuristic. */
    static String authHeader(String token) {
        boolean jwtShaped = token.split("\\.", -1).length == 3;
        return (jwtShaped ? "bearer " : "token ") + token;
    }

    private static String[] splitOwnerRepo(String projectId) {
        String[] parts = projectId.split("/", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("[issue-tickets/github] projectId must be in \"owner/repo\" format, got: " + projectId);
        }
        return parts;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    /** Carries the HTTP status code so callers can detect the 422-already-exists case. */
    static final class GithubApiException extends RuntimeException {
        private final int statusCode;

        GithubApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        int statusCode() {
            return statusCode;
        }
    }
}
