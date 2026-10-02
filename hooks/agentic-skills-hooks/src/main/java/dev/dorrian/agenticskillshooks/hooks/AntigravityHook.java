package dev.dorrian.agenticskillshooks.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dorrian.agenticskillshooks.EventLog;
import dev.dorrian.agenticskillshooks.HookInput;
import dev.dorrian.agenticskillshooks.JsonSupport;
import dev.dorrian.usagestore.UsageEvent;

import java.util.List;
import java.util.Optional;

/**
 * Adapter for Antigravity CLI hooks ({@code java -jar hooks.jar agy <event> [--verify]}).
 *
 * <p>Antigravity differs from Claude Code in ways that need translating: payload keys are camelCase
 * ({@code conversationId}, {@code toolCall.name}), the event name is NOT in the payload (so each registered
 * command names it), tool names differ ({@code run_command}, ...), and every hook must answer with a JSON
 * object on stdout (blocking is a {@code decision}, not an exit code). This class converts the payload to the
 * shape our hooks already read, runs them, and renders their result in Antigravity's format. It never throws
 * and always exits 0: any failure answers with an empty object so the agent is never held up.
 *
 * <p>Events: {@code post-tool-use} and {@code stop} (analytics; {@code stop --verify} also runs the verify
 * gate and answers {@code {"decision":"continue"}} to keep the agent working) and {@code pre-invocation}
 * (context injection on a conversation's first call). There is deliberately no guard: Antigravity's
 * {@code PreToolUse} has no "no opinion" answer, so a guard would have to answer {@code allow} for everything
 * it does not block, which would silently bypass Antigravity's own permission prompts.
 */
public final class AntigravityHook {

    private static final String EMPTY = "{}";
    private static final String PROVIDER = "antigravity";

    private AntigravityHook() {
    }

    /** Returns the JSON to print on stdout. */
    public static String run(String event, boolean verify, String raw) {
        try {
            JsonNode payload = JsonSupport.MAPPER.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            if (payload == null || !payload.isObject()) return EMPTY;
            String cwd = firstWorkspace(payload);
            if (cwd != null) {
                // Hooks run with the hooks.json directory as their cwd; identity and git look at user.dir.
                System.setProperty("user.dir", cwd);
            }
            return switch (event) {
                case "post-tool-use" -> postToolUse(payload, cwd);
                case "stop" -> stop(payload, cwd, verify);
                case "pre-invocation" -> preInvocation(payload, cwd);
                default -> EMPTY;
            };
        } catch (Throwable t) {
            return EMPTY;
        }
    }

    // ─── events ─────────────────────────────────────────────────────────────

    private static String postToolUse(JsonNode p, String cwd) {
        JsonNode call = p.get("toolCall");
        if (call == null || !call.isObject()) return EMPTY; // PostToolUse sometimes carries a null toolCall
        HookInput early = HookInput.parse(base(p, cwd, "PostToolUse").toString());
        ensureSession(early, cwd);

        ObjectNode n = base(p, cwd, "PostToolUse");
        n.put("tool_name", toolName(text(call, "name")));
        n.set("tool_input", toolInput(call.get("args")));
        if (p.hasNonNull("stepIdx")) n.put("tool_use_id", "step-" + p.get("stepIdx").asText());
        String error = text(p, "error");
        HookInput input;
        if (error != null && !error.isBlank()) {
            n.put("error", error);
            input = HookInput.parse(n.toString());
            PostToolUseFailureHook.run(input);
        } else {
            input = HookInput.parse(n.toString());
            PostToolUseHook.run(input);
        }
        return EMPTY;
    }

    private static String stop(JsonNode p, String cwd, boolean verify) {
        ObjectNode n = base(p, cwd, "Stop");
        String finalOutput = text(p, "finalModelOutput");
        if (finalOutput != null) n.put("last_assistant_message", finalOutput); // only its length is recorded
        HookInput early = HookInput.parse(n.toString());
        ensureSession(early, cwd);

        // Claude Code tells a Stop hook when it is being asked again because of an earlier block. Antigravity
        // has only an attempt counter, so also derive it from our own history: that is what bounds the verify
        // loop (VerifyHook gives up after N consecutive blocks), and it must hold even if the counter never rises.
        String session = early.sessionId();
        boolean continuing = p.path("executionNum").asInt(1) > 1
            || (session != null && VerifyHook.trailingBlocks(EventLog.sessionEvents(session)) > 0);
        n.put("stop_hook_active", continuing);
        HookInput input = HookInput.parse(n.toString());

        StopHook.run(input);
        if (!verify) return EMPTY;
        Optional<String> reason = VerifyHook.evaluate(input);
        if (reason.isPresent()) {
            ObjectNode out = JsonSupport.MAPPER.createObjectNode();
            out.put("decision", "continue"); // "continue" blocks the stop; anything else lets it end
            out.put("reason", reason.get());
            return out.toString();
        }
        return "{\"decision\":\"stop\"}";
    }

