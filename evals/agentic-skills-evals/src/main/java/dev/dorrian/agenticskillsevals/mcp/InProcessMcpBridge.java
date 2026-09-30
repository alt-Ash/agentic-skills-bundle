package dev.dorrian.agenticskillsevals.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * JSON-RPC 2.0 dispatcher (protocol version {@code 2024-11-05}, per the community Agent SDK docs)
 * for in-process MCP tool handlers - replaces evals/mocks/mcp-mock-server.ts's separate-subprocess
 * design. Handles {@code initialize}, {@code tools/list}, {@code tools/call}.
 *
 * <p><b>Wiring verified live in Phase 2</b> against a real {@code claude} subprocess. The outer
 * {@code control_request} envelope shape is:
 * {@code {"type":"control_request","request_id":"...","request":{"subtype":"mcp_message",
 * "server_name":"<name>","message":{jsonrpc,id,method,params}}}} - the JSON-RPC payload lives under
 * {@code request.message}, keyed alongside a {@code server_name} (so a single transport can host
 * several in-process servers, routed by name). This class's {@link #handle(JsonNode)} takes that
 * inner JSON-RPC request object directly; {@code ControlProtocolTransport} does the envelope
 * unwrapping/rewrapping. The server must be declared as an SDK-type entry in {@code --mcp-config}
 * (e.g. {@code {"mcpServers":{"<name>":{"type":"sdk","name":"<name>"}}}}) for the CLI to route
 * {@code mcp_message} requests here instead of spawning a real subprocess - see
 * {@code ClaudeSession.Builder#inProcessMcpServer}.
 */
public final class InProcessMcpBridge {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private record ToolDefinition(String name, String description, JsonNode inputSchema, Function<JsonNode, JsonNode> handler) {
    }

    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();
    private final String serverName;

    public InProcessMcpBridge(String serverName) {
        this.serverName = serverName;
    }

    public InProcessMcpBridge registerTool(String name, String description, JsonNode inputSchema, Function<JsonNode, JsonNode> handler) {
        tools.put(name, new ToolDefinition(name, description, inputSchema, handler));
        return this;
    }

    public String serverName() {
        return serverName;
    }

    /** Registered tool names, unqualified - callers prefix with {@code mcp__<serverName>__} for {@code --allowedTools}. */
    public java.util.Set<String> toolNames() {
        return java.util.Collections.unmodifiableSet(tools.keySet());
    }

    /**
     * Port of {@code mcpMockFromFixture}/{@code evals/mocks/mcp-mock-server.ts}'s fixture format:
     * {@code {"tools":[{"name","description","inputSchema","response"}]}}, where {@code response}
     * is either a static JSON value or the literal string {@code "{{dynamic}}"} (special-cased for
     * {@code create_issue}, synthesizing {@code {url,number,title,state}} from the call args -
     * ported faithfully from the original mock server's {@code buildToolResponse}).
     */
    public static InProcessMcpBridge fromFixture(String serverName, Path fixtureFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode fixture = mapper.readTree(Files.readString(fixtureFile));
        InProcessMcpBridge bridge = new InProcessMcpBridge(serverName);
        for (JsonNode toolNode : fixture.path("tools")) {
            String name = toolNode.path("name").asText("");
            String description = toolNode.path("description").asText("");
            JsonNode inputSchema = toolNode.path("inputSchema");
            JsonNode response = toolNode.path("response");
            boolean dynamic = response.isTextual() && "{{dynamic}}".equals(response.asText());
            bridge.registerTool(name, description, inputSchema, args -> {
                if (!dynamic) {
                    return response;
                }
                if ("create_issue".equals(name)) {
                    String repo = args.path("repo").asText("org/repo");
                    String title = args.path("title").asText("");
                    ObjectNode result = NODES.objectNode();
                    result.put("url", "https://github.com/" + repo + "/issues/1");
                    result.put("number", 1);
                    result.put("title", title);
                    result.put("state", "open");
                    return result;
                }
                return NODES.objectNode();
            });
        }
        return bridge;
    }

    /** @param request a JSON-RPC 2.0 request object: {@code {jsonrpc, id, method, params}}. */
    public JsonNode handle(JsonNode request) {
        String method = request.path("method").asText("");
        JsonNode id = request.path("id");
        return switch (method) {
            case "initialize" -> success(id, initializeResult());
            case "notifications/initialized" -> null; // no-op notification, no JSON-RPC response expected
            case "tools/list" -> success(id, toolsListResult());
            case "tools/call" -> handleToolsCall(id, request.path("params"));
            default -> error(id, -32601, "Method not found: " + method);
        };
    }

    private JsonNode initializeResult() {
        ObjectNode result = NODES.objectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = NODES.objectNode();
        capabilities.set("tools", NODES.objectNode());
        result.set("capabilities", capabilities);
        ObjectNode serverInfo = NODES.objectNode();
        serverInfo.put("name", serverName);
        serverInfo.put("version", "1.0.0");
        result.set("serverInfo", serverInfo);
        return result;
    }

    private JsonNode toolsListResult() {
        ArrayNode list = NODES.arrayNode();
        for (ToolDefinition tool : tools.values()) {
            ObjectNode node = NODES.objectNode();
            node.put("name", tool.name());
            node.put("description", tool.description());
            node.set("inputSchema", tool.inputSchema());
            list.add(node);
        }
        ObjectNode result = NODES.objectNode();
        result.set("tools", list);
        return result;
    }

    private JsonNode handleToolsCall(JsonNode id, JsonNode params) {
        String name = params.path("name").asText("");
        ToolDefinition tool = tools.get(name);
        if (tool == null) {
            return error(id, -32602, "Unknown tool: " + name);
        }
        JsonNode arguments = params.path("arguments");
        JsonNode toolResult;
        try {
            toolResult = tool.handler().apply(arguments);
        } catch (Exception e) {
            return error(id, -32000, "Tool handler failed: " + e.getMessage());
        }
        ObjectNode contentBlock = NODES.objectNode();
        contentBlock.put("type", "text");
        contentBlock.put("text", toolResult.toString());
        ArrayNode content = NODES.arrayNode();
        content.add(contentBlock);
        ObjectNode result = NODES.objectNode();
        result.set("content", content);
        return success(id, result);
    }

    private JsonNode success(JsonNode id, JsonNode result) {
        ObjectNode response = NODES.objectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    private JsonNode error(JsonNode id, int code, String message) {
        ObjectNode errorNode = NODES.objectNode();
        errorNode.put("code", code);
        errorNode.put("message", message);
        ObjectNode response = NODES.objectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("error", errorNode);
        return response;
    }
}
