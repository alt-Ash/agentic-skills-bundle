package dev.dorrian.agenticskillsevals.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InProcessMcpBridgeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private JsonNode rpcRequest(String method, JsonNode params, int id) {
        ObjectNode request = NODES.objectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }
        return request;
    }

    @Test
    void initializeReturnsProtocolVersionAndServerInfo() {
        InProcessMcpBridge bridge = new InProcessMcpBridge("test-server");
        JsonNode response = bridge.handle(rpcRequest("initialize", null, 1));

        assertEquals("2.0", response.path("jsonrpc").asText());
        assertEquals(1, response.path("id").asInt());
        assertEquals("2024-11-05", response.path("result").path("protocolVersion").asText());
        assertEquals("test-server", response.path("result").path("serverInfo").path("name").asText());
        assertTrue(response.path("result").path("capabilities").has("tools"));
    }

    @Test
    void toolsListReturnsRegisteredTools() {
        InProcessMcpBridge bridge = new InProcessMcpBridge("test-server")
                .registerTool("create_issue", "Creates an issue", NODES.objectNode(), args -> NODES.objectNode());

        JsonNode response = bridge.handle(rpcRequest("tools/list", null, 2));

        JsonNode tools = response.path("result").path("tools");
        assertTrue(tools.isArray());
        assertEquals(1, tools.size());
        assertEquals("create_issue", tools.get(0).path("name").asText());
        assertEquals("Creates an issue", tools.get(0).path("description").asText());
    }

    @Test
    void toolsCallInvokesHandlerAndWrapsResultAsTextContent() {
        ObjectNode staticResponse = NODES.objectNode();
        staticResponse.put("url", "https://example.com/issues/1");
        staticResponse.put("number", 1);

        InProcessMcpBridge bridge = new InProcessMcpBridge("test-server")
                .registerTool("create_issue", "Creates an issue", NODES.objectNode(), args -> staticResponse);

        ObjectNode params = NODES.objectNode();
        params.put("name", "create_issue");
        params.set("arguments", NODES.objectNode());

        JsonNode response = bridge.handle(rpcRequest("tools/call", params, 3));

        JsonNode content = response.path("result").path("content");
        assertTrue(content.isArray());
        assertEquals("text", content.get(0).path("type").asText());
        JsonNode parsedBack = assertDoesNotThrow(() -> MAPPER.readTree(content.get(0).path("text").asText()));
        assertEquals("https://example.com/issues/1", parsedBack.path("url").asText());
        assertEquals(1, parsedBack.path("number").asInt());
    }

    @Test
    void toolsCallOnUnknownToolReturnsJsonRpcError() {
        InProcessMcpBridge bridge = new InProcessMcpBridge("test-server");

        ObjectNode params = NODES.objectNode();
        params.put("name", "does_not_exist");
        params.set("arguments", NODES.objectNode());

        JsonNode response = bridge.handle(rpcRequest("tools/call", params, 4));

        assertTrue(response.has("error"));
        assertEquals(-32602, response.path("error").path("code").asInt());
    }

    @Test
    void unknownMethodReturnsMethodNotFoundError() {
        InProcessMcpBridge bridge = new InProcessMcpBridge("test-server");

        JsonNode response = bridge.handle(rpcRequest("bogus/method", null, 5));

        assertTrue(response.has("error"));
        assertEquals(-32601, response.path("error").path("code").asInt());
    }

    @Test
    void handlerExceptionIsCaughtAndReturnedAsJsonRpcError() {
        InProcessMcpBridge bridge = new InProcessMcpBridge("test-server")
                .registerTool("broken", "always throws", NODES.objectNode(), args -> {
                    throw new RuntimeException("boom");
                });

        ObjectNode params = NODES.objectNode();
        params.put("name", "broken");
        params.set("arguments", NODES.objectNode());

        JsonNode response = bridge.handle(rpcRequest("tools/call", params, 6));

        assertTrue(response.has("error"));
        assertTrue(response.path("error").path("message").asText().contains("boom"));
    }
}
