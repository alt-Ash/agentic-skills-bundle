package dev.dorrian.agenticskillscli.state;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/** SHA-256 content hashing for strings, files and directory trees. */
public final class ContentHash {

    private ContentHash() {
    }

    public static String ofString(String s) {
        MessageDigest md = digest();
        md.update(s.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(md.digest());
    }

    public static String ofFile(Path file) throws IOException {
        MessageDigest md = digest();
        md.update(Files.readAllBytes(file));
        return HexFormat.of().formatHex(md.digest());
    }

    public static String ofTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            throw new NoSuchFileException(root.toString());
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile)
                    .sorted((a, b) -> rel(root, a).compareTo(rel(root, b)))
                    .toList();
        }
        MessageDigest md = digest();
        for (Path f : files) {
            md.update(rel(root, f).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(Files.readAllBytes(f));
            md.update((byte) 0);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /**
     * Hashes only the files {@code shape} contains, reading their content from {@code root}. A file
     * missing from {@code root} contributes a distinct marker, so it always changes the hash; extra
     * files in {@code root} are ignored. Equals {@link #ofTree} of {@code shape} when root holds
     * identical copies.
     */
    public static String ofTreeLimitedTo(Path root, Path shape) throws IOException {
        return ofFiles(root, treeFiles(shape));
    }

    /** Sorted relative paths ('/' separators) of every regular file under {@code root}. */
    public static List<String> treeFiles(Path root) throws IOException {
        if (!Files.exists(root)) {
            throw new NoSuchFileException(root.toString());
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).map(f -> rel(root, f)).sorted().toList();
        }
    }

    /**
     * Same digest as {@link #ofTreeLimitedTo} but over exactly {@code relPaths} (sorted here). A file
     * missing from {@code root} contributes the distinct "missing" marker.
     */
    public static String ofFiles(Path root, List<String> relPaths) throws IOException {
        MessageDigest md = digest();
        for (String p : relPaths.stream().sorted().toList()) {
            md.update(p.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            Path live = root.resolve(p);
            if (Files.isRegularFile(live)) {
                md.update(Files.readAllBytes(live));
            } else {
                md.update((byte) 1);
            }
            md.update((byte) 0);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static String rel(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
