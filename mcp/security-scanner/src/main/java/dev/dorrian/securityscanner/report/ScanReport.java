package dev.dorrian.securityscanner.report;

import dev.dorrian.securityscanner.checks.Finding;
import java.util.List;

/**
 * Port of the TS {@code ScanReport} interface. {@code schema} is always the literal
 * {@code "url-scan/v1"}; {@code authorization} is only ever set for {@code mode=active}.
 */
public record ScanReport(
    String schema,
    String target,
    String environment,
    ScanMode mode,
    String startedAt,
    String finishedAt,
    ScanStatus status,
    String authorization,
    int requestsIssued,
    List<Finding> findings
) {

    public static final String SCHEMA = "url-scan/v1";
}
