package dev.dorrian.usagestore;

import java.util.List;

/**
 * Schema version 2: real per-message token usage (output and the cache read/creation split that
 * version 1 only had as one sum) and skill/agent attribution for usage tracking. Purely additive.
 */
final class SchemaV2 {

    private SchemaV2() {
    }

    static final List<String> STATEMENTS = List.of(
        "ALTER TABLE events ADD COLUMN output_tokens INTEGER",
        "ALTER TABLE events ADD COLUMN cache_read_tokens INTEGER",
        "ALTER TABLE events ADD COLUMN cache_creation_tokens INTEGER",
        "ALTER TABLE events ADD COLUMN agent_name TEXT",
        "ALTER TABLE events ADD COLUMN skill_name TEXT",
        "CREATE INDEX IF NOT EXISTS idx_events_skill ON events(skill_name)",
        "CREATE INDEX IF NOT EXISTS idx_events_agent ON events(agent_name)"
    );
}
