package dev.dorrian.agenticskillsevals.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Low-level client for the {@code claude} CLI's bidirectional control protocol
 * ({@code --input-format stream-json --output-format stream-json --verbose}). This protocol is
 * NOT publicly documented by Anthropic - the exact frame shapes here were verified via live
 * subprocess experimentation (see the {@code claude_cli_control_protocol} memory entry) and may
 * drift across {@code claude} CLI releases. Isolated to this one class deliberately, per the
 * migration plan's risk mitigation: if the protocol changes, this is the only place that breaks.
 *
 * <p>Handles: process lifecycle, the {@code initialize} handshake (declaring PreToolUse/
 * PostToolUse hook interest), NDJSON frame reading on a background thread, routing
 * {@code control_request}/{@code control_response} pairs by {@code request_id}, and dispatching
 * unsolicited {@code hook_callback}/{@code mcp_message} control_requests to registered handlers.
 */
public final class ControlProtocolTransport implements AutoCloseable {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final long HANDSHAKE_TIMEOUT_SECONDS = 15;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Process process;
    private final OutputStream stdin;
    private final Thread readerThread;
    private final Map<String, CompletableFuture<JsonNode>> pendingControlResponses = new ConcurrentHashMap<>();
    private final Map<String, Function<JsonNode, HookDecision>> hookCallbacks = new ConcurrentHashMap<>();
    /** Keyed by MCP {@code server_name} (as declared in {@code --mcp-config}'s {@code type:"sdk"} entries). */
    private final Map<String, Function<JsonNode, JsonNode>> mcpMessageHandlers = new ConcurrentHashMap<>();
    private final List<JsonNode> messageLog = new ArrayList<>();
    private final Object messageLogLock = new Object();
    private volatile Throwable readerFailure;
    private volatile boolean closed = false;

