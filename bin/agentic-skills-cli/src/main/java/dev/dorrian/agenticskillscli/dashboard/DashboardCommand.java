package dev.dorrian.agenticskillscli.dashboard;

import java.io.IOException;
import java.io.PrintStream;
import java.net.BindException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** {@code agentic-skills dashboard [--port N] [--no-open]}: serves the local usage dashboard until interrupted. */
public final class DashboardCommand {

    static final int DEFAULT_PORT = 8787;

    private DashboardCommand() {
    }

    /** Returns an exit code; blocks while serving. */
    public static int run(List<String> args, Path dbPath, PrintStream out, PrintStream err) {
        int port = DEFAULT_PORT;
        boolean open = true;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--no-open" -> open = false;
                case "--port" -> {
                    if (i + 1 >= args.size() || !args.get(i + 1).matches("\\d{1,5}")) {
                        err.println("--port needs a number between 0 and 65535");
                        return 2;
                    }
                    port = Integer.parseInt(args.get(++i));
                }
                default -> {
                    err.println("Usage: agentic-skills dashboard [--port N] [--no-open]");
                    return 2;
                }
            }
        }
        if (!Files.isRegularFile(dbPath)) {
            err.println("No usage data yet at " + dbPath);
            err.println("Use Claude Code with the hooks installed, or run: agentic-skills data import <project-dir>");
            return 1;
        }

        DashboardServer server;
        try {
            server = startWithFallback(dbPath, port, out);
        } catch (IOException e) {
            err.println("Could not start the dashboard: " + e.getMessage());
            return 1;
        }
        out.println("Dashboard: " + server.url() + "  (read-only, local only; Ctrl+C to stop)");
        if (open) {
            BrowserOpener.open(server.url());
        }
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            server.close();
        }
        return 0;
    }

    private static DashboardServer startWithFallback(Path dbPath, int port, PrintStream out) throws IOException {
        try {
            return DashboardServer.start(dbPath, port);
        } catch (BindException e) {
            if (port == 0) throw e;
            out.println("Port " + port + " is busy; picking a free one.");
            return DashboardServer.start(dbPath, 0);
        }
    }
}
