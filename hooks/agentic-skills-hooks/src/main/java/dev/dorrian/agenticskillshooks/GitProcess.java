package dev.dorrian.agenticskillshooks;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs a git plumbing command against the JVM process's actual working directory. Best-effort:
 * never throws, returns null on any failure (no repo, no git, timeout, non-zero exit). Drains
 * stdout/stderr on background threads while waiting so a chatty command can't deadlock the pipe,
 * and enforces the timeout via a bounded waitFor + destroyForcibly, matching the 1500ms timeout
 * used by hooks/lib/event-log.ts's runGit.
 */
public final class GitProcess {
    private static final int DEFAULT_TIMEOUT_MS = 1500;

    private GitProcess() {
    }

    public static String runGit(String... args) {
        return runGit(DEFAULT_TIMEOUT_MS, args);
    }

    public static String runGit(int timeoutMs, String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            for (String a : args) cmd.add(a);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(System.getProperty("user.dir")));
            Process process = pb.start();
            process.getOutputStream().close();

            StringBuilder out = new StringBuilder();
            Thread stdoutReader = new Thread(() -> {
                try (var in = process.getInputStream()) {
                    out.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                }
            });
            stdoutReader.setDaemon(true);
            stdoutReader.start();

            Thread stderrDrain = new Thread(() -> {
                try (var err = process.getErrorStream()) {
                    err.readAllBytes();
                } catch (IOException ignored) {
                }
            });
            stderrDrain.setDaemon(true);
            stderrDrain.start();

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            stdoutReader.join(500);
            stderrDrain.join(500);
            if (process.exitValue() != 0) return null;

            String trimmed = out.toString().replaceAll("[\\r\\n]+$", "");
            return trimmed.isEmpty() ? null : trimmed;
        } catch (Exception e) {
            return null;
        }
    }
}
