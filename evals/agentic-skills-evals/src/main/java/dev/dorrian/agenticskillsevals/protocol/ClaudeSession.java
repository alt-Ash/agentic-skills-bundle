package dev.dorrian.agenticskillsevals.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dorrian.agenticskillsevals.mcp.InProcessMcpBridge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Reusable, ergonomic wrapper over {@link ControlProtocolTransport} - the component the plan
 * calls out as usable both for offline eval scenarios (this module) and, later, as a live guard
 * wired into a real session (not part of this build). Query-only use (no hooks registered) is the
 * degenerate case of the same class - hooks are entirely optional.
 */
public final class ClaudeSession implements AutoCloseable {

    private static final long DEFAULT_TIMEOUT_SECONDS = 180; // matches the TS harness's vitest.config.ts testTimeout

    private final ControlProtocolTransport transport;

    private ClaudeSession(ControlProtocolTransport transport) {
        this.transport = transport;
    }

    public static Builder builder() {
        return new Builder();
    }

    public ChatResult query(String prompt) throws IOException, TimeoutException {
        transport.sendUserMessage(prompt);

        StringBuilder textBuilder = new StringBuilder();
        List<ToolCallRecord> toolCalls = new ArrayList<>();
        Map<String, Integer> toolCallCounts = new LinkedHashMap<>();
        List<JsonNode> transcript = new ArrayList<>();

        JsonNode result = transport.pumpUntil(Set.of("result"), message -> {
            transcript.add(message);
            if (!"assistant".equals(message.path("type").asText(""))) {
                return;
            }
            JsonNode content = message.path("message").path("content");
            if (!content.isArray()) {
                return;
            }
            for (JsonNode block : content) {
                String blockType = block.path("type").asText("");
                if ("text".equals(blockType)) {
                    textBuilder.append(block.path("text").asText(""));
                } else if ("tool_use".equals(blockType)) {
                    String toolName = block.path("name").asText("");
                    toolCalls.add(new ToolCallRecord(toolName, block.path("input"), false, null));
                    toolCallCounts.merge(toolName, 1, Integer::sum);
                }
            }
        }, DEFAULT_TIMEOUT_SECONDS);

        // Use the full accumulated text across every assistant message in the session, not the
        // terminal "result" event's own `result` field - that's just the CLI's last-message text,
        // which silently drops earlier-phase content in any multi-turn/multi-phase response (found
        // via a real issue-architect scenario failure: its agent_contract: block, produced in an
        // earlier phase, never made it into the captured response because this line preferred the
        // final phase's short summary instead).
        String finalText = textBuilder.toString();
        JsonNode usage = result.path("usage");

        return new ChatResult(
                finalText,
                toolCalls,
                toolCallCounts,
                usage.path("input_tokens").asLong(0),
                usage.path("output_tokens").asLong(0),
                usage.path("cache_read_input_tokens").asLong(0),
                usage.path("cache_creation_input_tokens").asLong(0),
                result.path("total_cost_usd").asDouble(0),
                result.path("session_id").asText(null),
                transcript
        );
    }

    public boolean isAlive() {
        return transport.isAlive();
    }

    @Override
    public void close() {
        transport.close();
    }

    public static final class Builder {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private String model;
        private String systemPrompt;
        private final List<String> allowedTools = new ArrayList<>();
        private String mcpConfigPath;
        private boolean strictMcpConfig;
        private final Map<String, Function<JsonNode, HookDecision>> preToolUseHooks = new LinkedHashMap<>();
        private final Map<String, Function<JsonNode, HookDecision>> postToolUseHooks = new LinkedHashMap<>();
        private final List<InProcessMcpBridge> inProcessMcpServers = new ArrayList<>();

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder allowedTools(List<String> tools) {
            this.allowedTools.addAll(tools);
            return this;
        }

        /** @param mcpConfigPath path to a JSON file in claude's {@code --mcp-config} format. */
        public Builder mcpConfig(String mcpConfigPath, boolean strict) {
            this.mcpConfigPath = mcpConfigPath;
            this.strictMcpConfig = strict;
            return this;
        }

        /**
         * Registers an in-process (SDK-type) MCP server, verified live in Phase 2: declares
         * {@code {"type":"sdk","name":"<serverName>"}} in an inline {@code --mcp-config} JSON string
         * (merged across multiple calls to this method), auto-allowlists every tool the bridge
         * exposes as {@code mcp__<serverName>__<toolName>} (the CLI's confirmed MCP tool-naming
         * convention), and wires {@code request.message} JSON-RPC dispatch to the bridge once the
         * transport exists. Replaces {@code evals/mocks/mcp-mock-server.ts}'s separate-subprocess design.
         */
        public Builder inProcessMcpServer(InProcessMcpBridge bridge) {
            inProcessMcpServers.add(bridge);
            for (String toolName : bridge.toolNames()) {
                allowedTools.add("mcp__" + bridge.serverName() + "__" + toolName);
            }
            return this;
        }

        /**
         * @param matcher tool-name matcher, or {@code null} to match every tool.
         * @param handler receives (toolName, toolInput); return {@link HookDecision#deny} to block.
         */
        public Builder registerPreToolUseHook(String matcher, BiFunction<String, JsonNode, HookDecision> handler) {
            preToolUseHooks.put(matcher, wrap(handler));
            return this;
        }

        public Builder registerPostToolUseHook(String matcher, BiFunction<String, JsonNode, HookDecision> handler) {
            postToolUseHooks.put(matcher, wrap(handler));
            return this;
        }

        private static Function<JsonNode, HookDecision> wrap(BiFunction<String, JsonNode, HookDecision> handler) {
            return raw -> handler.apply(raw.path("tool_name").asText(""), raw.path("tool_input"));
        }

        public ClaudeSession build() throws IOException {
            List<String> args = new ArrayList<>();
            if (model != null) {
                args.add("--model");
                args.add(model);
            }
            if (systemPrompt != null) {
                args.add("--system-prompt");
                args.add(systemPrompt);
            }
            if (!allowedTools.isEmpty()) {
                args.add("--allowedTools");
                args.addAll(allowedTools);
            }
            boolean usingInProcessMcp = !inProcessMcpServers.isEmpty();
            if (usingInProcessMcp) {
                ObjectNode mcpServersNode = MAPPER.getNodeFactory().objectNode();
                for (InProcessMcpBridge bridge : inProcessMcpServers) {
                    ObjectNode entry = MAPPER.getNodeFactory().objectNode();
                    entry.put("type", "sdk");
                    entry.put("name", bridge.serverName());
                    mcpServersNode.set(bridge.serverName(), entry);
                }
                ObjectNode configNode = MAPPER.getNodeFactory().objectNode();
                configNode.set("mcpServers", mcpServersNode);
                args.add("--mcp-config");
                args.add(configNode.toString());
                args.add("--strict-mcp-config");
            } else if (mcpConfigPath != null) {
                args.add("--mcp-config");
                args.add(mcpConfigPath);
                if (strictMcpConfig) {
                    args.add("--strict-mcp-config");
                }
            }
            Map<String, Function<JsonNode, JsonNode>> mcpHandlers = new LinkedHashMap<>();
            for (InProcessMcpBridge bridge : inProcessMcpServers) {
                mcpHandlers.put(bridge.serverName(), bridge::handle);
            }
            ControlProtocolTransport transport =
                    new ControlProtocolTransport(args, preToolUseHooks, postToolUseHooks, mcpHandlers);
            return new ClaudeSession(transport);
        }
    }
}
