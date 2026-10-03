package dev.dorrian.localcodegen.probe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dorrian.localcodegen.backend.Backend;
import dev.dorrian.localcodegen.backend.BackendClient;
import dev.dorrian.localcodegen.backend.Env;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Decides whether routing slices to a local model is worth it on this machine: detects the
 * hardware (reported, advisory) and times one fixed benchmark slice on the first reachable backend
 * (the actual gate). The benchmark result is cached on disk, so {@code health} stays cheap.
 *
 * <p>{@code LOCAL_CODEGEN_PROBE}: {@code off} skips the benchmark, {@code force} re-runs it.
 * {@code LOCAL_CODEGEN_BENCH_MAX_SECONDS} (default 20) is the slowest acceptable benchmark.
 */
public class CapabilityProbe {

    static final int DEFAULT_MAX_SECONDS = 20;
    static final long CACHE_TTL_SECONDS = 24 * 3600;
    public static final String START_COMMAND =
        "llama serve --host 127.0.0.1 --port 8090 -m <first-gguf-shard> -np 2 -c 16384 --fit on";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BENCH_SPEC = "Create a Java record Point with int fields x and y, "
        + "and a method double distanceTo(Point other) returning the Euclidean distance.";
    private static final String BENCH_PROMPT = "You implement exactly one slice of a larger feature. Follow "
        + "the spec literally. Create only the allowed files, no others. Output JSON only.\n\n"
        + "ALLOWED FILES:\nPoint.java\n\nCONTEXT:\n(none)\n\nSPEC:\n" + BENCH_SPEC;
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"files\":{\"type\":\"array\","
        + "\"items\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},"
        + "\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"content\"]}},\"notes\":"
        + "{\"type\":\"string\"}},\"required\":[\"files\",\"notes\"]}";

    private final BackendClient backends;
    private final Env env;
    private final Function<List<String>, String> runner;

    public CapabilityProbe(BackendClient backends, Env env) {
        this(backends, env, CapabilityProbe::run);
    }

    /** {@code runner} executes a command and returns its stdout, or null if it cannot run. */
    public CapabilityProbe(BackendClient backends, Env env, Function<List<String>, String> runner) {
        this.backends = backends;
        this.env = env;
        this.runner = runner;
    }

