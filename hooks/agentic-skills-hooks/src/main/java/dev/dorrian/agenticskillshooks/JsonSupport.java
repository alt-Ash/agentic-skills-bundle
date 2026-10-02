package dev.dorrian.agenticskillshooks;

import dev.dorrian.usagestore.UsageEvent;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Shared Jackson mapper (field-based, so UsageEvent/SessionBaseline need no getters) plus JSONL reading. */
public final class JsonSupport {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, Visibility.ANY)
            .setVisibility(PropertyAccessor.GETTER, Visibility.NONE)
            .setVisibility(PropertyAccessor.IS_GETTER, Visibility.NONE)
            .setVisibility(PropertyAccessor.SETTER, Visibility.NONE);

    private JsonSupport() {
    }

    public static List<JsonNode> readJsonlLines(String path) throws Exception {
        String content = Files.readString(Path.of(path));
        List<JsonNode> records = new ArrayList<>();
        for (String line : content.strip().split("\n")) {
            if (line.isBlank()) continue;
            try {
                records.add(MAPPER.readTree(line));
            } catch (Exception ignored) {
                // skip malformed lines
            }
        }
        return records;
    }
}
