package dev.dorrian.agenticskillscli.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class BundleCatalogTest {

    @Test
    void itemsAreSortedPrefixedAndDeduplicated() {
        assertEquals(List.of("AGENT:pr-reviewer", "COMMAND:pr-check", "MCP_JAR:local-codegen", "SKILL:a", "SKILL:b"),
            BundleCatalog.items(List.of("b", "a", "a"), List.of("pr-reviewer"), List.of("pr-check"),
                List.of("local-codegen")));
    }

    @Test
    void emptyInputsGiveEmptyList() {
        assertEquals(List.of(), BundleCatalog.items(List.of(), List.of(), List.of(), List.of()));
    }
}
