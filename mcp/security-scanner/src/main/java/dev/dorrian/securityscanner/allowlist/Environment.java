package dev.dorrian.securityscanner.allowlist;

/**
 * Allowed scan-target environments. Port of the TS {@code z.enum(['local','dev','staging','test'])}
 * restriction in allowlist.ts — deliberately has no {@code prod}/{@code production} member at all,
 * so a production value can never parse successfully; there is no override.
 */
public enum Environment {
    LOCAL("local"),
    DEV("dev"),
    STAGING("staging"),
    TEST("test");

    private final String json;

    Environment(String json) {
        this.json = json;
    }

    public String json() {
        return json;
    }

    /** @return the matching Environment, or null if raw is not one of the 4 valid values. */
    public static Environment fromJson(String raw) {
        if (raw == null) {
            return null;
        }
        for (Environment e : values()) {
            if (e.json.equals(raw)) {
                return e;
            }
        }
        return null;
    }
}
