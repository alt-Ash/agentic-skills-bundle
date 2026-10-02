package dev.dorrian.agenticskillscli.data;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dorrian.usagestore.SecretRedactor;
import dev.dorrian.usagestore.UsageDb;
import dev.dorrian.usagestore.UsageEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * One-way import of the old per-project {@code ai-usage-events.json} (the flat event list) into the
 * usage database. {@code hooks-events.json} and {@code .hooks-data/*} are copies of the same events,
 * so they are ignored. Read-only on the source; idempotent because rows are keyed on {@code event_id}
 * (a deterministic one is derived for events that predate ids). Failure text is redacted and
 * capped as it is imported, since older files kept it raw.
 */
public final class LegacyJsonImporter {

    public static final String FILE_NAME = "ai-usage-events.json";
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private LegacyJsonImporter() {
    }

    /** Counts for one import run. */
    public record Result(int imported, int duplicates, int skipped) {
        public Result plus(Result o) {
            return new Result(imported + o.imported, duplicates + o.duplicates, skipped + o.skipped);
        }
    }

    /** Imports {@code <dir>/ai-usage-events.json}; a missing file is not an error (zero counts). */
    public static Result importDirectory(UsageDb db, Path dir) throws IOException {
        Path file = dir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return new Result(0, 0, 0);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IOException("Not valid JSON: " + file, e);
        }
        if (root == null || !root.isArray()) {
            throw new IOException("Expected a JSON array of events: " + file);
        }

        String cwd = dir.toAbsolutePath().normalize().toString();
        int imported = 0;
        int duplicates = 0;
        int skipped = 0;
        for (int i = 0; i < root.size(); i++) {
            UsageEvent event;
            try {
                event = MAPPER.treeToValue(root.get(i), UsageEvent.class);
            } catch (IOException e) {
                skipped++;
                continue;
            }
            if (event == null || event.ts == null || event.event == null) {
                skipped++;
                continue;
            }
            if (event.eventId == null) {
                String key = cwd + "|" + i + "|" + event.ts + "|" + event.event + "|" + event.sessionId;
                event.eventId = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
            }
            // Old files stored tool-failure text unredacted and uncapped; sanitize it on the way in.
            event.error = SecretRedactor.redactError(event.error);
            if (event.cwd == null) {
                event.cwd = cwd;
            }
            if (db.record(event)) imported++; else duplicates++;
        }
        return new Result(imported, duplicates, skipped);
    }
}