    /**
     * @param claudeArgs        extra CLI args (e.g. {@code --model}, {@code --system-prompt},
     *                          {@code --allowedTools}, {@code --mcp-config}) - the
     *                          {@code -p --input-format stream-json --output-format stream-json
     *                          --verbose} prefix is added automatically.
     * @param preToolUseHooks   matcher (nullable = match-all) -> handler, registered at handshake time.
     * @param mcpMessageHandlers server_name -> handler for any {@code type:"sdk"} servers declared in
     *                          {@code --mcp-config}. Must be populated BEFORE the reader thread starts,
     *                          not after construction - verified live that the CLI eagerly initializes
     *                          declared SDK-type MCP servers immediately (before the model even sees the
     *                          user's prompt), so registering handlers post-construction races the reader
     *                          thread and can silently drop the MCP server's own initialize/tools-list
     *                          handshake.
     */
    public ControlProtocolTransport(
            List<String> claudeArgs,
            Map<String, Function<JsonNode, HookDecision>> preToolUseHooks,
            Map<String, Function<JsonNode, HookDecision>> postToolUseHooks,
            Map<String, Function<JsonNode, JsonNode>> mcpMessageHandlers
    ) throws IOException {
        if (mcpMessageHandlers != null) {
            this.mcpMessageHandlers.putAll(mcpMessageHandlers);
        }

        List<String> command = new ArrayList<>();
        command.add("claude");
        command.add("-p");
        command.add("--input-format");
        command.add("stream-json");
        command.add("--output-format");
        command.add("stream-json");
        command.add("--verbose");
        command.addAll(claudeArgs);

        ProcessBuilder builder = new ProcessBuilder(command);
        this.process = builder.start();
        this.stdin = process.getOutputStream();

        this.readerThread = new Thread(this::readLoop, "control-protocol-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();

        handshake(preToolUseHooks, postToolUseHooks);
    }

    private void handshake(
            Map<String, Function<JsonNode, HookDecision>> preToolUseHooks,
            Map<String, Function<JsonNode, HookDecision>> postToolUseHooks
    ) throws IOException {
        ObjectNode hooksNode = NODES.objectNode();
        addHookMatchers(hooksNode, "PreToolUse", preToolUseHooks);
        addHookMatchers(hooksNode, "PostToolUse", postToolUseHooks);

        ObjectNode request = NODES.objectNode();
        request.put("subtype", "initialize");
        if (hooksNode.size() > 0) {
            request.set("hooks", hooksNode);
        }

        try {
            sendControlRequest(request).get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            close();
            throw new IOException(
                    "claude CLI control-protocol initialize handshake failed or timed out after "
                            + HANDSHAKE_TIMEOUT_SECONDS + "s. This protocol is undocumented by Anthropic and "
                            + "may have changed since it was last verified - see the claude_cli_control_protocol "
                            + "memory entry.", e);
        }
    }

    private void addHookMatchers(
            ObjectNode hooksNode, String eventName, Map<String, Function<JsonNode, HookDecision>> handlers
    ) {
        if (handlers == null || handlers.isEmpty()) {
            return;
        }
        ArrayNode matchers = NODES.arrayNode();
        for (Map.Entry<String, Function<JsonNode, HookDecision>> entry : handlers.entrySet()) {
            String matcher = entry.getKey();
            String callbackId = UUID.randomUUID().toString();
            hookCallbacks.put(callbackId, entry.getValue());

            ObjectNode matcherNode = NODES.objectNode();
            if (matcher != null) {
                matcherNode.put("matcher", matcher);
            } else {
                matcherNode.putNull("matcher");
            }
            ArrayNode ids = NODES.arrayNode();
            ids.add(callbackId);
            matcherNode.set("hookCallbackIds", ids);
            matchers.add(matcherNode);
        }
        hooksNode.set(eventName, matchers);
    }

    /** Sends a {@code control_request} frame and returns a future completed with the matching {@code control_response}. */
    public CompletableFuture<JsonNode> sendControlRequest(ObjectNode request) throws IOException {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingControlResponses.put(requestId, future);

        ObjectNode frame = NODES.objectNode();
        frame.put("type", "control_request");
        frame.put("request_id", requestId);
        frame.set("request", request);
        writeFrame(frame);
        return future;
    }

    /** Sends a prompt-stream {@code user} message frame (see claude_cli_control_protocol memory for exact shape). */
    public void sendUserMessage(String content) throws IOException {
        ObjectNode message = NODES.objectNode();
        message.put("role", "user");
        message.put("content", content);

        ObjectNode frame = NODES.objectNode();
        frame.put("type", "user");
        frame.set("message", message);
        frame.putNull("parent_tool_use_id");
        frame.put("session_id", "default");
        writeFrame(frame);
    }

    private synchronized void writeFrame(JsonNode frame) throws IOException {
        if (closed) {
            throw new IOException("Transport is closed");
        }
        byte[] bytes = (mapper.writeValueAsString(frame) + "\n").getBytes(StandardCharsets.UTF_8);
        stdin.write(bytes);
        stdin.flush();
    }

    /**
     * Blocks until a message of one of the given top-level {@code type} values arrives (e.g.
     * {@code "assistant"}, {@code "result"}), invoking {@code onMessage} for every message seen
     * along the way (including ones not in {@code terminalTypes}). Returns the terminal message.
     */
    public JsonNode pumpUntil(java.util.Set<String> terminalTypes, java.util.function.Consumer<JsonNode> onMessage, long timeoutSeconds)
            throws IOException, TimeoutException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        int index = 0;
        while (true) {
            JsonNode next;
            synchronized (messageLogLock) {
                if (index < messageLog.size()) {
                    next = messageLog.get(index);
                    index++;
                } else {
                    next = null;
                }
            }
            if (next != null) {
                onMessage.accept(next);
                String type = next.path("type").asText("");
                if (terminalTypes.contains(type)) {
                    return next;
                }
                continue;
            }
            if (readerFailure != null) {
                throw new IOException("Reader thread failed", readerFailure);
            }
            if (!process.isAlive()) {
                throw new IOException("claude process exited unexpectedly before a terminal message arrived");
            }
            if (System.nanoTime() > deadline) {
                throw new TimeoutException("Timed out waiting for one of " + terminalTypes);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for response", e);
            }
        }
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = mapper.readTree(line);
                } catch (Exception e) {
                    continue;
                }
                dispatch(node);
            }
        } catch (IOException e) {
            readerFailure = e;
        }
    }

    private void dispatch(JsonNode node) {
        String type = node.path("type").asText("");
        if ("control_response".equals(type)) {
            JsonNode response = node.path("response");
            String requestId = response.path("request_id").asText(null);
            if (requestId != null) {
                CompletableFuture<JsonNode> future = pendingControlResponses.remove(requestId);
                if (future != null) {
                    future.complete(response);
                }
            }
            return;
        }
        if ("control_request".equals(type)) {
            handleIncomingControlRequest(node);
            return;
        }
        synchronized (messageLogLock) {
            messageLog.add(node);
        }
    }

    private void handleIncomingControlRequest(JsonNode node) {
        String requestId = node.path("request_id").asText(null);
        JsonNode request = node.path("request");
        String subtype = request.path("subtype").asText("");
        JsonNode responsePayload;
        try {
            responsePayload = switch (subtype) {
                case "hook_callback" -> handleHookCallback(request);
                case "mcp_message" -> handleMcpMessage(request);
                default -> NODES.objectNode();
            };
        } catch (Exception e) {
            responsePayload = NODES.objectNode();
        }

        ObjectNode responseObj = NODES.objectNode();
        responseObj.put("subtype", "success");
        responseObj.put("request_id", requestId);
        responseObj.set("response", responsePayload);

        ObjectNode reply = NODES.objectNode();
        reply.put("type", "control_response");
        reply.set("response", responseObj);
        try {
            writeFrame(reply);
        } catch (IOException e) {
            readerFailure = e;
        }
    }

    private JsonNode handleHookCallback(JsonNode request) {
        String callbackId = request.path("callback_id").asText(null);
        Function<JsonNode, HookDecision> handler = callbackId != null ? hookCallbacks.get(callbackId) : null;
        if (handler == null) {
            return NODES.objectNode();
        }
        JsonNode input = request.path("input");
        HookDecision decision = handler.apply(input);
        return decision.toResponsePayload();
    }

    /**
     * Unwraps {@code request.message} (the actual JSON-RPC 2.0 payload), dispatches to the handler
     * registered for {@code request.server_name}, and re-wraps the JSON-RPC response. Verified live:
     * the CLI accepted a reply carrying the JSON-RPC response under all three of {@code mcp_response}/
     * {@code message}/{@code response} keys simultaneously - which single key is authoritative wasn't
     * isolated (would need another live spike to disambiguate, not worth the spend), so all three are
     * populated redundantly. Extra JSON fields are harmless; this is deliberate, not sloppy.
     */
    private JsonNode handleMcpMessage(JsonNode request) {
        String serverName = request.path("server_name").asText("");
        Function<JsonNode, JsonNode> handler = mcpMessageHandlers.get(serverName);
        JsonNode rpcRequest = request.path("message");
        if (handler == null) {
            return NODES.objectNode();
        }
        JsonNode rpcResponse = handler.apply(rpcRequest);
        if (rpcResponse == null) {
            return NODES.objectNode(); // notification (e.g. notifications/initialized) - no reply content expected
        }
        ObjectNode wrapper = NODES.objectNode();
        wrapper.set("mcp_response", rpcResponse);
        wrapper.set("message", rpcResponse);
        wrapper.set("response", rpcResponse);
        return wrapper;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            stdin.close();
        } catch (IOException ignored) {
            // best-effort
        }
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        readerThread.interrupt();
    }
}
