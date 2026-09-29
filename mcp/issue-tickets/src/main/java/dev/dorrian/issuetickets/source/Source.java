package dev.dorrian.issuetickets.source;

/**
 * Ticket source. Lowercase constant names (against normal Java convention) so Spring AI's tool
 * JSON-schema generator (which renders enums from {@code Enum.name()} — see
 * {@code dev.dorrian.securityscanner.checks.ActiveCategory}'s javadoc for the same issue)
 * advertises {@code azure}/{@code github}, matching the source's actual wire values.
 */
public enum Source {
    azure,
    github
}
