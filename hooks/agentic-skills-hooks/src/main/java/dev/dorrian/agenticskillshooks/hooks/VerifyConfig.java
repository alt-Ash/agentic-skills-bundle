package dev.dorrian.agenticskillshooks.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dorrian.agenticskillshooks.JsonSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@code <project>/.agentic-skills/verify.json}: commands, timeoutSeconds (300), maxConsecutiveBlocks (3). */
record VerifyConfig(List<String> commands, int timeoutSeconds, int maxConsecutiveBlocks) {

    static final int DEFAULT_TIMEOUT_SECONDS = 300;
    /** Per-command cap; the registered host timeout (HooksRegistry.VERIFY) is deliberately higher. */
    static final int MAX_TIMEOUT_SECONDS = 540;
    /** Cap on ALL commands together, so the host timeout (HooksRegistry.VERIFY, 600s) is never outlived by a long list. */
    static final int MAX_TOTAL_SECONDS = 540;
    static final int DEFAULT_MAX_BLOCKS = 3;

    /** Empty when the file is missing, unparsable or lists no commands. Never throws. */
    static Optional<VerifyConfig> load(Path project) {
        try {
            Path file = project.resolve(".agentic-skills").resolve("verify.json");
            if (!Files.isRegularFile(file)) return Optional.empty();
            return parse(Files.readString(file));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static Optional<VerifyConfig> parse(String json) {
        try {
            JsonNode root = JsonSupport.MAPPER.readTree(json);
            if (root == null || !root.isObject()) return Optional.empty();
            List<String> commands = new ArrayList<>();
            JsonNode cmds = root.get("commands");
            if (cmds == null || !cmds.isArray()) return Optional.empty();
            for (JsonNode c : cmds) {
                if (c.isTextual() && !c.asText().isBlank()) commands.add(c.asText());
            }
            if (commands.isEmpty()) return Optional.empty();
            return Optional.of(new VerifyConfig(List.copyOf(commands),
                Math.min(positive(root.get("timeoutSeconds"), DEFAULT_TIMEOUT_SECONDS), MAX_TIMEOUT_SECONDS),
                positive(root.get("maxConsecutiveBlocks"), DEFAULT_MAX_BLOCKS)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static int positive(JsonNode n, int dflt) {
        return n != null && n.isIntegralNumber() && n.asInt() > 0 ? n.asInt() : dflt;
    }
}
