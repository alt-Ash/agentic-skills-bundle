package dev.dorrian.securityscanner.checks;

/**
 * Port of the TS {@code ActiveCategory} union.
 *
 * <p>Constants are deliberately lowercase/snake_case (against normal Java enum convention)
 * rather than the more idiomatic {@code XSS}, {@code OPEN_REDIRECT}, etc: Spring AI's tool
 * JSON-schema generator renders enum-typed parameters from {@code Enum.name()} directly and
 * does not honor {@code @JsonProperty}/{@code @JsonValue} overrides, so the generated schema —
 * and therefore what a real MCP client sends on the wire — must match the source's actual
 * lowercase values (xss, open_redirect, …) exactly, or every scan_active call specifying
 * categories would silently fail to match the source's API contract.
 */
public enum ActiveCategory {
    xss,
    injection,
    open_redirect,
    path_traversal,
    auth_bypass,
    ssrf
}
