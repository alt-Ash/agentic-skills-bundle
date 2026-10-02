package dev.dorrian.agenticskillscli.data;

import dev.dorrian.usagestore.UsageDb;
import dev.dorrian.usagestore.UsageStoreException;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Non-interactive {@code agentic-skills data ...} subcommands over the usage database:
 * {@code path}, {@code import [dir...]}, {@code prune --older-than <N>d}.
 */
public final class DataCommands {

    private static final Pattern DAYS = Pattern.compile("(\\d+)d");

    private DataCommands() {
    }

    /** Returns the process exit code. {@code args} excludes the leading {@code data}. */
    public static int run(List<String> args, Path dbPath, Path cwd, PrintStream out, PrintStream err) {
        String sub = args.isEmpty() ? "" : args.get(0);
        List<String> rest = args.isEmpty() ? List.of() : args.subList(1, args.size());
        try {
            return switch (sub) {
                case "path" -> {
                    out.println(dbPath);
                    yield 0;
                }
                case "import" -> importCommand(rest, dbPath, cwd, out, err);
                case "prune" -> pruneCommand(rest, dbPath, out, err);
                default -> {
                    err.println("Usage: agentic-skills data <path | import [dir...] | prune --older-than <N>d>");
                    yield 2;
                }
            };
        } catch (UsageStoreException e) {
            err.println(e.getMessage());
            return 1;
        }
    }

    private static int importCommand(List<String> dirs, Path dbPath, Path cwd, PrintStream out, PrintStream err) {
        List<Path> targets = new ArrayList<>();
        for (String d : dirs) targets.add(cwd.resolve(d));
        if (targets.isEmpty()) targets.add(cwd);

        int failures = 0;
        LegacyJsonImporter.Result total = new LegacyJsonImporter.Result(0, 0, 0);
        try (UsageDb db = UsageDb.open(dbPath)) {
            for (Path dir : targets) {
                if (!Files.exists(dir.resolve(LegacyJsonImporter.FILE_NAME))) {
                    err.println("No " + LegacyJsonImporter.FILE_NAME + " in " + dir);
                    failures++;
                    continue;
                }
                try {
                    LegacyJsonImporter.Result r = LegacyJsonImporter.importDirectory(db, dir);
                    out.println(dir + ": " + r.imported() + " imported, " + r.duplicates() + " already present, "
                        + r.skipped() + " skipped");
                    total = total.plus(r);
                } catch (IOException e) {
                    err.println(e.getMessage());
                    failures++;
                }
            }
        }
        if (targets.size() > 1) {
            out.println("Total: " + total.imported() + " imported, " + total.duplicates() + " already present, "
                + total.skipped() + " skipped");
        }
        if (total.imported() > 0) {
            out.println("Imported into " + dbPath + ". The old JSON files were left in place; delete them when you're happy.");
        }
        return failures == 0 ? 0 : 1;
    }

    private static int pruneCommand(List<String> args, Path dbPath, PrintStream out, PrintStream err) {
        int days = -1;
        for (int i = 0; i < args.size(); i++) {
            if ("--older-than".equals(args.get(i)) && i + 1 < args.size()) {
                Matcher m = DAYS.matcher(args.get(i + 1));
                if (m.matches()) days = Integer.parseInt(m.group(1));
            }
        }
        if (days < 1) {
            err.println("Usage: agentic-skills data prune --older-than <N>d   (N >= 1, e.g. 90d)");
            return 2;
        }
        if (!Files.isRegularFile(dbPath)) {
            out.println("Nothing to prune: no database at " + dbPath);
            return 0;
        }
        String cutoff = Instant.now().minus(Duration.ofDays(days)).toString();
        try (UsageDb db = UsageDb.open(dbPath)) {
            int deleted = db.pruneBefore(cutoff);
            out.println("Deleted " + deleted + " event(s) older than " + days + " day(s).");
        }
        return 0;
    }

    /** Resolves the default paths for a real run. */
    public static int runDefault(List<String> args) {
        return run(args, UsageDb.defaultPath(), Paths.get("").toAbsolutePath(), System.out, System.err);
    }
}
