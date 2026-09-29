package dev.dorrian.securityscanner.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dorrian.securityscanner.checks.Finding;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Persists and retrieves scan reports. Port of report.ts. Reports always live under
 * {@code <cwd>/security-scans/} — unlike the allowlist path, this is never configurable via an
 * env var in the source, so that's preserved exactly.
 */
@Component
public class ReportWriter {

    private final ObjectMapper mapper = new ObjectMapper();

    public record WriteResult(String scanId, Path jsonPath, Path mdPath) {
    }

    Path reportsDir() {
        return Path.of(System.getProperty("user.dir"), "security-scans");
    }

    private static int severityRank(dev.dorrian.securityscanner.checks.Severity severity) {
        return switch (severity) {
            case CRITICAL -> 0;
            case HIGH -> 1;
            case MODERATE -> 2;
            case LOW -> 3;
        };
    }

    /** Port of {@code scanIdFor(target, startedAt)}. */
    static String scanIdFor(String target, String startedAt) {
        String safeHost = target.replaceAll("(?i)[^a-z0-9.-]", "_");
        String safeTime = startedAt.replaceAll("[^0-9]", "");
        return safeHost + "-" + safeTime;
    }

    /** Port of {@code renderMarkdown(report)} — exact section structure and ordering. */
    static String renderMarkdown(ScanReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# URL Security Scan — `").append(report.target()).append("`\n\n");
        sb.append("**Environment:** ").append(report.environment())
            .append("  **Mode:** ").append(report.mode().json())
            .append("  **Status:** ").append(report.status().json()).append('\n');
        sb.append("**Started:** ").append(report.startedAt())
            .append("  **Finished:** ").append(report.finishedAt())
            .append("  **Requests issued:** ").append(report.requestsIssued()).append('\n');
        if (report.authorization() != null && !report.authorization().isEmpty()) {
            sb.append("**Authorization:** ").append(report.authorization()).append('\n');
        }
        sb.append("\n## Findings (").append(report.findings().size()).append(")\n\n");

        if (report.findings().isEmpty()) {
            sb.append("No findings.\n");
            return sb.toString();
        }

        List<Finding> sorted = new ArrayList<>(report.findings());
        sorted.sort(Comparator.comparingInt(f -> severityRank(f.severity())));

        sb.append("| ID | Severity | OWASP | Description |\n");
        sb.append("|---|---|---|---|\n");
        for (Finding f : sorted) {
            sb.append("| ").append(f.id()).append(" | ").append(f.severity().json())
                .append(" | ").append(f.category()).append(" | ").append(f.description()).append(" |\n");
        }
        sb.append('\n');
        for (Finding f : sorted) {
            sb.append("### [").append(f.severity().json()).append("] ").append(f.id())
                .append(" — ").append(f.description()).append("\n");
            sb.append("**OWASP:** ").append(f.category()).append('\n');
            sb.append("**Evidence:** ").append(f.evidence()).append('\n');
            sb.append("**Remediation:** ").append(f.remediation()).append("\n\n");
        }
        return sb.toString();
    }

    public WriteResult writeReport(ScanReport report) {
        Path dir = reportsDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        String scanId = scanIdFor(report.target(), report.startedAt());
        Path jsonPath = dir.resolve(scanId + ".json");
        Path mdPath = dir.resolve(scanId + ".md");

        try {
            Files.writeString(jsonPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            Files.writeString(mdPath, renderMarkdown(report));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return new WriteResult(scanId, jsonPath, mdPath);
    }

    /** Returns null on any failure (missing file or corrupt JSON) — matches the source exactly. */
    public ScanReport getScanReport(String scanId) {
        Path jsonPath = reportsDir().resolve(scanId + ".json");
        try {
            return mapper.readValue(jsonPath.toFile(), ScanReport.class);
        } catch (IOException e) {
            return null;
        }
    }

    /** Port of {@code listScanReports()} — not wired to any tool in the source; kept for parity. */
    public List<String> listScanReports() {
        Path dir = reportsDir();
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                .map(p -> p.getFileName().toString())
                .filter(name -> name.endsWith(".json"))
                .map(name -> name.substring(0, name.length() - ".json".length()))
                .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
