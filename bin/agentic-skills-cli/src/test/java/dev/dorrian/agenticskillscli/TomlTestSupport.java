package dev.dorrian.agenticskillscli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Parses TOML the installer emits with a real TOML parser (test scope only) — proves validity. */
public final class TomlTestSupport {

    private static final TomlMapper TOML = new TomlMapper();

    private TomlTestSupport() {
    }

    public static Map<String, Object> parse(String toml) {
        try {
            return TOML.readValue(toml, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("Invalid TOML:\n" + toml, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> table(Map<String, Object> parsed, String... path) {
        Map<String, Object> cur = parsed;
        for (String p : path) {
            cur = (Map<String, Object>) cur.get(p);
            if (cur == null) return null;
        }
        return cur;
    }
}
