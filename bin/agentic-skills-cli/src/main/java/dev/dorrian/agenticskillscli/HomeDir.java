package dev.dorrian.agenticskillscli;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Centralized resolution of the effective "home directory" that every
 * per-tool config path, MCP install dir, and shell-profile lookup is
 * built from. Exists solely so tests and an end-to-end smoke test can
 * redirect all of those paths at a scratch directory via {@link
 * #OVERRIDE_ENV_VAR} instead of mutating the real {@code $HOME} — this is
 * not a general user-facing feature, and the override has no effect on
 * the actual {@code claude} CLI binary invoked by {@code
 * config.ClaudeCliMcpRegistrar}, which manages its own config storage
 * internally.
 */
public final class HomeDir {

    public static final String OVERRIDE_ENV_VAR = "AGENTIC_SKILLS_HOME_OVERRIDE";

    private HomeDir() {
    }

    public static Path resolve() {
        return resolve(System.getenv(), System.getProperty("user.home"));
    }

    public static Path resolve(Map<String, String> env, String realUserHome) {
        String override = env.get(OVERRIDE_ENV_VAR);
        return Paths.get((override != null && !override.isBlank()) ? override : realUserHome);
    }
}
