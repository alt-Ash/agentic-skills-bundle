package dev.dorrian.agenticskillscli.install;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CommandCatalogTest {

    private static CommandDescriptor c(String name, String dir) {
        return new CommandDescriptor(name, Path.of(dir, name + ".md"));
    }

    @Test
    void skillCommandWinsOnCollision() {
        CommandDescriptor skill = c("go", "skill");
        CommandDescriptor agent = c("go", "agent");
        List<CommandDescriptor> out = CommandCatalog.dedupe(List.of(skill), List.of(agent, c("other", "agent")));
        assertEquals(2, out.size());
        assertSame(skill, out.get(0));
        assertEquals("other", out.get(1).name());
    }

    @Test
    void noOverlapKeepsAllInOrder() {
        List<CommandDescriptor> out = CommandCatalog.dedupe(List.of(c("a", "s"), c("b", "s")), List.of(c("c", "a")));
        assertEquals(List.of("a", "b", "c"), out.stream().map(CommandDescriptor::name).toList());
    }
}
