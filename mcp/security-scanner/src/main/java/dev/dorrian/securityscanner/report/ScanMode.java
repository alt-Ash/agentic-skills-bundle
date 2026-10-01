package dev.dorrian.securityscanner.report;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Port of the TS {@code ScanReport['mode']} union. */
public enum ScanMode {
    PASSIVE("passive"),
    ACTIVE("active");

    private final String json;

    ScanMode(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }

    @JsonCreator
    public static ScanMode fromJson(String raw) {
        for (ScanMode m : values()) {
            if (m.json.equals(raw)) {
                return m;
            }
        }
        throw new IllegalArgumentException("Unknown scan mode: " + raw);
    }
}
