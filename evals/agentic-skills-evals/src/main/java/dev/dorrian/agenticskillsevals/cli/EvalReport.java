package dev.dorrian.agenticskillsevals.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Port of {@code evals/report.ts}: reads every {@code results/history/*.json} record (written by
 * {@code AbstractEvalTest}), groups by {@code agent::scenario}, splits each group's
 * timestamp-sorted records into a "current" window (last N, default 10) and the N before that,
 * computes windowed stats + deltas, and raises the same three advisory alerts as the original
 * (thresholds below). Writes {@code results/REPORT.md} + {@code results/report.json}; with
 * {@code --save-baseline} also writes {@code results/baselines.json}.
 */
final class EvalReport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final double JUDGE_REGRESSION_DROP = 0.3;
    private static final double PASS_RATE_REGRESSION_DROP = 0.10;
    private static final double TOKEN_DELTA_THRESHOLD = 0.10;

    private EvalReport() {
    }

    static void run(String[] args) throws IOException {
        boolean saveBaseline = false;
        int window = 10;
        for (String arg : args) {
            if (arg.equals("--save-baseline")) {
                saveBaseline = true;
            } else if (arg.startsWith("--window=")) {
                window = Integer.parseInt(arg.substring("--window=".length()));
            }
        }

        Path resultsDir = Path.of("results");
        Path historyDir = resultsDir.resolve("history");
        if (!Files.isDirectory(historyDir)) {
            System.out.println("No results/history/ found - nothing to report on. Run `check`/`select` first.");
            return;
        }

        Map<String, List<JsonNode>> byGroup = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(historyDir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                JsonNode record = MAPPER.readTree(file.toFile());
                String key = record.path("agent").asText("?") + "::" + record.path("scenario").asText("?");
                byGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(record);
            }
        }

        for (List<JsonNode> records : byGroup.values()) {
            records.sort(Comparator.comparing(r -> r.path("timestamp").asText("")));
        }

        StringBuilder md = new StringBuilder("# Eval Report\n\n");
        ObjectNode reportJson = NODES.objectNode();
        ObjectNode baselinesJson = NODES.objectNode();
        List<String> alerts = new ArrayList<>();

        for (var entry : byGroup.entrySet()) {
            String key = entry.getKey();
            List<JsonNode> records = entry.getValue();
            int total = records.size();
            List<JsonNode> current = records.subList(Math.max(0, total - window), total);
            int prevEnd = Math.max(0, total - window);
            int prevStart = Math.max(0, prevEnd - window);
            List<JsonNode> previous = records.subList(prevStart, prevEnd);

            WindowStats curStats = WindowStats.of(current);
            WindowStats prevStats = previous.isEmpty() ? null : WindowStats.of(previous);

            md.append("## ").append(key).append("\n\n");
            md.append("- Runs (current window): ").append(current.size()).append("\n");
            md.append("- Pass rate: ").append(pct(curStats.passRate)).append("\n");
            md.append("- Mean judge score: ").append(round1(curStats.meanJudgeScore)).append("/5\n");
            md.append("- Mean tokens: ").append(Math.round(curStats.meanTotalTokens)).append("\n");
            md.append("- Mean cost: $").append(round4(curStats.meanCostUsd)).append("\n");
            md.append("- Mean duration: ").append(Math.round(curStats.meanDurationMs)).append("ms\n");

            ObjectNode groupJson = NODES.objectNode();
            groupJson.set("current", curStats.toJson());

            if (prevStats != null) {
                double judgeDelta = curStats.meanJudgeScore - prevStats.meanJudgeScore;
                double passRateDelta = curStats.passRate - prevStats.passRate;
                double tokenDelta = prevStats.meanTotalTokens == 0 ? 0
                        : (curStats.meanTotalTokens - prevStats.meanTotalTokens) / prevStats.meanTotalTokens;

                md.append("- Judge delta: ").append(round1(judgeDelta)).append("\n");
                md.append("- Pass-rate delta: ").append(pct(passRateDelta)).append("\n");
                md.append("- Token delta: ").append(pct(tokenDelta)).append("\n");

                String alert = classifyAlert(key, judgeDelta, passRateDelta, tokenDelta);
                if (alert != null) {
                    alerts.add(alert);
                    md.append("- **").append(alert).append("**\n");
                }

                ObjectNode deltaJson = NODES.objectNode();
                deltaJson.put("judgeDelta", judgeDelta);
                deltaJson.put("passRateDelta", passRateDelta);
                deltaJson.put("tokenDelta", tokenDelta);
                groupJson.set("previous", prevStats.toJson());
                groupJson.set("delta", deltaJson);
            } else {
                md.append("- (no previous window yet - need > ").append(window).append(" runs for a delta)\n");
            }
            md.append("\n");
            reportJson.set(key, groupJson);

            if (saveBaseline) {
                baselinesJson.set(key, curStats.toJson());
            }
        }

        if (!alerts.isEmpty()) {
            System.out.println("Alerts:");
            alerts.forEach(a -> System.out.println("  - " + a));
        } else {
            System.out.println("No regressions/spikes detected across " + byGroup.size() + " agent::scenario group(s).");
        }

        Files.writeString(resultsDir.resolve("REPORT.md"), md.toString());
        Files.writeString(resultsDir.resolve("report.json"), reportJson.toPrettyString());
        System.out.println("Wrote " + resultsDir.resolve("REPORT.md") + " and " + resultsDir.resolve("report.json"));

        if (saveBaseline) {
            Files.writeString(resultsDir.resolve("baselines.json"), baselinesJson.toPrettyString());
            System.out.println("Wrote " + resultsDir.resolve("baselines.json"));
        }
    }

    /**
     * Returns the advisory alert text for these deltas, or {@code null} if none applies.
     * Regression takes priority over the token-only alerts (a regression that also happens to use
     * fewer tokens is still a regression, not a win).
     */
    static String classifyAlert(String key, double judgeDelta, double passRateDelta, double tokenDelta) {
        boolean regression = judgeDelta <= -JUDGE_REGRESSION_DROP || passRateDelta <= -PASS_RATE_REGRESSION_DROP;
        if (regression) {
            return "QUALITY REGRESSION in " + key + ": judge " + round1(judgeDelta) + ", pass-rate " + pct(passRateDelta);
        } else if (tokenDelta <= -TOKEN_DELTA_THRESHOLD) {
            return "EFFICIENCY WIN in " + key + ": tokens " + pct(tokenDelta) + ", no quality regression";
        } else if (tokenDelta >= TOKEN_DELTA_THRESHOLD) {
            return "TOKEN SPIKE in " + key + ": tokens " + pct(tokenDelta);
        }
        return null;
    }

    private static String pct(double v) {
        return round1(v * 100) + "%";
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    record WindowStats(double passRate, double meanJudgeScore, double meanInputTokens,
                                double meanOutputTokens, double meanTotalTokens, double meanCostUsd,
                                double meanDurationMs, Set<String> gitShas) {

        static WindowStats of(List<JsonNode> records) {
            int n = records.size();
            if (n == 0) {
                return new WindowStats(0, 0, 0, 0, 0, 0, 0, Set.of());
            }
            double passed = 0, judgeSum = 0, inSum = 0, outSum = 0, costSum = 0, durSum = 0;
            Set<String> shas = new LinkedHashSet<>();
            for (JsonNode r : records) {
                if (r.path("passed").asBoolean(false)) passed++;
                judgeSum += r.path("judgeScore").asDouble(0);
                inSum += r.path("tokenUsage").path("inputTokens").asDouble(0);
                outSum += r.path("tokenUsage").path("outputTokens").asDouble(0);
                costSum += r.path("tokenUsage").path("estimatedCostUsd").asDouble(0);
                durSum += r.path("durationMs").asDouble(0);
                String sha = r.path("gitSha").asText(null);
                if (sha != null) shas.add(sha);
            }
            return new WindowStats(passed / n, judgeSum / n, inSum / n, outSum / n,
                    (inSum + outSum) / n, costSum / n, durSum / n, shas);
        }

        ObjectNode toJson() {
            ObjectNode node = NODES.objectNode();
            node.put("passRate", passRate);
            node.put("meanJudgeScore", meanJudgeScore);
            node.put("meanInputTokens", meanInputTokens);
            node.put("meanOutputTokens", meanOutputTokens);
            node.put("meanTotalTokens", meanTotalTokens);
            node.put("meanCostUsd", meanCostUsd);
            node.put("meanDurationMs", meanDurationMs);
            ArrayNode shaArray = NODES.arrayNode();
            gitShas.forEach(shaArray::add);
            node.set("gitShas", shaArray);
            return node;
        }
    }
}
