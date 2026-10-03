package dev.dorrian.localcodegen.validate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Path and content checks that must hold before anything the model wrote is kept. Port of validate.py. */
public final class Validate {

    public static final int MAX_CONTEXT_BYTES = 200_000;
    public static final int MAX_FILE_BYTES = 400_000;
    // Seen in the bake-off: a model emitted `#L1`, `#L2`, ... line markers between code lines.
    private static final Pattern LINE_MARKER = Pattern.compile("^#L\\d+\\s*$", Pattern.MULTILINE);

    private Validate() {}

    public record OutputFile(String path, String content) {}

    public record Rejected(String path, String reason) {}

    public record Checked(List<OutputFile> accepted, List<Rejected> rejected) {}

    /** posix-normalise like PurePosixPath: collapse '//' and '.', keep '..' parts. */
    private static List<String> parts(String p) {
        List<String> out = new ArrayList<>();
        for (String s : p.split("/")) {
            if (!s.isEmpty() && !s.equals(".")) {
                out.add(s);
            }
        }
        return out;
    }

    private static String posix(String p) {
        List<String> parts = parts(p);
        String joined = parts.isEmpty() ? "." : String.join("/", parts);
        return p.startsWith("/") ? "/" + joined : joined;
    }

    /** Allowed output paths must be relative, free of '..', and non-empty. */
    public static List<String> normalizeAllowed(List<String> paths) {
        List<String> out = new ArrayList<>();
        if (paths != null) {
            for (String p : paths) {
                if (p == null || p.isEmpty() || p.startsWith("/") || p.endsWith("/") || parts(p).contains("..")
                    || parts(p).isEmpty()) {
                    throw new IllegalArgumentException(
                        "files_allowed entry must be a relative path without '..': '" + p + "'");
                }
                out.add(posix(p));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("files_allowed must not be empty");
        }
        return out;
    }

    /** Read context files, refusing anything that resolves outside projectRoot (symlinks included). */
    public static Map<String, String> resolveContext(String projectRoot, List<String> contextFiles) {
        Path root;
        try {
            root = Path.of(projectRoot).toRealPath();
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("project_root is not a directory: " + projectRoot);
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("project_root is not a directory: " + projectRoot);
        }
        Map<String, String> contents = new LinkedHashMap<>();
        for (String item : contextFiles) {
            Path candidate = root.resolve(item).normalize();
            Path real;
            try {
                real = Files.exists(candidate) ? candidate.toRealPath() : candidate;
            } catch (IOException e) {
                throw new IllegalArgumentException("context file not found: '" + item + "'");
            }
            if (!real.startsWith(root)) {
                throw new IllegalArgumentException("context file escapes project_root: '" + item + "'");
            }
            if (!Files.isRegularFile(real)) {
                throw new IllegalArgumentException("context file not found: '" + item + "'");
            }
            try {
                if (Files.size(real) > MAX_CONTEXT_BYTES) {
                    throw new IllegalArgumentException(
                        "context file too large (> " + MAX_CONTEXT_BYTES + " bytes): '" + item + "'");
                }
                contents.put(root.relativize(real).toString().replace('\\', '/'),
                    Files.readString(real, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalArgumentException("context file unreadable: '" + item + "'");
            }
        }
        return contents;
    }

    /** Split model output into (accepted, rejected). Rejected entries carry a reason. */
    public static Checked checkOutput(List<OutputFile> files, List<String> allowed) {
        List<OutputFile> accepted = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (OutputFile f : files) {
            String path = posix(f.path());
            String content = f.content();
            String reason = null;
            if (!allowed.contains(path)) {
                reason = "path not in files_allowed";
            } else if (seen.contains(path)) {
                reason = "duplicate path";
            } else if (content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
                reason = "file too large";
            } else if (LINE_MARKER.matcher(content).find()) {
                reason = "line-marker artifact (#L<n> lines) in content";
            } else if (content.isBlank()) {
                reason = "empty content";
            }
            if (reason != null) {
                rejected.add(new Rejected(f.path(), reason));
            } else {
                seen.add(path);
                accepted.add(new OutputFile(path, content));
            }
        }
        return new Checked(accepted, rejected);
    }
}
