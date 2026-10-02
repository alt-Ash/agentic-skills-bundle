package dev.dorrian.agenticskillshooks.hooks;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs one shell command in a directory with stdin closed, combined output, and a hard timeout. */
final class CommandRunner {

    private static final int MAX_CAPTURE = 64 * 1024;

    record Result(int exitCode, String output, boolean timedOut, boolean startFailed, long durationMs) {
    }

    private CommandRunner() {
    }

    static Result run(String command, File dir, int timeoutSeconds) {
        long start = System.currentTimeMillis();
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> argv = windows ? List.of("cmd", "/c", command) : List.of("sh", "-c", command);
        try {
            ProcessBuilder pb = new ProcessBuilder(argv).directory(dir).redirectErrorStream(true);
            Process p = pb.start();
            p.getOutputStream().close();
            StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (var in = p.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        synchronized (out) {
                            out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                            // keep only the tail: that is where failures are reported
                            if (out.length() > MAX_CAPTURE) out.delete(0, out.length() - MAX_CAPTURE);
                        }
                    }
                } catch (IOException ignored) {
                    // stream closed by destroy
                }
            });
            reader.setDaemon(true);
            reader.start();
            boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
            reader.join(1000);
            String text;
            synchronized (out) {
                text = out.toString();
            }
            return new Result(done ? p.exitValue() : -1, text, !done, false, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return new Result(-1, String.valueOf(e.getMessage()), false, true, System.currentTimeMillis() - start);
        }
    }
}
