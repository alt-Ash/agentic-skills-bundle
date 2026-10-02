package dev.dorrian.agenticskillshooks.hooks;

import dev.dorrian.agenticskillshooks.HookInput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardHookTest {

    private static boolean denies(String tool, String key, String value) {
        String json = "{\"tool_name\":\"" + tool + "\",\"tool_input\":{\"" + key + "\":\""
            + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}}";
        return GuardHook.evaluate(HookInput.parse(json)).isPresent();
    }

    private static boolean bashDenies(String command) {
        return denies("Bash", "command", command);
    }

    @Test
    void deniesDestructiveRecursiveDeletes() {
        assertTrue(bashDenies("rm -rf /"));
        assertTrue(bashDenies("rm -rf ~"));
        assertTrue(bashDenies("rm -fr $HOME"));
        assertTrue(bashDenies("rm -rf *"));
    }

    @Test
    void allowsScopedDeletes() {
        assertFalse(bashDenies("rm -rf target"));
        assertFalse(bashDenies("rm -rf ./build/classes"));
        assertFalse(bashDenies("rm file.txt"));
    }

    @Test
    void deniesForcePushToMainOrMasterOnly() {
        assertTrue(bashDenies("git push --force origin main"));
        assertTrue(bashDenies("git push -f origin master"));
        assertFalse(bashDenies("git push --force-with-lease origin main"));
        assertFalse(bashDenies("git push --force origin feature/x"));
        assertFalse(bashDenies("git push origin main"));
    }

    @Test
    void deniesReadingEnvFilesButNotTemplates() {
        assertTrue(bashDenies("cat .env"));
        assertTrue(bashDenies("grep KEY .env.production"));
        assertFalse(bashDenies("cat .env.example"));
        assertFalse(bashDenies("echo hello"));
    }

    @Test
    void deniesFileToolsOnSecretFiles() {
        assertTrue(denies("Read", "file_path", "/proj/.env"));
        assertTrue(denies("Edit", "file_path", "/proj/.env.local"));
        assertTrue(denies("Write", "file_path", "/home/u/.ssh/id_rsa"));
        assertTrue(denies("Read", "file_path", "/proj/certs/server.pem"));
        assertFalse(denies("Read", "file_path", "/proj/.env.example"));
        assertFalse(denies("Read", "file_path", "/proj/src/Main.java"));
    }

    @Test
    void allowsUnknownToolsAndMalformedInput() {
        assertFalse(denies("Glob", "pattern", "**/.env"));
        assertFalse(GuardHook.evaluate(HookInput.parse("not json")).isPresent());
        assertFalse(GuardHook.evaluate(HookInput.parse("{\"tool_name\":\"Bash\"}")).isPresent());
    }
}
