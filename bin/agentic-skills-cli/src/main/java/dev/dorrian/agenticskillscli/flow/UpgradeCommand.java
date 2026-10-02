package dev.dorrian.agenticskillscli.flow;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code agentic-skills upgrade [--dry-run] [--project <dir>]... [--package-root <p>]}: refreshes
 * what is already installed from the current bundle. Non-interactive. Exit 0 = all ok (or nothing
 * installed), 1 = some op failed (the rest still ran), 2 = bad arguments.
 */
public final class UpgradeCommand {

    private static final String USAGE =
        "usage: agentic-skills upgrade [--dry-run] [--project <dir>]... [--package-root <path>]";

    private UpgradeCommand() {
    }

    public static int runDefault(List<String> args) {
        return run(args, null, System.out, System.err);
    }

    /** {@code env} null means the real installation (built after the args are validated). */
    public static int run(List<String> args, UpgradeEnvironment env, PrintStream out, PrintStream err) {
        boolean dryRun = false;
        List<Path> projects = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--dry-run" -> dryRun = true;
                case "--project" -> {
                    if (++i >= args.size()) return bad(err, "--project needs a directory");
                    Path dir = Paths.get(args.get(i)).toAbsolutePath().normalize();
                    if (!Files.isDirectory(dir)) return bad(err, "not a directory: " + dir);
                    projects.add(dir);
                }
                case "--package-root" -> {
                    // Already applied by PackageRoot.initFromArgs; just check the value is present.
                    if (++i >= args.size()) return bad(err, "--package-root needs a path");
                }
                default -> {
                    return bad(err, "unknown argument: " + arg);
                }
            }
        }

        List<UpgradeOp> ops;
        try {
            ops = UpgradePlanner.plan(env != null ? env : UpgradeEnvironment.defaults(), projects);
        } catch (RuntimeException e) {
            err.println("upgrade: could not build the plan: " + e.getMessage());
            return 1;
        }

        if (ops.isEmpty()) {
            out.println("Nothing to upgrade: no installed skills, agents, commands, hooks or MCP jars found.");
            if (projects.isEmpty()) out.println("Project installs are only found with --project <dir>.");
            return 0;
        }

        out.println(dryRun ? "Upgrade plan (dry run, nothing will be written):" : "Upgrading:");
        int failed = 0;
        for (UpgradeOp op : ops) {
            if (dryRun) {
                out.println("  " + op.line());
                continue;
            }
            try {
                op.action().run();
                out.println("  ok     " + op.line());
            } catch (Exception e) {
                failed++;
                out.println("  FAILED " + op.line() + ": " + e.getMessage());
                err.println("upgrade: " + op.line() + " failed: " + e.getMessage());
            }
        }

        if (dryRun) {
            out.println(ops.size() + " operation(s) planned.");
            return 0;
        }
        out.println(ops.size() + " operation(s): " + (ops.size() - failed) + " ok, " + failed + " failed.");
        return failed == 0 ? 0 : 1;
    }

    private static int bad(PrintStream err, String message) {
        err.println("upgrade: " + message);
        err.println(USAGE);
        return 2;
    }
}