    /** Hardware snapshot plus a tier suggestion. The tier thresholds are proposals, measured on one machine only. */
    public Map<String, Object> hardware(Path scratch) {
        Map<String, Object> hw = new LinkedHashMap<>();
        long vramTotal = -1;
        long vramFree = -1;
        String accelerator = "cpu";
        String smi = runner.apply(List.of("nvidia-smi", "--query-gpu=memory.total,memory.free",
            "--format=csv,noheader,nounits"));
        long[] nv = parseNvidiaSmi(smi);
        long ramTotal = -1;
        long ramFree = -1;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            ramTotal = os.getTotalMemorySize() >> 20;
            ramFree = os.getFreeMemorySize() >> 20;
        }
        long available = memAvailableMib();
        if (available >= 0) {
            ramFree = available;
        }
        if (nv != null) {
            accelerator = "nvidia";
            vramTotal = nv[0];
            vramFree = nv[1];
        } else if (runner.apply(List.of("rocm-smi", "--showproductname")) != null) {
            accelerator = "amd";
        } else if (System.getProperty("os.name", "").startsWith("Mac")
            && System.getProperty("os.arch", "").contains("aarch64")) {
            accelerator = "apple";
            vramTotal = ramTotal;
            vramFree = ramFree;
        }
        long disk = -1;
        try {
            Path p = scratch;
            while (p != null && !Files.exists(p)) {
                p = p.getParent();
            }
            if (p != null) {
                disk = Files.getFileStore(p).getUsableSpace() >> 20;
            }
        } catch (IOException ignored) {
            // disk stays unknown
        }
        hw.put("accelerator", accelerator);
        hw.put("vram_total_mib", vramTotal);
        hw.put("vram_free_mib", vramFree);
        hw.put("ram_total_mib", ramTotal);
        hw.put("ram_free_mib", ramFree);
        hw.put("cpu_cores", Runtime.getRuntime().availableProcessors());
        hw.put("disk_free_mib", disk);
        hw.put("suggested_tier", tier(accelerator, vramTotal));
        return hw;
    }

    /** Linux MemAvailable (free plus reclaimable cache) in MiB, or -1 elsewhere. */
    private static long memAvailableMib() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) {
                    return Long.parseLong(line.replaceAll("\\D+", "")) >> 10;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // not Linux, or unreadable
        }
        return -1;
    }

    /** Parses {@code nvidia-smi} csv ({@code total, free} MiB per GPU); the GPU with most free memory wins. */
    static long[] parseNvidiaSmi(String csv) {
        if (csv == null) {
            return null;
        }
        long[] best = null;
        for (String line : csv.strip().split("\\R")) {
            String[] parts = line.split(",");
            if (parts.length < 2) {
                continue;
            }
            try {
                long total = Long.parseLong(parts[0].strip());
                long free = Long.parseLong(parts[1].strip());
                if (best == null || free > best[1]) {
                    best = new long[] {total, free};
                }
            } catch (NumberFormatException ignored) {
                // skip unparsable line
            }
        }
        return best;
    }

    /**
     * Starting proposal from the handoff (14B Q4 ~12.5 GB measured, 7B Q4 ~6 GB proposed), judged on TOTAL
     * memory with headroom: a model that is already loaded occupies the free VRAM, so free memory would
     * wrongly rule out a machine that works. Measured on one machine only; the benchmark is the real gate.
     */
    static String tier(String accelerator, long totalMib) {
        if ("cpu".equals(accelerator)) {
            return "none";
        }
        if (totalMib < 0) {
            return "unknown";
        }
        if (totalMib >= 15_000) {
            return "14B-Q4";
        }
        return totalMib >= 7_500 ? "7B-Q4" : "none";
    }

    /** Returns viable, reason, hardware and benchmark for the given probe results (from {@link BackendClient#probe}). */
    public Map<String, Object> assess(List<Map<String, Object>> probes, Path scratch) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> hw = hardware(scratch);
        out.put("hardware", hw);
        Backend chosen = null;
        List<Backend> configured = backends.loadBackends();
        for (int i = 0; i < probes.size() && i < configured.size(); i++) {
            if (Boolean.TRUE.equals(probes.get(i).get("up"))) {
                chosen = configured.get(i);
                break;
            }
        }
        if (chosen == null) {
            out.put("viable", false);
            out.put("reason", "no local backend reachable");
            out.put("start_command", START_COMMAND);
            return out;
        }
        String mode = env.get("LOCAL_CODEGEN_PROBE");
        if ("off".equals(mode)) {
            out.put("viable", true);
            out.put("reason", "benchmark disabled (LOCAL_CODEGEN_PROBE=off)");
            return out;
        }
        int max = maxSeconds();
        Map<String, Object> bench = benchmark(chosen, hw, scratch, max, "force".equals(mode));
        out.put("benchmark", bench);
        if (!Boolean.TRUE.equals(bench.get("ok"))) {
            out.put("viable", false);
            out.put("reason", "benchmark failed: " + bench.get("error"));
        } else if (((Number) bench.get("seconds")).doubleValue() > max) {
            out.put("viable", false);
            out.put("reason", "benchmark slice took " + bench.get("seconds") + " s (limit " + max + " s)");
        } else {
            out.put("viable", true);
            out.put("reason", "benchmark slice took " + bench.get("seconds") + " s (limit " + max + " s)");
        }
        return out;
    }

    private int maxSeconds() {
        String raw = env.get("LOCAL_CODEGEN_BENCH_MAX_SECONDS");
        try {
            int v = raw == null ? DEFAULT_MAX_SECONDS : Integer.parseInt(raw.strip());
            return v > 0 ? v : DEFAULT_MAX_SECONDS;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_SECONDS;
        }
    }

    private Map<String, Object> benchmark(Backend b, Map<String, Object> hw, Path scratch, int max, boolean force) {
        String key = b.name() + "|" + b.baseUrl() + "|" + b.model() + "|" + hw.get("accelerator") + "|" + hw.get("vram_total_mib");
        Path cache = scratch.resolve("probe.json");
        if (!force) {
            Map<String, Object> cached = readCache(cache, key);
            if (cached != null) {
                return cached;
            }
        }
        Map<String, Object> bench = new LinkedHashMap<>();
        bench.put("backend", b.name());
        bench.put("measured_at", Instant.now().toString());
        long start = System.nanoTime();
        try {
            BackendClient.ChatResult r = backends.chatJson(b, BENCH_PROMPT, MAPPER.readTree(SCHEMA), null, 400,
                max * 2);
            double seconds = Math.round((System.nanoTime() - start) / 1e8) / 10.0;
            JsonNode tokens = r.raw().path("usage").path("completion_tokens");
            bench.put("ok", true);
            bench.put("seconds", seconds);
            bench.put("completion_tokens", tokens.isNumber() ? tokens.asInt() : null);
            if (tokens.isNumber() && seconds > 0) {
                bench.put("tokens_per_second", Math.round(tokens.asInt() / seconds * 10) / 10.0);
            }
        } catch (IOException | RuntimeException e) {
            bench.put("ok", false);
            bench.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return bench;
        }
        // Only a result within the limit is cached: a slow first call is usually model load, and caching it
        // would keep health at viable=false for a day after the backend is warm.
        if (((Number) bench.get("seconds")).doubleValue() <= max) {
            writeCache(cache, key, bench);
        }
        return bench;
    }

    private Map<String, Object> readCache(Path cache, String key) {
        try {
            if (!Files.isRegularFile(cache)) {
                return null;
            }
            JsonNode n = MAPPER.readTree(cache.toFile());
            if (!key.equals(n.path("key").asText())) {
                return null;
            }
            Instant at = Instant.parse(n.path("benchmark").path("measured_at").asText());
            if (Instant.now().isAfter(at.plusSeconds(CACHE_TTL_SECONDS))) {
                return null;
            }
            Map<String, Object> bench = MAPPER.convertValue(n.get("benchmark"),
                new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
            bench.put("cached", true);
            return bench;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void writeCache(Path cache, String key, Map<String, Object> bench) {
        try {
            Files.createDirectories(cache.getParent());
            MAPPER.writeValue(cache.toFile(), Map.of("key", key, "benchmark", bench));
        } catch (IOException ignored) {
            // cache is best-effort
        }
    }

    private static String run(List<String> cmd) {
        Path out = null;
        try {
            out = Files.createTempFile("local-codegen-probe", ".out");
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(out.toFile()).start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? Files.readString(out) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (out != null) {
                try {
                    Files.deleteIfExists(out);
                } catch (IOException ignored) {
                    // temp file cleanup is best-effort
                }
            }
        }
    }
}
