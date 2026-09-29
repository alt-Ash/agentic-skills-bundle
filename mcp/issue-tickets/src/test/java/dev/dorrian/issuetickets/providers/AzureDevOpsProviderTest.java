package dev.dorrian.issuetickets.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.dorrian.issuetickets.credentials.AzureAccountsResolver;
import dev.dorrian.issuetickets.model.CreateIssueParams;
import dev.dorrian.issuetickets.model.CreatePrParams;
import dev.dorrian.issuetickets.model.PullTicketParams;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AzureDevOpsProviderTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private record CapturedRequest(String method, String path, String authHeader, String fedAuthHeader, String body) {
    }

    private AzureDevOpsProvider startProvider(Map<String, ResponseByPath> responses) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            ResponseByPath configured = responses.get(path);
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            if (configured != null && configured.captured != null) {
                configured.captured.set(new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("X-TFS-FedAuthRedirect"),
                    new String(requestBody, StandardCharsets.UTF_8)));
            }
            String responseBody = configured != null ? configured.body : "{}";
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        String orgUrl = "http://localhost:" + server.getAddress().getPort();
        var resolver = new AzureAccountsResolver(Map.of("AZURE_DEVOPS_ORG_URL", orgUrl, "AZURE_DEVOPS_TOKEN", "pat123")::get);
        return new AzureDevOpsProvider(resolver, HttpClient.newHttpClient());
    }

    private record ResponseByPath(String body, AtomicReference<CapturedRequest> captured) {
        static ResponseByPath of(String body) {
            return new ResponseByPath(body, new AtomicReference<>());
        }
    }

    @Test
    void pullTicketByIdSendsBasicAuthWithPatPrefixAndCorrectApiVersion() throws IOException {
        var workItemsResponse = ResponseByPath.of("""
            {"value":[{"id":42,"fields":{"System.Title":"Bug","System.State":"Active","System.TeamProject":"MyProj"}}]}
            """);
        var provider = startProvider(Map.of("/_apis/wit/workitems", workItemsResponse));

        var tickets = provider.pullTicket(new PullTicketParams(List.of("42"), null, null, null, null));

        assertThat(tickets).hasSize(1);
        assertThat(tickets.get(0).title()).isEqualTo("Bug");
        assertThat(tickets.get(0).status()).isEqualTo("Active");

        var captured = workItemsResponse.captured.get();
        assertThat(captured.path()).contains("ids=42", "api-version=7.1-preview.3");
        assertThat(captured.fedAuthHeader()).isEqualTo("Suppress");
        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString("PAT:pat123".getBytes(StandardCharsets.UTF_8));
        assertThat(captured.authHeader()).isEqualTo(expectedAuth);
    }

    @Test
    void pullTicketByProjectIdUsesWiqlThenBatchFetchesAndTruncatesToPageSize() throws IOException {
        var wiqlResponse = ResponseByPath.of("""
            {"workItems":[{"id":1},{"id":2},{"id":3}]}
            """);
        var workItemsResponse = ResponseByPath.of("""
            {"value":[{"id":1,"fields":{"System.Title":"One"}},{"id":2,"fields":{"System.Title":"Two"}}]}
            """);
        var provider = startProvider(Map.of(
            "/MyProj/_apis/wit/wiql", wiqlResponse,
            "/_apis/wit/workitems", workItemsResponse
        ));

        var tickets = provider.pullTicket(new PullTicketParams(null, "MyProj", null, 2, null));

        assertThat(tickets).hasSize(2);
        assertThat(wiqlResponse.captured.get().body()).contains("[System.TeamProject] = 'MyProj'", "[System.State] <> 'Closed'");
        // pageSize=2 truncation happens client-side before the batch fetch — confirm only 2 ids requested.
        assertThat(workItemsResponse.captured.get().path()).contains("ids=1,2");
    }

    @Test
    void createIssueSendsJsonPatchDocumentWithConditionalFields() throws IOException {
        var response = ResponseByPath.of("""
            {"id":100,"_links":{"html":{"href":"https://dev.azure.com/org/_workitems/edit/100"}}}
            """);
        // HttpExchange#getRequestURI().getPath() returns the decoded path, so the map key here
        // is "$Task" even though the real request on the wire encodes it as "%24Task".
        var provider = startProvider(Map.of("/MyProj/_apis/wit/workitems/$Task", response));

        var result = provider.createIssue(new CreateIssueParams("New task", "desc", "MyProj", null,
            List.of("a", "b"), "alice", null));

        assertThat(result.id()).isEqualTo("100");
        String body = response.captured.get().body();
        assertThat(body).contains("\"path\":\"/fields/System.Title\"", "\"value\":\"New task\"");
        assertThat(body).contains("System.Tags", "a; b");
        assertThat(body).contains("System.AssignedTo", "alice");
    }

    @Test
    void createPullRequestPrefixesBranchNamesWithRefsHeads() throws IOException {
        var response = ResponseByPath.of("""
            {"pullRequestId":55,"_links":{"web":{"href":"https://dev.azure.com/org/proj/_git/repo/pullrequest/55"}}}
            """);
        var provider = startProvider(Map.of("/_apis/git/repositories/repo-id/pullrequests", response));

        var result = provider.createPullRequest(new CreatePrParams("Title", "feature", "main", "repo-id", null, null, null));

        assertThat(result.id()).isEqualTo("55");
        String body = response.captured.get().body();
        assertThat(body).contains("\"sourceRefName\":\"refs/heads/feature\"", "\"targetRefName\":\"refs/heads/main\"");
    }

    @Test
    void createPullRequestDoesNotDoublePrefixAnAlreadyQualifiedRef() throws IOException {
        var response = ResponseByPath.of("{\"pullRequestId\":1}");
        var provider = startProvider(Map.of("/_apis/git/repositories/repo-id/pullrequests", response));

        provider.createPullRequest(new CreatePrParams("T", "refs/heads/feature", "refs/heads/main", "repo-id", null, null, null));

        String body = response.captured.get().body();
        assertThat(body).contains("\"sourceRefName\":\"refs/heads/feature\"");
        assertThat(body).doesNotContain("refs/heads/refs/heads");
    }
}
