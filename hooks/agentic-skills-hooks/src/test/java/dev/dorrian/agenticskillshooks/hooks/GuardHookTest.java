package dev.dorrian.agenticskillshooks.hooks;

import dev.dorrian.agenticskillshooks.HookInput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardHookTest {

    private static boolean denies(String tool, String key, String value) {
        String json = "{\"tool_name\":\"" + tool + "\",\"tool_input\":{\"" + key + "\":\""
            + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}}";
        return GuardHook.evaluate(HookInput.parse(json), GuardConfig.defaults()).isPresent();
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
        assertFalse(GuardHook.evaluate(HookInput.parse("not json"), GuardConfig.defaults()).isPresent());
        assertFalse(GuardHook.evaluate(HookInput.parse("{\"tool_name\":\"Bash\"}"), GuardConfig.defaults()).isPresent());
    }

    // ── hardened-rule battery (defaults only, independent of any real guard.json) ──

    private static boolean denied(String tool, String key, String value) {
        String json = "{\"tool_name\":\"" + tool + "\",\"tool_input\":{\"" + key + "\":\""
            + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}}";
        return GuardHook.evaluate(HookInput.parse(json), GuardConfig.defaults()).isPresent();
    }

    private static boolean bashDenied(String c) {
        return denied("Bash", "command", c);
    }

    @Test
    void recursiveDeleteDecisions() {
        // allowed: absolute path below root is scoped work, not a root delete
        assertFalse(bashDenied("rm -rf /tmp/x"));
        assertFalse(bashDenied("rm -rf ./build"));
        // allowed: a quoted path below $HOME is scoped
        assertFalse(bashDenied("rm -rf \"$HOME/.cache/x\""));
        assertFalse(bashDenied("rm -rf ~/.cache"));
        // allowed: *.o is a narrow glob, only a bare * is a wildcard wipe
        assertFalse(bashDenied("rm -rf *.o"));
        // denied (was a false negative): long option before the root path
        assertTrue(bashDenied("rm -rf --no-preserve-root /"));
        // denied (was a false negative): quoted root/home
        assertTrue(bashDenied("rm -rf \"$HOME\""));
        assertTrue(bashDenied("rm -rf '/'"));
        assertTrue(bashDenied("rm -rf ${HOME}/"));
        // denied: dangerous target after other targets, and after --
        assertTrue(bashDenied("rm -rf build /"));
        assertTrue(bashDenied("rm -rf -- /"));
        assertTrue(bashDenied("rm -r -f /*"));
        assertTrue(bashDenied("sudo rm --recursive --force ~/"));
        // allowed: a later, separate command must not leak into the rm
        assertFalse(bashDenied("rm -rf build && ls /"));
        // allowed: not rm itself
        assertFalse(bashDenied("docker run --rm -it alpine ls /"));
    }

    @Test
    void forcePushDecisions() {
        // allowed: lease is the safe force
        assertFalse(bashDenied("git push --force-with-lease origin main"));
        // denied (was a false negative): + refspec forces without a flag
        assertTrue(bashDenied("git push origin +main"));
        assertTrue(bashDenied("git push origin +HEAD:master"));
        // denied: short flag with a refspec
        assertTrue(bashDenied("git push -f origin HEAD:main"));
        assertTrue(bashDenied("git push origin HEAD:refs/heads/main --force"));
        // denied: git -C dir push
        assertTrue(bashDenied("git -C /repo push -f origin main"));
        // allowed (was a false positive): a branch merely ending in /main
        assertFalse(bashDenied("git push --force origin feature/main"));
        assertFalse(bashDenied("git push -f origin main-fix"));
        // allowed: plain push, force on a feature branch
        assertFalse(bashDenied("git push origin main"));
        assertFalse(bashDenied("git push origin +feature"));
        // allowed: force flag belongs to a different command
        assertFalse(bashDenied("git push origin feature && echo -f main"));
    }

    @Test
    void envReadDecisions() {
        // allowed: templates are meant to be committed
        assertFalse(bashDenied("cat .env.example"));
        assertFalse(bashDenied("cat .env.local.sample"));
        // denied: nested path to a real env file
        assertTrue(bashDenied("cat ./config/.env.local"));
        assertTrue(bashDenied("cat \"$PWD/.env\""));
        // denied (was a false negative): other readers
        assertTrue(bashDenied("source .env"));
        assertTrue(bashDenied("sed -n 1p .env"));
        // allowed: writing is not reading, and the rule claims "reading"; the Write/Edit tools are guarded separately
        assertFalse(bashDenied("echo hi > .env"));
        // allowed: escaped pattern is a search for the text, not the file
        assertFalse(bashDenied("grep \"\\.env\" .gitignore"));
        // allowed: .envrc and .environment are not .env
        assertFalse(bashDenied("cat .envrc"));
        assertFalse(bashDenied("cat .environment"));
        // known conservative case: cp from the template into .env still names .env, so it is denied
        assertTrue(bashDenied("cp .env.example .env"));
    }

    @Test
    void secretPathDecisions() {
        // allowed: example variants, including compound ones (was a false positive for .env.local.example)
        assertFalse(denied("Read", "file_path", "/p/.env.local.example"));
        assertFalse(denied("Read", "file_path", "/p/.env.dist"));
        // denied: more private key names and case-insensitive match
        assertTrue(denied("Read", "file_path", "/home/u/.ssh/id_ecdsa"));
        assertTrue(denied("Read", "file_path", "C:\\proj\\.ENV"));
        // allowed: public key and look-alike names
        assertFalse(denied("Read", "file_path", "/home/u/.ssh/id_rsa.pub"));
        assertFalse(denied("Read", "file_path", "/p/src/environment.ts"));
        // denied: notebook_path is honoured when file_path is absent
        assertTrue(denied("NotebookEdit", "notebook_path", "/p/.env"));
    }

    @Test
    void hugeInputStaysFastAndKeepsHeadAndTail() {
        String padding = "echo x ".repeat(100_000);
        long start = System.nanoTime();
        assertTrue(bashDenied(padding + "; rm -rf /"));
        assertTrue(bashDenied("rm -rf / ; " + padding));
        assertFalse(bashDenied(padding));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000, "guard must stay fast on huge commands");
    }
}
