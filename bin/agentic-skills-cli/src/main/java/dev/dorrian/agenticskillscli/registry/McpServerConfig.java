package dev.dorrian.agenticskillscli.registry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small builder for the loosely-typed per-tool MCP server config objects
 * that {@code bin/install.js} represents as plain JS object literals (shape
 * varies by tool: opencode uses {@code command} as an array plus {@code
 * environment}, most others use {@code command}+{@code args}+{@code env},
 * zed sometimes nests {@code command} as an object). A {@code
 * LinkedHashMap<String, Object>} preserves key order for deterministic JSON
 * output and is flexible enough to hold any of those shapes without a
 * separate record type per variant.
 */
public final class McpServerConfig {

    private McpServerConfig() {
    }

    public static Map<String, Object> of(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("Expected an even number of key/value arguments");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    public static List<String> list(String... items) {
        return List.of(items);
    }
}
