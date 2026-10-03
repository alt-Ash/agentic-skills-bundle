package dev.dorrian.localcodegen.probe;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dorrian.localcodegen.backend.BackendClient;
import dev.dorrian.localcodegen.tools.LocalCodegenTools;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CapabilityProbeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicInteger chatCalls = new AtomicInteger();
    private volatile long chatDelayMillis;
    private final Map<String, String> env = new HashMap<>();

    @TempDir
    Path tmp;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", ex -> reply(ex, "{\"data\":[{\"id\":\"m\"}]}"));
        server.createContext("/v1/chat/completions", ex -> {
            chatCalls.incrementAndGet();
            try {
                Thread.sleep(chatDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String content = MAPPER.writeValueAsString(Map.of("files",
                List.of(Map.of("path", "Point.java", "content", "record Point(int x, int y) {}")), "notes", "ok"));
            reply(ex, MAPPER.writeValueAsString(Map.of("choices",
                List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content))),
                "usage", Map.of("completion_tokens", 50))));
        });
        server.start();
        env.put("LOCAL_CODEGEN_BACKENDS", backendsJson("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"));
        env.put("LOCAL_CODEGEN_OUT", tmp.resolve("out").toString());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String backendsJson(String url) throws IOException {
        return MAPPER.writeValueAsString(List.of(Map.of("name", "fake", "base_url", url, "model", "m")));
    }

    private static void reply(HttpExchange ex, String json) throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, data.length);
        ex.getResponseBody().write(data);
        ex.close();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bench(Map<String, Object> health) {
        return (Map<String, Object>) health.get("benchmark");
    }

    private LocalCodegenTools tools() {
        return new LocalCodegenTools(new BackendClient(env::get), env::get);
    }

    @Test
    void fastBackendIsViableAndTheBenchmarkResultIsCached() {
        Map<String, Object> first = tools().health();
        assertThat(first).containsEntry("viable", true);
        assertThat(first).containsKey("hardware");
        assertThat(bench(first)).containsEntry("ok", true).doesNotContainKey("cached");

        Map<String, Object> second = tools().health();
        assertThat(bench(second)).containsEntry("cached", true);
        assertThat(chatCalls.get()).isEqualTo(1);
    }

    @Test
    void forceReRunsTheBenchmark() {
        tools().health();
        env.put("LOCAL_CODEGEN_PROBE", "force");
        tools().health();
        assertThat(chatCalls.get()).isEqualTo(2);
    }

    @Test
    void slowBackendIsNotViableWithAReason() {
        chatDelayMillis = 1500;
        env.put("LOCAL_CODEGEN_BENCH_MAX_SECONDS", "1");
        Map<String, Object> health = tools().health();
        assertThat(health).containsEntry("any_up", true).containsEntry("viable", false);
        assertThat((String) health.get("reason")).contains("limit 1 s");
    }

    @Test
    void probeOffSkipsTheBenchmark() {
        env.put("LOCAL_CODEGEN_PROBE", "off");
        Map<String, Object> health = tools().health();
        assertThat(health).containsEntry("viable", true).doesNotContainKey("benchmark");
        assertThat(chatCalls.get()).isZero();
    }

    @Test
    void noBackendReachableIsNotViableWithAStartCommand() throws IOException {
        env.put("LOCAL_CODEGEN_BACKENDS", backendsJson("http://127.0.0.1:9/v1"));
        Map<String, Object> health = tools().health();
        assertThat(health).containsEntry("any_up", false).containsEntry("viable", false);
        assertThat((String) health.get("reason")).contains("no local backend reachable");
        assertThat((String) health.get("start_command")).contains("llama serve");
    }

    @Test
    void malformedBackendConfigIsReportedNotThrown() {
        env.put("LOCAL_CODEGEN_BACKENDS", "{not json");
        Map<String, Object> health = tools().health();
        assertThat(health).containsEntry("viable", false);
        assertThat((String) health.get("reason")).startsWith("invalid configuration");
    }

    @Test
    void trailingSlashInBaseUrlStillWorks() throws IOException {
        env.put("LOCAL_CODEGEN_BACKENDS",
            backendsJson("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/"));
        assertThat(tools().health()).containsEntry("any_up", true);
    }

    @Test
    void chatFailureFallsThroughToTheNextBackend() throws Exception {
        HttpServer broken = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        broken.createContext("/v1/models", ex -> reply(ex, "{\"data\":[{\"id\":\"x\"}]}"));
        broken.createContext("/v1/chat/completions", ex -> {
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        broken.start();
        try {
            env.put("LOCAL_CODEGEN_BACKENDS", MAPPER.writeValueAsString(List.of(
                Map.of("name", "broken", "base_url", "http://127.0.0.1:" + broken.getAddress().getPort() + "/v1",
                    "model", "x"),
                Map.of("name", "fake", "base_url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "model", "m"))));
            Path proj = Files.createDirectory(tmp.resolve("proj"));
            Map<String, Object> result = tools().generate("spec", List.of("Point.java"), proj.toString(), null,
                null, 200, 30);
            assertThat(result).containsEntry("backend", "fake").containsEntry("complete", true);
        } finally {
            broken.stop(0);
        }
    }

    @Test
    void runDirectoriesOlderThanKeepDaysArePruned() throws Exception {
        Path out = tmp.resolve("out");
        Path stale = Files.createDirectories(out.resolve("abcdef012345"));
        Files.writeString(stale.resolve("A.java"), "x");
        Files.setLastModifiedTime(stale, java.nio.file.attribute.FileTime.from(
            java.time.Instant.now().minus(java.time.Duration.ofDays(30))));
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        tools().generate("spec", List.of("Point.java"), proj.toString(), null, null, 200, 30);
        assertThat(stale).doesNotExist();
    }

    @Test
    void nvidiaSmiOutputPicksTheGpuWithMostFreeMemory() {
        assertThat(CapabilityProbe.parseNvidiaSmi("16376, 3500\n16376, 15000\n")).containsExactly(16376, 15000);
        assertThat(CapabilityProbe.parseNvidiaSmi("garbage")).isNull();
        assertThat(CapabilityProbe.parseNvidiaSmi(null)).isNull();
    }

    @Test
    void tiersFollowFreeMemoryAndCpuOnlyHasNone() {
        assertThat(CapabilityProbe.tier("nvidia", 16_376)).isEqualTo("14B-Q4");
        assertThat(CapabilityProbe.tier("nvidia", 8_192)).isEqualTo("7B-Q4");
        assertThat(CapabilityProbe.tier("nvidia", 4_096)).isEqualTo("none");
        assertThat(CapabilityProbe.tier("cpu", -1)).isEqualTo("none");
        assertThat(CapabilityProbe.tier("amd", -1)).isEqualTo("unknown");
    }

    @Test
    void cpuOnlyMachineIsReportedFromTheRunner() {
        CapabilityProbe probe = new CapabilityProbe(new BackendClient(env::get), env::get, cmd -> null);
        Map<String, Object> hw = probe.hardware(tmp);
        assertThat(hw).containsEntry("accelerator", "cpu").containsEntry("suggested_tier", "none");
    }

    @Test
    void pruneOnlyTouchesOwnRunDirectoriesAndIgnoresNonPositiveKeepDays() throws Exception {
        Path out = tmp.resolve("out");
        java.nio.file.attribute.FileTime old = java.nio.file.attribute.FileTime.from(
            java.time.Instant.now().minus(java.time.Duration.ofDays(30)));
        Path foreign = Files.createDirectories(out.resolve("my-work"));
        Path ownRun = Files.createDirectories(out.resolve("0123456789ab"));
        Files.setLastModifiedTime(foreign, old);
        Files.setLastModifiedTime(ownRun, old);
        Path proj = Files.createDirectory(tmp.resolve("proj"));

        env.put("LOCAL_CODEGEN_KEEP_DAYS", "-1");
        tools().generate("spec", List.of("Point.java"), proj.toString(), null, null, 200, 30);
        assertThat(ownRun).exists();

        env.remove("LOCAL_CODEGEN_KEEP_DAYS");
        tools().generate("spec", List.of("Point.java"), proj.toString(), null, null, 200, 30);
        assertThat(ownRun).doesNotExist();
        assertThat(foreign).exists();
    }

    @Test
    void slowBenchmarkIsNotCachedSoAWarmBackendRecovers() {
        chatDelayMillis = 1500;
        env.put("LOCAL_CODEGEN_BENCH_MAX_SECONDS", "1");
        assertThat(tools().health()).containsEntry("viable", false);
        chatDelayMillis = 0;
        assertThat(tools().health()).containsEntry("viable", true);
        assertThat(chatCalls.get()).isEqualTo(2);
    }

    @Test
    void outputDirExistsEvenWhenNoFileWasAccepted() throws Exception {
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        Map<String, Object> result = tools().generate("spec", List.of("Other.java"), proj.toString(), null,
            null, 200, 30);
        assertThat(result).containsEntry("complete", false);
        assertThat(Path.of((String) result.get("output_dir"))).exists();
    }
}
