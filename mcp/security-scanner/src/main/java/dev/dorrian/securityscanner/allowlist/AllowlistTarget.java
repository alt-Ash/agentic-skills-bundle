package dev.dorrian.securityscanner.allowlist;

/** One entry in {@code .security-scanner/allowlist.json}. Port of TS's {@code TargetSchema}. */
public record AllowlistTarget(String host, Environment environment) {
}
