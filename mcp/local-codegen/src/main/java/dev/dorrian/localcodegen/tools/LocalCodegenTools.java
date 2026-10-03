package dev.dorrian.localcodegen.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dorrian.localcodegen.backend.Backend;
import dev.dorrian.localcodegen.backend.BackendClient;
import dev.dorrian.localcodegen.backend.Env;
import dev.dorrian.localcodegen.probe.CapabilityProbe;
import dev.dorrian.localcodegen.validate.Validate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * The three MCP tools: health, list_models, generate_slice. Claude plans and reviews; this server
 * only generates. Output goes to a scratch directory, never into the project, and NO validation
 * gates run here by design.
 */
@Service
public class LocalCodegenTools {

    static final String SCHEMA_JSON = """
        {"type":"object","properties":{"files":{"type":"array","items":{"type":"object",
        "properties":{"path":{"type":"string"},"content":{"type":"string"}},
        "required":["path","content"]}},"notes":{"type":"string"}},"required":["files","notes"]}""";

    static final String PROMPT = "You implement exactly one slice of a larger feature. Follow the spec "
        + "literally. Create only the allowed files, no others. Output JSON only.\n\n"
        + "ALLOWED FILES:\n%s\n\nCONTEXT (existing code you may rely on, do not recreate):\n%s\n\nSPEC:\n%s";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode SCHEMA;

