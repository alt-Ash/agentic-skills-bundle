package dev.dorrian.securityscanner.allowlist;

import java.util.List;

/** Port of TS's {@code AllowlistSchema} — a plain list of targets, possibly empty. */
public record Allowlist(List<AllowlistTarget> targets) {
}
