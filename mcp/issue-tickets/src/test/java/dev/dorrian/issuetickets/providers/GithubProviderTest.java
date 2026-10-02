package dev.dorrian.issuetickets.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.dorrian.issuetickets.HttpTimeoutProperties;
import dev.dorrian.issuetickets.credentials.GithubAccountsResolver;
import dev.dorrian.issuetickets.model.CreateIssueParams;
import dev.dorrian.issuetickets.model.CreatePrParams;
import dev.dorrian.issuetickets.model.PullTicketParams;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class GithubProviderTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private record CapturedRequest(String method, String path, String authHeader, String body) {
    }

    private GithubProvider startServerAndProvider(String path, int status, String responseBody, AtomicReference<CapturedRequest> captured)
        throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(path.split("\\?")[0], exchange -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            // registerContext matches by path prefix, so a request to e.g. .../issues/42/comments
            // also lands here — only capture the first request (the one this test cares about).
            captured.compareAndSet(null, new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().toString(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                new String(requestBody, StandardCharsets.UTF_8)));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        String apiBase = "http://localhost:" + server.getAddress().getPort();
        var resolver = new GithubAccountsResolver(Map.of("GITHUB_TOKEN", "ghp_testtoken123")::get);
        return new GithubProvider(resolver, HttpClient.newHttpClient(), apiBase, Duration.ofSeconds(5));
    }

    @Test
    void requestTimeoutIsAppliedToEveryRequest() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(2000);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException | IOException ignored) {
                // client gave up on the slow response, which is what the test expects
            } finally {
                exchange.close();
            }
        });
        server.start();
        String apiBase = "http://localhost:" + server.getAddress().getPort();
        var resolver = new GithubAccountsResolver(Map.of("GITHUB_TOKEN", "t")::get);
        var provider = new GithubProvider(resolver, HttpClient.newHttpClient(), apiBase, Duration.ofMillis(200));

        assertThatThrownBy(() -> provider.pullTicket(new PullTicketParams(List.of("1"), "acme/widgets", null, null, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasCauseInstanceOf(HttpTimeoutException.class);
    }

    @Test
    void propertiesBuildClientWithConfiguredConnectTimeout() {
        var props = new HttpTimeoutProperties(Duration.ofSeconds(3), Duration.ofSeconds(7));
        var resolver = new GithubAccountsResolver(Map.of("GITHUB_TOKEN", "t")::get);

        var provider = new GithubProvider(resolver, props);

        var client = (HttpClient) ReflectionTestUtils.getField(provider, "httpClient");
        assertThat(client.connectTimeout()).contains(Duration.ofSeconds(3));
        assertThat(ReflectionTestUtils.getField(provider, "requestTimeout")).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void pullTicketByIdSendsCorrectPathAndAuthHeader() throws IOException {
        var captured = new AtomicReference<CapturedRequest>();
        var provider = startServerAndProvider("/repos/acme/widgets/issues/42", 200, """
            {"number":42,"title":"Bug","body":"desc","state":"open","html_url":"https://github.com/acme/widgets/issues/42",
             "assignee":{"login":"alice"},"labels":["bug"],"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-02T00:00:00Z"}
            """, captured);

        var tickets = provider.pullTicket(new PullTicketParams(List.of("42"), "acme/widgets", null, null, null));

        assertThat(tickets).hasSize(1);
        assertThat(tickets.get(0).title()).isEqualTo("Bug");
        assertThat(tickets.get(0).assignee()).isEqualTo("alice");
        assertThat(captured.get().method()).isEqualTo("GET");
        assertThat(captured.get().path()).isEqualTo("/repos/acme/widgets/issues/42");
        assertThat(captured.get().authHeader()).isEqualTo("token ghp_testtoken123");
    }

    @Test
    void pullTicketByProjectIdFiltersOutPullRequests() throws IOException {
        var captured = new AtomicReference<CapturedRequest>();
        var provider = startServerAndProvider("/repos/acme/widgets/issues", 200, """
            [
              {"number":1,"title":"Real issue","state":"open","html_url":"u1","created_at":"c","updated_at":"u"},
              {"number":2,"title":"A PR","state":"open","html_url":"u2","pull_request":{"url":"x"},"created_at":"c","updated_at":"u"}
            ]
            """, captured);

        var tickets = provider.pullTicket(new PullTicketParams(null, "acme/widgets", null, null, null));

        assertThat(tickets).hasSize(1);
        assertThat(tickets.get(0).title()).isEqualTo("Real issue");
        assertThat(captured.get().path()).contains("state=open", "sort=updated", "direction=desc", "per_page=25");
    }

    @Test
    void createIssuePostsExactBody() throws IOException {
        var captured = new AtomicReference<CapturedRequest>();
        var provider = startServerAndProvider("/repos/acme/widgets/issues", 201,
            """
            {"number":99,"html_url":"https://github.com/acme/widgets/issues/99"}
            """, captured);

        var result = provider.createIssue(new CreateIssueParams("New bug", "details", "acme/widgets", null,
            List.of("bug", "urgent"), "bob", null));

        assertThat(result.id()).isEqualTo("99");
        assertThat(captured.get().method()).isEqualTo("POST");
        assertThat(captured.get().body()).contains("\"title\":\"New bug\"", "\"body\":\"details\"",
            "\"bug\"", "\"urgent\"", "\"bob\"");
    }

    @Test
    void createPullRequestHappyPath() throws IOException {
        var captured = new AtomicReference<CapturedRequest>();
        var provider = startServerAndProvider("/repos/acme/widgets/pulls", 201, """
            {"number":7,"html_url":"https://github.com/acme/widgets/pull/7"}
            """, captured);

        var result = provider.createPullRequest(new CreatePrParams("Title", "feature", "main", null, "acme/widgets", null, null));

        assertThat(result.id()).isEqualTo("7");
        assertThat(result.alreadyExisted()).isNull();
        assertThat(captured.get().body()).contains("\"head\":\"feature\"", "\"base\":\"main\"");
    }

    @Test
    void createPullRequestFallsBackToExistingOpenPrOn422() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/repos/acme/widgets/pulls", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            byte[] body;
            int status;
            if ("POST".equals(exchange.getRequestMethod())) {
                status = 422;
                body = "{\"message\":\"already exists\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                status = 200;
                body = ("[{\"number\":55,\"html_url\":\"https://github.com/acme/widgets/pull/55\"}]")
                    .getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        String apiBase = "http://localhost:" + server.getAddress().getPort();
        var resolver = new GithubAccountsResolver(Map.of("GITHUB_TOKEN", "t")::get);
        var provider = new GithubProvider(resolver, HttpClient.newHttpClient(), apiBase, Duration.ofSeconds(5));

        var result = provider.createPullRequest(new CreatePrParams("Title", "feature", "main", null, "acme/widgets", null, null));

        assertThat(result.id()).isEqualTo("55");
        assertThat(result.alreadyExisted()).isTrue();
    }

    @Test
    void authHeaderUsesTokenSchemeForANonJwtShapedPat() {
        assertThat(GithubProvider.authHeader("ghp_abc123")).isEqualTo("token ghp_abc123");
    }

    @Test
    void authHeaderUsesBearerSchemeForAJwtShapedToken() {
        assertThat(GithubProvider.authHeader("header.payload.signature")).isEqualTo("bearer header.payload.signature");
    }
}
