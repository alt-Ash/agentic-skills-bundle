package dev.dorrian.agenticskillsevals.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dorrian.agenticskillsevals.protocol.ChatResult;
import dev.dorrian.agenticskillsevals.protocol.ClaudeSession;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of {@code evals/behavioral/lib/judge.ts}. A second real model call ({@link ClaudeSession},
 * same class used for the agent-under-test call - the judge is just another session, not a
 * separate client) grades the agent's response against a rubric.
 */
public final class Judge {

    public record Result(double score, Map<String, Double> dimensions, String rationale,
                          String reasoning, boolean passed) {
    }

    private static final String JUDGE_SYSTEM = """
            You are an expert evaluator of AI agent outputs.
            Rate the agent response on each provided criterion with a score from 1 to 5, where 1 = fails the criterion, 3 = acceptable, 5 = fully meets it.
            First reason through the evidence in the response against each criterion, THEN assign scores — scoring before reasoning produces unreliable grades.
            Reply with ONLY a JSON object wrapped in <result> tags — no markdown, no explanation outside it:
            <result>{"reasoning": "<step-by-step assessment of each criterion citing evidence>", "dimensions": {"criterion_key": <integer 1-5>, ...}, "rationale": "<one sentence overall assessment>"}</result>""";

    // Matches raw ASCII control characters (newlines, tabs, etc.) inside the judge's JSON reply.
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x1F]+");
    private static final Pattern RESULT_TAG = Pattern.compile("<result>([\\s\\S]*?)</result>");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String model;

    public Judge(String model) {
        this.model = model;
    }

    public Result judge(String agentDescription, String scenario, String response,
                        Map<String, String> criteria) throws Exception {
        return judge(agentDescription, scenario, response, criteria, 3, null);
    }

    public Result judge(String agentDescription, String scenario, String response,
                        Map<String, String> criteria, double passingThreshold, String referenceAnswer)
            throws Exception {
        String referenceSection = referenceAnswer == null || referenceAnswer.isBlank()
                ? ""
                : "\n## Reference (notes on what an ideal answer contains)\n" + referenceAnswer + "\n";
        String prompt = "## Agent description\n" + agentDescription
                + "\n\n## Scenario given to agent\n" + scenario
                + "\n\n## Agent response\n" + response
                + referenceSection
                + "\n## Scoring criteria (rate each 1-5)\n" + buildCriteriaList(criteria);

        String raw;
        try (ClaudeSession judgeSession = ClaudeSession.builder()
                .model(model)
                .systemPrompt(JUDGE_SYSTEM)
                .build()) {
            ChatResult chatResult = judgeSession.query(prompt);
            raw = chatResult.text();
        }

        return parse(raw, passingThreshold);
    }

    private static String buildCriteriaList(Map<String, String> criteria) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (Map.Entry<String, String> entry : criteria.entrySet()) {
            if (index > 1) {
                sb.append('\n');
            }
            sb.append(index).append(". ").append(entry.getKey()).append(": ").append(entry.getValue());
            index++;
        }
        return sb.toString();
    }

    static Result parse(String raw, double passingThreshold) {
        Matcher xmlMatch = RESULT_TAG.matcher(raw);
        String jsonStr;
        if (xmlMatch.find()) {
            jsonStr = xmlMatch.group(1).trim();
        } else {
            int first = raw.indexOf('{');
            int last = raw.lastIndexOf('}');
            jsonStr = (first != -1 && last > first) ? raw.substring(first, last + 1) : "";
        }

        if (jsonStr.isEmpty()) {
            throw new IllegalStateException("Judge returned no parseable JSON.\nRaw response: " + raw);
        }

        // Judges often emit multi-line reasoning with raw newlines/tabs inside string
        // literals, which strict JSON parsing rejects. Flatten control chars to spaces first.
        String cleanedJson = CONTROL_CHARS.matcher(jsonStr).replaceAll(" ");

        try {
            JsonNode parsed = MAPPER.readTree(cleanedJson);
            Map<String, Double> dimensions = new LinkedHashMap<>();
            JsonNode dimensionsNode = parsed.path("dimensions");
            dimensionsNode.fields().forEachRemaining(e -> dimensions.put(e.getKey(), e.getValue().asDouble()));

            double avg = dimensions.isEmpty()
                    ? 0
                    : dimensions.values().stream().mapToDouble(Double::doubleValue).sum() / dimensions.size();
            double score = Math.round(avg * 10) / 10.0;

            return new Result(
                    score,
                    dimensions,
                    parsed.path("rationale").asText(""),
                    parsed.path("reasoning").asText(""),
                    avg >= passingThreshold);
        } catch (Exception e) {
            throw new IllegalStateException("Judge JSON parse error: " + e + "\nRaw response: " + raw, e);
        }
    }
}
