package dev.dorrian.agenticskillscli.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dorrian.usagestore.VerifyTrust;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code agentic-skills verify <trust|untrust|status> [dir]}: approval of a project's
 * {@code .agentic-skills/verify.json}. The verify hook runs those shell commands by itself, outside the AI
 * tool's permission system, so it only honors a file whose exact content you approved for that project.
 * Running {@code trust} is the consent: it prints the commands first.
 */
public final class VerifyCommands {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VerifyCommands() {
    }

    /** Returns the process exit code. {@code args} excludes the leading {@code verify}. */
    public static int run(List<String> args, Path home, Path cwd, PrintStream out, PrintStream err) {
        String sub = args.isEmpty() ? "" : args.get(0);
        if (args.size() > 2 || !List.of("trust", "untrust", "status").contains(sub)) {
            err.println("Usage: agentic-skills verify <trust | untrust | status> [project-dir]");
            return 2;
        }
        Path project = (args.size() == 2 ? cwd.resolve(args.get(1)) : cwd).toAbsolutePath().normalize();
        Path file = project.resolve(".agentic-skills").resolve("verify.json");
        try {
            return switch (sub) {
                case "trust" -> trust(home, project, file, out, err);
                case "untrust" -> {
                    out.println(VerifyTrust.untrust(home, project)
                        ? "No longer trusting " + project : "Nothing to remove: " + project + " was not trusted");
                    yield 0;
                }
                default -> status(home, project, file, out);
            };
        } catch (IOException e) {
            err.println("Failed: " + e.getMessage());
            return 1;
        }
    }

    private static int trust(Path home, Path project, Path file, PrintStream out, PrintStream err) throws IOException {
        if (!Files.isRegularFile(file)) {
            err.println("No " + file + " to approve.");
            return 1;
        }
        byte[] bytes = Files.readAllBytes(file);
        List<String> commands = commands(bytes);
        if (commands.isEmpty()) {
            err.println(file + " lists no commands (expected {\"commands\": [\"...\"]}); nothing approved.");
            return 1;
        }
        VerifyTrust.trust(home, project, bytes);
        out.println("Trusting " + project + ". When your AI tool tries to stop, the verify hook will run:");
        for (String c : commands) out.println("  " + c);
        out.println("If the file changes, it stops running until you run this command again.");
        return 0;
    }

    private static int status(Path home, Path project, Path file, PrintStream out) throws IOException {
        if (!Files.isRegularFile(file)) {
            out.println("No verify.json in " + project);
            return 0;
        }
        out.println(VerifyTrust.isTrusted(home, project, Files.readAllBytes(file))
            ? "trusted: " + project
            : "NOT trusted: " + project + " (never approved, or the file changed). Run: agentic-skills verify trust");
        return 0;
    }

    private static List<String> commands(byte[] bytes) {
        List<String> out = new ArrayList<>();
        try {
            JsonNode cmds = MAPPER.readTree(bytes).get("commands");
            if (cmds != null && cmds.isArray()) {
                for (JsonNode c : cmds) {
                    if (c.isTextual() && !c.asText().isBlank()) out.add(c.asText());
                }
            }
        } catch (IOException ignored) {
            // unparsable: no commands
        }
        return out;
    }

    public static int runDefault(List<String> args) {
        return run(args, Path.of(System.getProperty("user.home")), Path.of("").toAbsolutePath(), System.out, System.err);
    }
}
