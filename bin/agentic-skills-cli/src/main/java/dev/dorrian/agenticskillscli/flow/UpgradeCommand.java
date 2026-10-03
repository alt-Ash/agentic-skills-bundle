package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.flow.UpgradeReportFormatter.Line;
import dev.dorrian.agenticskillscli.flow.UpgradeReportFormatter.Outcome;
import dev.dorrian.agenticskillscli.state.InstallManifest;

import java.io.IOException;
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

        UpgradeEnvironment environment = env;
        UpgradePlanner.Plan plan;
        try {
            if (environment == null) environment = UpgradeEnvironment.defaults();
            plan = UpgradePlanner.plan(environment, projects);
        } catch (RuntimeException e) {
            err.println("upgrade: could not build the plan: " + e.getMessage());
            return 1;
        }

        if (plan.ops().isEmpty()) {
            out.println("Nothing to upgrade: no installed skills, agents, commands, hooks or MCP jars found.");
            if (projects.isEmpty()) out.println("Project installs are only found with --project <dir>.");
            return 0;
        }

        List<Line> lines = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        int failed = 0;
        for (UpgradeOp op : plan.ops()) {
            if (!labels.contains(op.tool())) labels.add(op.tool());
            Outcome outcome = op.outcome();
            String detail = null;
            if (op.action() != null && !dryRun) {
                try {
                    op.action().run();
                } catch (Exception e) {
                    failed++;
                    outcome = Outcome.FAILED;
                    detail = e.getMessage();
                    err.println("upgrade: " + op.kind() + " " + op.name() + " (" + op.tool() + ") failed: " + detail);
                }
            }
            lines.add(new Line(op.tool(), op.kind(), op.name(), outcome, detail));
        }

        InstallManifest manifest = plan.manifest();
        String from = manifest.bundleVersion() == null ? "unknown" : manifest.bundleVersion();
        if (dryRun) {
            out.println("Dry run: nothing written; 'refreshed' lines show what would be refreshed.");
        }
        out.println(UpgradeReportFormatter.format(from, environment.bundleVersion(), labels, lines, plan.newItems()));
        if (plan.baselineMissing()) {
            out.println("No previous install record, so new items cannot be told apart. "
                + "Run `agentic-skills` and choose Install to see everything not installed.");
        }

        if (!dryRun && environment.manifestFile() != null) {
            try {
                plan.backfills().forEach(Runnable::run);
                // A failed step keeps the old baseline and version so the next run re-reports it.
                if (failed == 0) {
                    manifest.advanceBundle(environment.bundleVersion(), plan.bundleItems(), plan.installedEntries());
                }
                manifest.save();
            } catch (IOException | RuntimeException e) {
                err.println("upgrade: warning: could not save the install record: " + e.getMessage());
            }
        }
        return failed == 0 ? 0 : 1;
    }

    private static int bad(PrintStream err, String message) {
        err.println("upgrade: " + message);
        err.println(USAGE);
        return 2;
    }
}