    static {
        try {
            SCHEMA = MAPPER.readTree(SCHEMA_JSON);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final int KEEP_DAYS_DEFAULT = 7;

    private final BackendClient backends;
    private final Env env;
    private final CapabilityProbe probe;

    public LocalCodegenTools(BackendClient backends, Env env) {
        this.backends = backends;
        this.env = env;
        this.probe = new CapabilityProbe(backends, env);
    }

    private Path scratchRoot() {
        String out = env.get("LOCAL_CODEGEN_OUT");
        if (out != null && !out.isEmpty()) {
            return Path.of(out);
        }
        return Path.of(System.getProperty("user.home"), ".cache", "local-codegen");
    }

    @Tool(name = "health", description = "Report which local backends are reachable and which model each serves, "
        + "plus a capability probe: `viable` (false with a `reason` when no backend is up or the timed benchmark "
        + "slice is too slow), the detected `hardware` and the `benchmark`. Callers must treat viable=false as blocked.")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            for (Backend b : backends.loadBackends()) {
                results.add(backends.probe(b));
            }
        } catch (IllegalArgumentException e) {
            out.put("any_up", false);
            out.put("backends", List.of());
            out.put("viable", false);
            out.put("reason", "invalid configuration: " + e.getMessage());
            return out;
        }
        out.put("any_up", results.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("up"))));
        out.put("backends", results);
        out.putAll(probe.assess(results, scratchRoot()));
        return out;
    }

    @Tool(name = "list_models", description = "List models served by every reachable local backend.")
    public Map<String, Object> listModels() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Backend b : backends.loadBackends()) {
            Map<String, Object> r = backends.probe(b);
            if (Boolean.TRUE.equals(r.get("up"))) {
                out.put((String) r.get("name"), r.get("models"));
            }
        }
        return out;
    }

    @Tool(name = "generate_slice", description = "Generate the files for one mechanical slice with a local "
        + "model. Writes accepted files under output_dir (mirroring their relative paths) and returns a "
        + "summary. Nothing is written to the project. Check `rejected` and `missing`; if either is "
        + "non-empty the slice is not complete and should be retried or taken over by Claude.")
    public Map<String, Object> generateSlice(
        @ToolParam(description = "Complete instructions for this slice only.") String slice_spec,
        @ToolParam(description = "Relative paths the model may create; anything else is rejected.")
        List<String> files_allowed,
        @ToolParam(description = "Repo/worktree root; context_files are read from it and must stay inside it.")
        String project_root,
        @ToolParam(description = "Relative paths of existing code the slice relies on.", required = false)
        List<String> context_files,
        @ToolParam(description = "Override the backend's configured model.", required = false) String model,
        @ToolParam(description = "Max completion tokens (default 4000).", required = false) Integer max_tokens,
        @ToolParam(description = "Backend request timeout in seconds (default 300).", required = false)
        Integer timeout_seconds
    ) {
        return generate(slice_spec, files_allowed, project_root, context_files, model,
            max_tokens == null ? 4000 : max_tokens, timeout_seconds == null ? 300 : timeout_seconds);
    }

    /** Non-annotated entry point so tests can call with primitives. */
    public Map<String, Object> generate(String sliceSpec, List<String> filesAllowed, String projectRoot,
                                        List<String> contextFiles, String model, int maxTokens,
                                        int timeoutSeconds) {
        List<String> allowed = Validate.normalizeAllowed(filesAllowed);
        Map<String, String> context = Validate.resolveContext(projectRoot,
            contextFiles == null ? List.of() : contextFiles);

        String ctx = context.isEmpty() ? "(none)" : context.entrySet().stream()
            .map(e -> "// " + e.getKey() + "\n" + e.getValue()).collect(Collectors.joining("\n\n"));
        String prompt = PROMPT.formatted(String.join("\n", allowed), ctx, sliceSpec);

        long start = System.nanoTime();
        Backend chosen = null;
        String usedModel = null;
        BackendClient.ChatResult result = null;
        IllegalStateException lastFailure = null;
        boolean first = true;
        for (Backend candidate : backends.loadBackends()) {
            if (!Boolean.TRUE.equals(backends.probe(candidate).get("up"))) {
                continue;
            }
            // A model override names a model on the first backend tried; fallbacks use their own configured model.
            String useModel = first ? model : null;
            first = false;
            try {
                result = backends.chatJson(candidate, prompt, SCHEMA, useModel, maxTokens, timeoutSeconds);
                chosen = candidate;
                usedModel = useModel;
                break;
            } catch (IllegalStateException e) {
                lastFailure = e;
            }
        }
        if (chosen == null) {
            if (lastFailure != null) {
                throw lastFailure;
            }
            throw new IllegalStateException("no local backend reachable; start one, e.g. `"
                + CapabilityProbe.START_COMMAND + "`");
        }
        double seconds = Math.round((System.nanoTime() - start) / 1e8) / 10.0;

        JsonNode parsed = result.parsed();
        if (!parsed.path("files").isArray() || !parsed.has("notes")) {
            throw new IllegalStateException("model output missing 'files' or 'notes'");
        }
        List<Validate.OutputFile> files = new ArrayList<>();
        for (JsonNode f : parsed.get("files")) {
            if (!f.path("path").isTextual() || !f.path("content").isTextual()) {
                throw new IllegalStateException("model output file entry missing 'path' or 'content'");
            }
            files.add(new Validate.OutputFile(f.get("path").asText(), f.get("content").asText()));
        }
        Validate.Checked checked = Validate.checkOutput(files, allowed);
        List<String> got = checked.accepted().stream().map(Validate.OutputFile::path).toList();
        List<String> missing = allowed.stream().filter(p -> !got.contains(p)).toList();

        String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path outDir = scratchRoot().resolve(runId);
        prune(scratchRoot());
        try {
            Files.createDirectories(outDir);
            for (Validate.OutputFile f : checked.accepted()) {
                Path target = outDir.resolve(f.path());
                Files.createDirectories(target.getParent());
                Files.writeString(target, f.content(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not write output: " + e.getMessage(), e);
        }

        List<Map<String, Object>> fileSummaries = new ArrayList<>();
        for (Validate.OutputFile f : checked.accepted()) {
            fileSummaries.add(Map.of("path", f.path(),
                "bytes", f.content().getBytes(StandardCharsets.UTF_8).length));
        }
        List<Map<String, Object>> rejected = new ArrayList<>();
        for (Validate.Rejected r : checked.rejected()) {
            rejected.add(Map.of("path", r.path(), "reason", r.reason()));
        }
        JsonNode tokens = result.raw().path("usage").path("completion_tokens");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", runId);
        out.put("output_dir", outDir.toString());
        out.put("complete", rejected.isEmpty() && missing.isEmpty());
        out.put("files", fileSummaries);
        out.put("rejected", rejected);
        out.put("missing", missing);
        out.put("notes", parsed.get("notes").asText());
        out.put("backend", chosen.name());
        out.put("model", usedModel == null || usedModel.isEmpty() ? chosen.model() : usedModel);
        out.put("seconds", seconds);
        out.put("completion_tokens", tokens.isNumber() ? tokens.asInt() : null);
        return out;
    }

    /**
     * Removes this server's own run directories (12 hex chars) older than LOCAL_CODEGEN_KEEP_DAYS (default 7;
     * zero or negative disables pruning). Anything else under the scratch root is left alone. Best-effort.
     */
    private void prune(Path root) {
        long days = KEEP_DAYS_DEFAULT;
        try {
            String raw = env.get("LOCAL_CODEGEN_KEEP_DAYS");
            if (raw != null && !raw.isBlank()) {
                days = Long.parseLong(raw.strip());
            }
            if (days <= 0) {
                return;
            }
            java.time.Instant cutoff = java.time.Instant.now().minus(java.time.Duration.ofDays(days));
            try (var dirs = Files.list(root)) {
                for (Path d : (Iterable<Path>) dirs.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().matches("[0-9a-f]{12}"))::iterator) {
                    if (Files.getLastModifiedTime(d).toInstant().isBefore(cutoff)) {
                        try (var walk = Files.walk(d)) {
                            for (Path p : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) {
                                Files.deleteIfExists(p);
                            }
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // pruning must never fail a generation
        }
    }
}
