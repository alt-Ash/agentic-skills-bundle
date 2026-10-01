package dev.dorrian.securityscanner.report;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Port of the TS {@code ScanStatus} union (defined in http-client.ts, used by report.ts). */
public enum ScanStatus {
    COMPLETED("completed"),
    ABORTED_INSTABILITY("aborted_instability"),
    REFUSED("refused");

    private final String json;

    ScanStatus(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }

    @JsonCreator
    public static ScanStatus fromJson(String raw) {
        for (ScanStatus s : values()) {
            if (s.json.equals(raw)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown scan status: " + raw);
    }
}
