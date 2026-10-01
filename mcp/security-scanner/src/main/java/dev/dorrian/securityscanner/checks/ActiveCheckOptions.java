package dev.dorrian.securityscanner.checks;

import java.util.List;

/**
 * Port of the TS {@code ActiveCheckOptions} interface. {@code observedJwt} is never populated by
 * any current caller — {@link ActiveChecks}'s JWT alg:none sub-check is preserved for parity but
 * is unreachable in practice, exactly as in the source.
 */
public record ActiveCheckOptions(List<ActiveCategory> categories, String observedJwt) {

    public static ActiveCheckOptions all() {
        return new ActiveCheckOptions(null, null);
    }

    public static ActiveCheckOptions of(List<ActiveCategory> categories) {
        return new ActiveCheckOptions(categories, null);
    }
}
