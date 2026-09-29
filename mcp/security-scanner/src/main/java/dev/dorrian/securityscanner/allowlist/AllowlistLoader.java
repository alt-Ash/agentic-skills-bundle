package dev.dorrian.securityscanner.allowlist;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Loads and checks the project-local scan-target allowlist. Port of allowlist.ts
 * (resolveAllowlistPath/loadAllowlist/checkTarget/authorizeTarget) — fails closed on every
 * error path: a missing file, invalid JSON, or a single bad entry anywhere in the {@code
 * targets} array invalidates the <b>whole file</b>, not just that entry (locked in by the
 * source's own test: "rejects a config with one bad entry rather than silently dropping it").
 */
@Component
public class AllowlistLoader {

    private final ObjectMapper mapper = new ObjectMapper();

    public record LoadResult(boolean ok, Allowlist allowlist, String error) {
        static LoadResult ok(Allowlist allowlist) {
            return new LoadResult(true, allowlist, null);
        }

        static LoadResult fail(String error) {
            return new LoadResult(false, null, error);
        }
    }

    /**
     * {@code SCANNER_ALLOWLIST_PATH} wins unconditionally if set (resolved against cwd if
     * relative); otherwise {@code <cwd>/.security-scanner/allowlist.json}. No other override.
     */
    public Path resolveAllowlistPath() {
        String override = System.getenv("SCANNER_ALLOWLIST_PATH");
        Path cwd = Path.of(System.getProperty("user.dir"));
        if (override != null && !override.isEmpty()) {
            return cwd.resolve(override).normalize().toAbsolutePath();
        }
        return cwd.resolve(".security-scanner").resolve("allowlist.json").normalize().toAbsolutePath();
    }

    public LoadResult load() {
        Path path = resolveAllowlistPath();

        String raw;
        try {
            raw = Files.readString(path);
        } catch (IOException e) {
            return LoadResult.fail("No allowlist config found at " + path + ". This tool refuses "
                + "to scan anything until an explicit allowlist is created. Set SCANNER_ALLOWLIST_PATH "
                + "or create .security-scanner/allowlist.json — see README.md for the format.");
        }

        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (JsonProcessingException e) {
            return LoadResult.fail("Allowlist config at " + path + " is not valid JSON: " + e.getOriginalMessage());
        }

        List<String> issues = new ArrayList<>();
        boolean environmentIssue = false;
        List<AllowlistTarget> targets = new ArrayList<>();

        JsonNode targetsNode = root.get("targets");
        if (targetsNode == null || !targetsNode.isArray()) {
            issues.add("targets: required array");
        } else {
            for (int i = 0; i < targetsNode.size(); i++) {
                JsonNode entry = targetsNode.get(i);
                JsonNode hostNode = entry.get("host");
                JsonNode envNode = entry.get("environment");

                String host = hostNode != null && hostNode.isTextual() ? hostNode.asText() : null;
                if (host == null || host.isEmpty()) {
                    issues.add("targets[" + i + "].host: required non-empty string");
                }

                String envRaw = envNode != null && envNode.isTextual() ? envNode.asText() : null;
                Environment environment = Environment.fromJson(envRaw);
                if (environment == null) {
                    issues.add("targets[" + i + "].environment: must be one of local|dev|staging|test");
                    environmentIssue = true;
                }

                if (host != null && !host.isEmpty() && environment != null) {
                    targets.add(new AllowlistTarget(host, environment));
                }
            }
        }

        if (!issues.isEmpty()) {
            // Mirrors the source's zod-issue-path check: ANY invalid/missing environment value
            // (not specifically "prod") triggers this suffix, since the enum itself has no prod
            // member to special-case against.
            String suffix = environmentIssue
                ? " — this tool refuses to scan production targets under any configuration; "
                    + "'environment' must be one of local|dev|staging|test"
                : "";
            return LoadResult.fail("Allowlist config at " + path + " is invalid: "
                + String.join("; ", issues) + suffix);
        }

        return LoadResult.ok(new Allowlist(List.copyOf(targets)));
    }

    /** Exact match only, case-insensitive, trimmed — no wildcard/subdomain matching. */
    public TargetCheckResult checkTarget(String host, Allowlist allowlist) {
        String needle = host.trim().toLowerCase(Locale.ROOT);
        for (AllowlistTarget target : allowlist.targets()) {
            if (target.host().trim().toLowerCase(Locale.ROOT).equals(needle)) {
                return TargetCheckResult.allowed(target.environment());
            }
        }
        return TargetCheckResult.notAllowed("'" + host + "' is not in the allowlist. Add it to "
            + ".security-scanner/allowlist.json with an explicit environment "
            + "(local|dev|staging|test) before scanning it.");
    }

    /** Never throws — always returns a structured result (configError or allowed/reason). */
    public TargetCheckResult authorizeTarget(String host) {
        LoadResult loaded = load();
        if (!loaded.ok()) {
            return TargetCheckResult.configError(loaded.error());
        }
        return checkTarget(host, loaded.allowlist());
    }
}
