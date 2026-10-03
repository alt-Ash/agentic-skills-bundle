package dev.dorrian.localcodegen.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** OpenAI-compatible backends with ordered fallback. Port of backend.py. */
@Service
public class BackendClient {

    public static final List<Backend> DEFAULT_BACKENDS = List.of(
        new Backend("llama", "http://127.0.0.1:8090/v1", "local"),
        new Backend("ollama", "http://127.0.0.1:11434/v1", "qwen2.5:14b"));

    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    private final Env env;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public BackendClient(Env env) {
        this.env = env;
    }

    /** LOCAL_CODEGEN_BACKENDS is a JSON list of {name, base_url, model}, tried in order. */
    public List<Backend> loadBackends() {
        String raw = env.get("LOCAL_CODEGEN_BACKENDS");
        if (raw == null || raw.isEmpty()) {
            return DEFAULT_BACKENDS;
        }
        try {
            JsonNode root = mapper.readTree(raw);
            if (root == null || !root.isArray()) {
                throw new IllegalArgumentException("LOCAL_CODEGEN_BACKENDS must be a JSON list");
            }
            List<Backend> out = new ArrayList<>();
            for (JsonNode item : root) {
                out.add(new Backend(field(item, "name"), field(item, "base_url"), field(item, "model")));
            }
            return out;
        } catch (IOException e) {
            throw new IllegalArgumentException("LOCAL_CODEGEN_BACKENDS is not valid JSON: " + e.getMessage(), e);
        }
    }

    private static String field(JsonNode item, String name) {
        JsonNode v = item.get(name);
        if (v == null || !v.isTextual()) {
            throw new IllegalArgumentException("LOCAL_CODEGEN_BACKENDS entry needs a string '" + name + "'");
        }
        return v.asText();
    }

    private HttpRequest.Builder request(String url, Duration timeout) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("Content-Type", "application/json");
        String key = env.get("LOCAL_CODEGEN_API_KEY");
        if (key != null && !key.isEmpty()) {
            b.header("Authorization", "Bearer " + key);
        }
        return b;
    }

    private JsonNode send(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode());
        }
        return mapper.readTree(resp.body());
    }

    static List<String> modelIds(JsonNode payload) {
        List<String> ids = new ArrayList<>();
        // OpenAI shape is {"data":[{"id":..}]}; llama.cpp also returns {"models":[{"model":..}]}.
        JsonNode data = payload.get("data");
        if (data != null && data.isArray() && !data.isEmpty()) {
            for (JsonNode m : data) {
                ids.add(m.path("id").asText());
            }
            return ids;
        }
        JsonNode models = payload.get("models");
        if (models != null && models.isArray()) {
            for (JsonNode m : models) {
                JsonNode v = m.hasNonNull("model") && !m.get("model").asText().isEmpty() ? m.get("model") : m.get("name");
                ids.add(v == null || v.isNull() ? null : v.asText());
            }
        }
        return ids;
    }

    /** Probe a backend's /models endpoint; never throws. */
    public Map<String, Object> probe(Backend backend) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", backend.name());
        out.put("base_url", backend.baseUrl());
        try {
            JsonNode payload = send(request(backend.baseUrl() + "/models", PROBE_TIMEOUT).GET().build());
            List<String> models = modelIds(payload);
            out.put("up", true);
            out.put("configured_model", backend.model());
            out.put("models", models);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return down(out, backend, "interrupted");
        } catch (IOException | RuntimeException e) {
            return down(out, backend, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
        return out;
    }

    private static Map<String, Object> down(Map<String, Object> out, Backend backend, String error) {
        out.put("up", false);
        out.put("configured_model", backend.model());
        out.put("error", error);
        return out;
    }

    /** First backend (in order) whose probe is up, or null. */
    public Backend firstHealthy(List<Backend> backends) {
        for (Backend b : backends) {
            if (Boolean.TRUE.equals(probe(b).get("up"))) {
                return b;
            }
        }
        return null;
    }

    /** Result of one chat call: parsed content plus the raw response. */
    public record ChatResult(JsonNode parsed, JsonNode raw) {}

    /** One JSON-schema-constrained chat completion. */
    public ChatResult chatJson(Backend backend, String prompt, JsonNode schema, String model,
                               int maxTokens, int timeoutSeconds) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model == null || model.isEmpty() ? backend.model() : model);
        body.put("temperature", 0);
        body.put("max_tokens", maxTokens);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "user").put("content", prompt);
        ObjectNode js = body.putObject("response_format");
        js.put("type", "json_schema");
        ObjectNode inner = js.putObject("json_schema");
        inner.put("name", "slice");
        inner.put("strict", true);
        inner.set("schema", schema);
        try {
            HttpRequest req = request(backend.baseUrl() + "/chat/completions", Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            JsonNode raw = send(req);
            JsonNode choice = raw.path("choices").path(0);
            if ("length".equals(choice.path("finish_reason").asText(null))) {
                throw new IllegalStateException(
                    "model hit max_tokens before finishing; raise max_tokens or shrink the slice");
            }
            return new ChatResult(mapper.readTree(choice.path("message").path("content").asText()), raw);
        } catch (IOException e) {
            throw new IllegalStateException("backend " + backend.name() + " request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
