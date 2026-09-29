package dev.dorrian.issuetickets;

import dev.dorrian.issuetickets.tools.IssueTicketsTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Stdio MCP server entrypoint. Port of index.ts's {@code new McpServer(...) +
 * StdioServerTransport + connect} bootstrap.
 */
@SpringBootApplication
public class IssueTicketsApplication {

    public static void main(String[] args) {
        SpringApplication.run(IssueTicketsApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider issueTicketsToolCallbacks(IssueTicketsTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
