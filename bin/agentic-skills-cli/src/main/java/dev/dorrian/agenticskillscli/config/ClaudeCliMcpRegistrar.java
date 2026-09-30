package dev.dorrian.agenticskillscli.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Java port of {@code bin/install.js}'s {@code claudeMcpExists} / {@code
 * installClaudeMcpServer} / {@code uninstallClaudeMcpServer}.
 *
 * <p>Claude Code never reads MCP servers from {@code settings.json} — only
 * from {@code .mcp.json} (project scope) or {@code ~/.claude.json} (user
 * scope), and only the CLI writes the exact schema it expects. So unlike
 * every other tool, Claude registration shells out to {@code claude mcp
 * add}/{@code claude mcp remove} instead of merging JSON directly.
 */
public final class ClaudeCliMcpRegistrar {

    public static final String CONFIG_LABEL = "~/.claude.json (user scope, via `claude mcp add`)";

    private ClaudeCliMcpRegistrar() {
    }

    public static boolean exists(String name) {
        return runQuiet("claude", "mcp", "get", name) == 0;
    }

    public static OperationResult install(String name, Map<String, Object> serverConfig, boolean force) {
        if (!force && exists(name)) {
            return OperationResult.ok(name, true, CONFIG_LABEL);
        }

        List<String> args = new ArrayList<>(List.of("mcp", "add", "--scope", "user"));
        @SuppressWarnings("unchecked")
        Map<String, Object> env = (Map<String, Object>) serverConfig.getOrDefault("env", Map.of());
        for (Map.Entry<String, Object> entry : env.entrySet()) {
            args.add("-e");
            args.add(entry.getKey() + "=" + entry.getValue());
        }
        args.add("--transport");
        args.add("stdio");
        args.add(name);
        args.add("--");
        args.add(String.valueOf(serverConfig.get("command")));
        @SuppressWarnings("unchecked")
        List<Object> cmdArgs = (List<Object>) serverConfig.getOrDefault("args", List.of());
        for (Object a : cmdArgs) {
            args.add(String.valueOf(a));
        }

        try {
            List<String> fullCommand = new ArrayList<>();
            fullCommand.add("claude");
            fullCommand.addAll(args);
            int exit = run(fullCommand);
            if (exit != 0) {
                return OperationResult.failed(name, CONFIG_LABEL, "claude mcp add exited with status " + exit);
            }
            return OperationResult.ok(name, false, CONFIG_LABEL);
        } catch (IOException | InterruptedException e) {
            return OperationResult.failed(name, CONFIG_LABEL, e.getMessage());
        }
    }

    public static OperationResult uninstall(String name) {
        int exit = runQuiet("claude", "mcp", "remove", name, "--scope", "user");
        return OperationResult.ok(name, exit != 0, null);
    }

    private static int runQuiet(String... command) {
        try {
            return run(List.of(command));
        } catch (IOException | InterruptedException e) {
            return -1;
        }
    }

    private static int run(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = pb.start();
        return process.waitFor();
    }
}
