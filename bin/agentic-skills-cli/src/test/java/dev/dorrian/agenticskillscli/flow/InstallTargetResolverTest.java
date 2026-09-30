package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.registry.AgentToolDef;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallTargetResolverTest {

    private static final Path PROJECT_PATH = Paths.get("/tmp/my-project");

    private static AgentToolDef tool(Path globalPath, String projectFolder, Path commandsGlobal, String commandsProjectFolder,
                                      Path agentsGlobal, String agentsProjectFolder) {
        return new AgentToolDef(
            "t", "Tool",
            globalPath, projectFolder,
            commandsGlobal, commandsProjectFolder,
            agentsGlobal, agentsProjectFolder,
            null, null,
            true, true,
            null
        );
    }

    @Test
    void skillsPathGlobalUsesToolGlobalPath() {
        AgentToolDef t = tool(Paths.get("/home/.tool/skills"), ".tool/skills", null, null, null, null);
        assertEquals(Paths.get("/home/.tool/skills"), InstallTargetResolver.skillsPath(t, "global", PROJECT_PATH));
    }

    @Test
    void skillsPathProjectJoinsProjectFolder() {
        AgentToolDef t = tool(Paths.get("/home/.tool/skills"), ".tool/skills", null, null, null, null);
        assertEquals(PROJECT_PATH.resolve(".tool/skills"), InstallTargetResolver.skillsPath(t, "project", PROJECT_PATH));
    }

    @Test
    void commandsInstallGlobalWhenAgentCommandsPresent() {
        assertTrue(InstallTargetResolver.commandsInstallGlobal(true, "project", false));
    }

    @Test
    void commandsInstallGlobalWhenSkillsTargetIsGlobal() {
        assertTrue(InstallTargetResolver.commandsInstallGlobal(false, "global", false));
    }

    @Test
    void commandsInstallGlobalWhenNoSkillsSelected() {
        assertTrue(InstallTargetResolver.commandsInstallGlobal(false, "project", true));
    }

    @Test
    void commandsInstallProjectWhenNoneOfTheAboveApply() {
        assertFalse(InstallTargetResolver.commandsInstallGlobal(false, "project", false));
    }

    @Test
    void agentsPathGlobalUsesToolAgentsGlobalPath() {
        AgentToolDef t = tool(null, null, null, null, Paths.get("/home/.tool/agents"), ".tool/agents");
        assertEquals(Paths.get("/home/.tool/agents"), InstallTargetResolver.agentsPath(t, "global", PROJECT_PATH));
    }

    @Test
    void agentsPathProjectUsesToolProjectFolder() {
        AgentToolDef t = tool(null, null, null, null, Paths.get("/home/.tool/agents"), ".tool/agents");
        assertEquals(PROJECT_PATH.resolve(".tool/agents"), InstallTargetResolver.agentsPath(t, "project", PROJECT_PATH));
    }

    @Test
    void agentsPathProjectFallsBackToAgentsWhenNoProjectFolderDefined() {
        AgentToolDef t = tool(null, null, null, null, Paths.get("/home/.tool/agents"), null);
        assertEquals(PROJECT_PATH.resolve("agents"), InstallTargetResolver.agentsPath(t, "project", PROJECT_PATH));
    }
}
