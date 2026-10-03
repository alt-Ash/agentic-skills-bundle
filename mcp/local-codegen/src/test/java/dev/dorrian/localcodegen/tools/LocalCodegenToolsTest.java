package dev.dorrian.localcodegen.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.dorrian.localcodegen.backend.BackendClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalCodegenToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<JsonNode> seen = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private volatile String chatContent;
    private volatile String finishReason = "stop";
    private final Map<String, String> env = new HashMap<>();

    @TempDir
    Path tmp;

    @BeforeEach
    void start() throws IOException {
        chatContent = MAPPER.writeValueAsString(Map.of(
            "files", List.of(Map.of("path", "src/A.java", "content", "class A {}\n"),
                Map.of("path", "src/Extra.java", "content", "class X {}\n")),
            "notes", "ok"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", ex -> reply(ex, "{\"data\":[{\"id\":\"fake-model\"}]}"));
        server.createContext("/v1/chat/completions", ex -> {
            seen.add(MAPPER.readTree(ex.getRequestBody().readAllBytes()));
            authHeaders.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            try {
                reply(ex, MAPPER.writeValueAsString(Map.of(
                    "choices", List.of(Map.of("finish_reason", finishReason, "message", Map.of("content", chatContent))),
                    "usage", Map.of("completion_tokens", 7))));
            } catch (Exception e) {
                throw new IOException(e);
            }
        });
        server.start();
        env.put("LOCAL_CODEGEN_BACKENDS", MAPPER.writeValueAsString(List.of(
            Map.of("name", "dead", "base_url", "http://127.0.0.1:9/v1", "model", "x"),
            Map.of("name", "fake", "base_url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "model", "fake-model"))));
        env.put("LOCAL_CODEGEN_OUT", tmp.resolve("out").toString());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, String json) throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, data.length);
        ex.getResponseBody().write(data);
        ex.close();
    }

    private LocalCodegenTools tools() {
        return new LocalCodegenTools(new BackendClient(env::get), env::get);
    }

    private Path project() throws IOException {
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Files.writeString(proj.resolve("ctx.java"), "class Ctx {}");
        return proj;
    }

    @SuppressWarnings("unchecked")
    @Test
    void healthReportsFallbackOrder() {
        Map<String, Object> health = tools().health();
        assertThat(health.get("any_up")).isEqualTo(true);
        List<Map<String, Object>> backends = (List<Map<String, Object>>) health.get("backends");
        assertThat(backends).extracting(b -> b.get("up")).containsExactly(false, true);
        assertThat(backends.get(1).get("models")).isEqualTo(List.of("fake-model"));
        assertThat(backends.get(0)).containsKey("error");
    }

    @Test
    void healthAnyUpFalseWhenNothingReachable() {
        env.put("LOCAL_CODEGEN_BACKENDS",
            "[{\"name\":\"dead\",\"base_url\":\"http://127.0.0.1:9/v1\",\"model\":\"x\"}]");
        assertThat(tools().health().get("any_up")).isEqualTo(false);
        assertThat(tools().listModels()).isEmpty();
    }

    @Test
    void listModelsOnlyIncludesReachableBackends() {
        assertThat(tools().listModels()).isEqualTo(Map.of("fake", List.of("fake-model")));
    }

    @Test
    void defaultBackendsAreLlamaThenOllama() {
        env.remove("LOCAL_CODEGEN_BACKENDS");
        var defaults = new BackendClient(env::get).loadBackends();
        assertThat(defaults).extracting(b -> b.name() + "@" + b.baseUrl())
            .containsExactly("llama@http://127.0.0.1:8090/v1", "ollama@http://127.0.0.1:11434/v1");
    }

    @SuppressWarnings("unchecked")
    @Test
    void generateSliceHappyPathWritesOnlyToScratch() throws Exception {
        env.put("LOCAL_CODEGEN_API_KEY", "secret");
        Path proj = project();
        Map<String, Object> res = tools().generateSlice("make A", List.of("src/A.java"), proj.toString(),
            List.of("ctx.java"), null, null, null);

        assertThat(res.get("backend")).isEqualTo("fake");
        assertThat(res.get("model")).isEqualTo("fake-model");
        assertThat((List<Map<String, Object>>) res.get("rejected"))
            .containsExactly(Map.of("path", "src/Extra.java", "reason", "path not in files_allowed"));
        assertThat(res.get("complete")).isEqualTo(false);
        assertThat(res.get("missing")).isEqualTo(List.of());
        assertThat(res.get("completion_tokens")).isEqualTo(7);
        assertThat(res.get("notes")).isEqualTo("ok");
        Path out = tmp.resolve("out").resolve((String) res.get("run_id"));
        assertThat(res.get("output_dir")).isEqualTo(out.toString());
        assertThat(Files.readString(out.resolve("src/A.java"))).isEqualTo("class A {}\n");
        assertThat(out.resolve("src/Extra.java")).doesNotExist();
        assertThat(seen.get(0).at("/messages/0/content").asText()).contains("class Ctx {}");
        assertThat(seen.get(0).at("/response_format/type").asText()).isEqualTo("json_schema");
        assertThat(seen.get(0).at("/max_tokens").asInt()).isEqualTo(4000);
        assertThat(seen.get(0).at("/temperature").asInt()).isZero();
        assertThat(authHeaders.get(0)).isEqualTo("Bearer secret");
        try (var files = Files.walk(proj)) {
            assertThat(files.noneMatch(p -> p.getFileName().toString().equals("A.java"))).isTrue();
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void completeWhenEverythingAllowedIsDelivered() throws Exception {
        chatContent = MAPPER.writeValueAsString(Map.of(
            "files", List.of(Map.of("path", "src/A.java", "content", "class A {}")), "notes", "n"));
        Map<String, Object> res = tools().generate("s", List.of("src/A.java"), project().toString(), null, "m2", 100, 30);
        assertThat(res.get("complete")).isEqualTo(true);
        assertThat(res.get("model")).isEqualTo("m2");
        assertThat(((List<Map<String, Object>>) res.get("files")).get(0)).containsEntry("bytes", 10);
    }

    @Test
    void lineMarkerArtifactIsRejectedAndReportedMissing() throws Exception {
        chatContent = MAPPER.writeValueAsString(Map.of(
            "files", List.of(Map.of("path", "src/A.java", "content", "#L1\nclass A {}\n")), "notes", "n"));
        Map<String, Object> res = tools().generate("s", List.of("src/A.java"), project().toString(), null, null, 100, 30);
        assertThat(res.get("complete")).isEqualTo(false);
        assertThat(res.get("missing")).isEqualTo(List.of("src/A.java"));
        assertThat(res.get("rejected").toString()).contains("line-marker artifact");
    }

    @Test
    void missingFilesAreReported() throws Exception {
        Map<String, Object> res = tools().generate("s", List.of("src/A.java", "src/B.java"),
            project().toString(), null, null, 100, 30);
        assertThat(res.get("missing")).isEqualTo(List.of("src/B.java"));
        assertThat(res.get("complete")).isEqualTo(false);
    }

    @Test
    void finishReasonLengthIsAnError() throws Exception {
        finishReason = "length";
        assertThatThrownBy(() -> tools().generate("s", List.of("src/A.java"), project().toString(), null, null, 100, 30))
            .hasMessageContaining("max_tokens");
    }

    @Test
    void pathOutsideFilesAllowedFromRequestIsRejected() {
        assertThatThrownBy(() -> tools().generate("s", List.of("../evil.java"), tmp.toString(), null, null, 100, 30))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void contextFileEscapingProjectRootIsRefused() throws Exception {
        Path proj = project();
        Files.writeString(tmp.resolve("secret.txt"), "nope");
        assertThatThrownBy(() -> tools().generate("s", List.of("a.txt"), proj.toString(),
            new ArrayList<>(List.of("../secret.txt")), null, 100, 30))
            .hasMessageContaining("escapes project_root");
        assertThat(seen).isEmpty();
    }

    @Test
    void noBackendIsAnError() {
        env.put("LOCAL_CODEGEN_BACKENDS",
            "[{\"name\":\"dead\",\"base_url\":\"http://127.0.0.1:9/v1\",\"model\":\"x\"}]");
        assertThatThrownBy(() -> tools().generate("s", List.of("a.txt"), tmp.toString(), null, null, 100, 30))
            .hasMessageContaining("no local backend reachable");
    }
}
