package dev.dorrian.issuetickets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Smoke test: the stdio MCP server's context starts and exposes the issue-tickets tools. */
@SpringBootTest(properties = {
    "spring.main.web-application-type=none",
    "spring.ai.mcp.server.stdio=true"
})
class IssueTicketsApplicationContextTest {

    @Autowired
    private ToolCallbackProvider issueTicketsToolCallbacks;

    @Autowired
    private HttpTimeoutProperties timeouts;

    @Test
    void contextLoadsAndRegistersMcpTools() {
        String[] names = Arrays.stream(issueTicketsToolCallbacks.getToolCallbacks())
            .map(ToolCallback::getToolDefinition)
            .map(d -> d.name())
            .toArray(String[]::new);

        assertThat(names).contains("pull_ticket", "create_issue");
    }

    @Test
    void httpTimeoutDefaultsApplyWithoutPropertiesEntries() {
        assertThat(timeouts.connectTimeout()).isNotNull().isPositive();
        assertThat(timeouts.requestTimeout()).isNotNull().isPositive();
    }
}
