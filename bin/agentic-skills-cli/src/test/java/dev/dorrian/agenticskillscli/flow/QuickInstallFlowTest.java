package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.install.CommandDescriptor;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuickInstallFlowTest {

    @Test
    void dedupedCommandsPrefersSkillCommandsOnNameCollision() {
        CommandDescriptor skillCmd = new CommandDescriptor("shared", Paths.get("/skill/shared.md"));
        CommandDescriptor agentCmd = new CommandDescriptor("shared", Paths.get("/agent/shared.md"));
        CommandDescriptor agentOnly = new CommandDescriptor("agent-only", Paths.get("/agent/agent-only.md"));

        List<CommandDescriptor> result = QuickInstallFlow.dedupedCommands(
            List.of(skillCmd), List.of(agentCmd, agentOnly)
        );

        assertEquals(2, result.size());
        assertEquals(Paths.get("/skill/shared.md"), result.get(0).srcFile());
        assertEquals("agent-only", result.get(1).name());
    }

    @Test
    void dedupedCommandsHandlesNoOverlap() {
        CommandDescriptor a = new CommandDescriptor("a", Paths.get("/a.md"));
        CommandDescriptor b = new CommandDescriptor("b", Paths.get("/b.md"));
        List<CommandDescriptor> result = QuickInstallFlow.dedupedCommands(List.of(a), List.of(b));
        assertEquals(List.of(a, b), result);
    }
}
