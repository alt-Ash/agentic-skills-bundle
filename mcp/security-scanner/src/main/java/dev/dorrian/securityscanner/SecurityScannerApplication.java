package dev.dorrian.securityscanner;

import dev.dorrian.securityscanner.tools.SecurityScannerTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Stdio MCP server entrypoint. Port of index.ts's {@code new McpServer(...) +
 * StdioServerTransport + connect} bootstrap — {@code spring-ai-starter-mcp-server} with
 * {@code spring.ai.mcp.server.stdio=true} (see application.properties) handles the transport
 * wiring; {@link SecurityScannerTools}'s {@code @Tool}-annotated methods become the 3
 * registered tools.
 */
@SpringBootApplication
public class SecurityScannerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SecurityScannerApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider securityScannerToolCallbacks(SecurityScannerTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
