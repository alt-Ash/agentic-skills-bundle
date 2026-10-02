package dev.dorrian.securityscanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Smoke test: the stdio MCP server context starts and the three scanner tools are registered.
 * Guards against dependency-pairing breakage (Spring Boot vs. Spring AI) that unit tests miss.
 */
@SpringBootTest(properties = {
    "spring.main.web-application-type=none",
    "spring.ai.mcp.server.stdio=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SecurityScannerContextTest {

    @Autowired
    private ToolCallbackProvider securityScannerToolCallbacks;

    @Test
    void contextLoadsAndRegistersTheScannerTools() {
        var names = Arrays.stream(securityScannerToolCallbacks.getToolCallbacks())
            .map(cb -> cb.getToolDefinition().name())
            .toList();

        assertThat(names).containsExactlyInAnyOrder("scan_passive", "scan_active", "get_scan_report");
    }
}
