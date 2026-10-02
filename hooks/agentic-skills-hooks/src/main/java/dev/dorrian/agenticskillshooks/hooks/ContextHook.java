package dev.dorrian.agenticskillshooks.hooks;

import dev.dorrian.agenticskillshooks.GitProcess;
import dev.dorrian.agenticskillshooks.HookInput;
import dev.dorrian.usagestore.SecretRedactor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Opt-in {@code SessionStart} context injector (including after compaction): the text it returns is
 * printed to stdout, which Claude Code adds to the session context. Always exit 0; any failure
 * yields no output. Nothing is recorded.
 */
public final class ContextHook {

    static final int MAX_CHARS = 4000;
    static final int MAX_NOTES_CHARS = 2500;

    private ContextHook() {
    }

    /** Returns the context to inject, or empty for none. */
    public static Optional<String> contextFor(HookInput input) {
        try {
            String cwd = input.cwd() != null && !input.cwd().isBlank() ? input.cwd() : System.getProperty("user.dir");
            Path project = Path.of(cwd).toAbsolutePath();
            String branch = GitProcess.runGit("-C", cwd, "rev-parse", "--abbrev-ref", "HEAD");
            String subject = GitProcess.runGit("-C", cwd, "log", "-1", "--format=%s");
            String status = GitProcess.runGit("-C", cwd, "status", "--porcelain");
            return Optional.of(build(project, branch, subject, status, readNotes(project), input.source()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static String build(Path project, String branch, String subject, String porcelain, String notes, String source) {
        StringBuilder sb = new StringBuilder("Project context\n");
        Path name = project.getFileName();
        sb.append("Project: ").append(name == null ? project : name).append('\n');
        if (branch != null) sb.append("Git branch: ").append(branch).append('\n');
        if (subject != null) sb.append("Last commit: ").append(subject).append('\n');
        if (branch != null) {
            long changes = porcelain == null ? 0 : porcelain.lines().filter(l -> !l.isBlank()).count();
            sb.append("Uncommitted changes: ").append(changes).append('\n');
        }
        if ("compact".equals(source)) {
            sb.append("The conversation was just compacted; re-read the context and handoff notes below "
                + "(and any handoff file) before continuing.\n");
        }
        if (notes != null && !notes.isBlank()) {
            sb.append("\nNotes (.agentic-skills/context.md):\n").append(notes.strip()).append('\n');
        }
        String text = SecretRedactor.redact(sb.toString().stripTrailing());
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
    }

    private static String readNotes(Path project) {
        try {
            Path file = project.resolve(".agentic-skills").resolve("context.md");
            if (!Files.isRegularFile(file)) return null;
            String s = Files.readString(file);
            return s.length() > MAX_NOTES_CHARS ? s.substring(0, MAX_NOTES_CHARS) + "\n[truncated]" : s;
        } catch (Exception e) {
            return null;
        }
    }
}
