package dev.dorrian.agenticskillsevals.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dorrian.agenticskillsevals.protocol.ChatResult;
import dev.dorrian.agenticskillsevals.protocol.ClaudeSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-subprocess test proving {@link InProcessMcpBridge} is genuinely wired end-to-end through
 * the actual {@code ClaudeSession}/{@code ControlProtocolTransport} classes - not just the raw
 * protocol assumption validated by the standalone Node spike during Phase 2. Makes one real, small,
 * billed API call on {@code claude-haiku-4-5-20251001}.
 */
class InProcessMcpBridgeRealSubprocessTest {

    private static final String MODEL = "claude-haiku-4-5-20251001";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @Timeout(60)
    void inProcessMcpToolIsGenuinelyInvokedAndItsResponseReachesTheModel() throws Exception {
        AtomicBoolean toolInvoked = new AtomicBoolean(false);

        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = MAPPER.createObjectNode();
        ObjectNode cityProp = MAPPER.createObjectNode();
        cityProp.put("type", "string");
        properties.set("city", cityProp);
        schema.set("properties", properties);
        schema.putArray("required").add("city");

        InProcessMcpBridge bridge = new InProcessMcpBridge("weathermock")
                .registerTool("get_weather", "Get weather for a city", schema, args -> {
                    toolInvoked.set(true);
                    ObjectNode response = MAPPER.createObjectNode();
                    response.put("temp", 72);
                    response.put("city", args.path("city").asText(""));
                    return response;
                });

        try (ClaudeSession session = ClaudeSession.builder()
                .model(MODEL)
                .inProcessMcpServer(bridge)
                .build()) {
            ChatResult result = session.query(
                    "Call the get_weather tool for city \"Paris\" and tell me the temperature.");

            assertTrue(toolInvoked.get(), "the in-process MCP tool handler should have been invoked");
            assertTrue(result.text().contains("72"),
                    "expected the mocked temperature (72) to reach the model's final response: " + result.text());
        }
    }
}
