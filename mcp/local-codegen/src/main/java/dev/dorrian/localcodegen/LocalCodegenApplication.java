package dev.dorrian.localcodegen;

import dev.dorrian.localcodegen.backend.Env;
import dev.dorrian.localcodegen.tools.LocalCodegenTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Stdio MCP server entrypoint. Java/Spring port of the Python local-codegen-mcp server:
 * {@link LocalCodegenTools}'s {@code @Tool} methods become the three registered tools.
 */
@SpringBootApplication
public class LocalCodegenApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocalCodegenApplication.class, args);
    }

    @Bean
    public Env env() {
        return System::getenv;
    }

    @Bean
    public ToolCallbackProvider localCodegenToolCallbacks(LocalCodegenTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
