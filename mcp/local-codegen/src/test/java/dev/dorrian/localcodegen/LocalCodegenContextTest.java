package dev.dorrian.localcodegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/** Smoke test: the stdio MCP server context starts and the three tools are registered. */
@SpringBootTest(properties = {
    "spring.main.web-application-type=none",
    "spring.ai.mcp.server.stdio=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LocalCodegenContextTest {

    @Autowired
    private ToolCallbackProvider localCodegenToolCallbacks;

    @Test
    void contextLoadsAndRegistersTheTools() {
        var names = Arrays.stream(localCodegenToolCallbacks.getToolCallbacks())
            .map(cb -> cb.getToolDefinition().name())
            .toList();

        assertThat(names).containsExactlyInAnyOrder("health", "list_models", "generate_slice");
    }
}
