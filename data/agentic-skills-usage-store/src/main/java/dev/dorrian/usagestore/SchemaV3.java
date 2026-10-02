package dev.dorrian.usagestore;

import java.util.List;

/**
 * Schema version 3: whether a prompt followed an interrupted turn (Claude Code fires no Stop hook for an
 * interrupt, so it is recorded on the next user_prompt). Purely additive.
 */
final class SchemaV3 {

    private SchemaV3() {
    }

    static final List<String> STATEMENTS = List.of(
        "ALTER TABLE events ADD COLUMN is_interrupt INTEGER"
    );
}
