package dev.dorrian.securityscanner.checks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Finding severity. Port of the TS {@code Finding['severity']} union. */
public enum Severity {
    CRITICAL("Critical"),
    HIGH("High"),
    MODERATE("Moderate"),
    LOW("Low");

    private final String json;

    Severity(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }

    @JsonCreator
    public static Severity fromJson(String raw) {
        for (Severity s : values()) {
            if (s.json.equals(raw)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown severity: " + raw);
    }
}
