package dev.dorrian.agenticskillscli.flow;

import dev.dorrian.agenticskillscli.HomeDir;
import dev.dorrian.agenticskillscli.ui.Ansi;
import dev.dorrian.agenticskillscli.ui.Prompter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The "Project path:" prompt shared by the install and uninstall flows, and the parsing behind it.
 * Typed or pasted paths are interpreted the way a shell would: {@code ~} and {@code $HOME} expand
 * to the home directory, surrounding quotes are stripped, backslash-escaped spaces (macOS terminal
 * drag-and-drop) are unescaped, and only genuinely relative paths resolve against the current
 * directory. Previously {@code ~/x} became {@code <cwd>/~/x}.
 */
final class ProjectPathInput {

    private ProjectPathInput() {
    }

    /** Prompts until the user gives an existing directory; blank means the current directory. */
    static Path prompt(Prompter prompter) {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        while (true) {
            String input = prompter.input("Project path:", cwd.toString());
            Path resolved = resolve(input, cwd, HomeDir.resolve());
            if (!Files.exists(resolved)) {
                System.out.println("  " + Ansi.red("Path does not exist: " + resolved));
                continue;
            }
            if (!Files.isDirectory(resolved)) {
                System.out.println("  " + Ansi.red("Path must be a directory: " + resolved));
                continue;
            }
            return resolved;
        }
    }

    static Path resolve(String raw, Path cwd, Path home) {
        String s = raw == null ? "" : raw.strip();
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'') && s.charAt(s.length() - 1) == s.charAt(0)) {
            s = s.substring(1, s.length() - 1).strip();
        }
        if (File.separatorChar == '/') {
            s = s.replace("\\ ", " "); // on Windows a backslash is a separator, never an escape
        }
        if (s.isEmpty()) {
            return cwd.toAbsolutePath().normalize();
        }

        String homeStr = home.toString();
        if (s.equals("~")) {
            s = homeStr;
        } else if (s.startsWith("~/") || s.startsWith("~" + File.separator)) {
            s = homeStr + s.substring(1);
        } else if (s.equals("$HOME") || s.startsWith("$HOME/")) {
            s = homeStr + s.substring("$HOME".length());
        } else if (s.equals("${HOME}") || s.startsWith("${HOME}/")) {
            s = homeStr + s.substring("${HOME}".length());
        }

        Path path = Paths.get(s);
        return (path.isAbsolute() ? path : cwd.resolve(path)).toAbsolutePath().normalize();
    }
}
