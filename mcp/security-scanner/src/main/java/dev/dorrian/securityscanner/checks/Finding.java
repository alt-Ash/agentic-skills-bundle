package dev.dorrian.securityscanner.checks;

/** Port of the TS {@code Finding} interface shared by passive.ts and active.ts. */
public record Finding(
    String id,
    String category,
    Severity severity,
    String description,
    String evidence,
    String remediation
) {
}