    private static String preInvocation(JsonNode p, String cwd) {
        ObjectNode n = base(p, cwd, "PreInvocation");
        HookInput input = HookInput.parse(n.toString());
        // Once per conversation: the first time we see it. (An invocation happens before every model call.)
        boolean first = input.sessionId() != null && EventLog.session(input.sessionId()) == null;
        ensureSession(input, cwd);
        if (!first || p.path("invocationNum").asInt(0) != 0) return EMPTY;

        n.put("source", "startup");
        Optional<String> context = ContextHook.contextFor(HookInput.parse(n.toString()));
        if (context.isEmpty() || context.get().isBlank()) return EMPTY;
        ObjectNode step = JsonSupport.MAPPER.createObjectNode();
        step.put("ephemeralMessage", context.get());
        ArrayNode steps = JsonSupport.MAPPER.createArrayNode().add(step);
        ObjectNode out = JsonSupport.MAPPER.createObjectNode();
        out.set("injectSteps", steps);
        return out.toString();
    }

    // ─── translation ────────────────────────────────────────────────────────

    /** The common fields of an Antigravity payload, under the names our hooks read. */
    private static ObjectNode base(JsonNode p, String cwd, String event) {
        ObjectNode n = JsonSupport.MAPPER.createObjectNode();
        n.put("hook_event_name", event);
        String conversation = text(p, "conversationId");
        if (conversation == null || conversation.isBlank()) conversation = System.getenv("ANTIGRAVITY_CONVERSATION_ID");
        if (conversation != null) n.put("session_id", conversation);
        if (cwd != null) n.put("cwd", cwd);
        String transcript = text(p, "transcriptPath");
        if (transcript != null) n.put("transcript_path", transcript);
        String model = text(p, "modelName");
        if (model != null && !model.isBlank()) n.put("model", model);
        n.put("agentic_skills_provider", PROVIDER);
        return n;
    }

    /** Antigravity has no session-start event, so the first event we see for a conversation starts it. */
    private static void ensureSession(HookInput input, String cwd) {
        String id = input.sessionId();
        if (id == null || EventLog.session(id) != null) return;
        ObjectNode s = JsonSupport.MAPPER.createObjectNode();
        s.put("hook_event_name", "SessionStart");
        s.put("session_id", id);
        s.put("source", "startup");
        s.put("agentic_skills_provider", PROVIDER);
        if (cwd != null) s.put("cwd", cwd);
        SessionHook.run(HookInput.parse(s.toString()));
    }

    static String toolName(String antigravityName) {
        if (antigravityName == null) return null;
        return switch (antigravityName) {
            case "run_command" -> "Bash";
            case "view_file" -> "Read";
            case "write_to_file" -> "Write";
            case "replace_file_content", "multi_replace_file_content" -> "Edit";
            default -> antigravityName;
        };
    }

    /** Only the two fields our hooks use: the shell command and a file path, never file contents or edit text. */
    static ObjectNode toolInput(JsonNode args) {
        ObjectNode out = JsonSupport.MAPPER.createObjectNode();
        if (args == null || !args.isObject()) return out;
        String command = text(args, "CommandLine");
        if (command != null) out.put("command", command);
        for (String key : List.of("TargetFile", "AbsolutePath", "FilePath", "Path")) {
            String path = text(args, key);
            if (path != null && !path.isBlank()) {
                out.put("file_path", path);
                break;
            }
        }
        return out;
    }

    private static String firstWorkspace(JsonNode p) {
        JsonNode paths = p.get("workspacePaths");
        if (paths != null && paths.isArray() && paths.size() > 0 && paths.get(0).isTextual()) {
            String first = paths.get(0).asText();
            return first.isBlank() ? null : first;
        }
        return null;
    }

    private static String text(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    /** Test seam: the events an Antigravity run would have produced. */
    static List<UsageEvent> eventsFor(String sessionId) {
        return EventLog.sessionEvents(sessionId);
    }
}
