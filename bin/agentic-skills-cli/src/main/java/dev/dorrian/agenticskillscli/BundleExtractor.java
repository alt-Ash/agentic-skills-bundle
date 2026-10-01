package dev.dorrian.agenticskillscli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Makes the content bundled inside {@code agentic-skills.jar} (classpath {@code bundle/}: skills,
 * agents, commands, templates, the hooks jar and both MCP server jars) available as real files,
 * which is what every installer and discovery class needs.
 *
 * <p>When running from the shaded jar, {@code bundle/} is extracted once to
 * {@code <home>/.agentic-skills/dist/<version>/} (home from {@link HomeDir}, so
 * {@code AGENTIC_SKILLS_HOME_OVERRIDE} redirects it). Extraction is:
 * <ul>
 *   <li><b>idempotent</b> — skipped when the version dir holds a {@link #COMPLETE_MARKER} whose
 *       content matches the running jar's fingerprint (size + mtime), so a rebuilt jar with an
 *       unchanged version number still refreshes;</li>
 *   <li><b>atomic</b> — files are copied into a temp sibling dir, the marker is written last,
 *       and only then is the dir moved into place. A dir without a valid marker (interrupted
 *       run, manual tampering) is deleted and re-extracted.</li>
 * </ul>
 *
 * <p>When running from classes on disk (IDE, {@code mvn test}), {@code bundle/} is already a
 * directory ({@code target/classes/bundle}) and is used in place, without copying.
 */
public final class BundleExtractor {

    public static final String BUNDLE_DIR = "bundle";
    public static final String COMPLETE_MARKER = ".extraction-complete";
    static final String UNKNOWN_VERSION = "dev";

    private BundleExtractor() {
    }

    public static Path distDir(Path home, String version) {
        return home.resolve(".agentic-skills").resolve("dist").resolve(version);
    }

    /**
     * Locates the running code's {@code bundle/}: the directory itself when on disk, or the
     * extracted copy under {@code home} when running from a jar. Empty when there is no bundle
     * at all (e.g. a bare compile without resources).
     */
    public static Optional<Path> locateOrExtract(Path home) {
        Optional<Path> codeLocation = codeLocation();
        if (codeLocation.isEmpty()) {
            return Optional.empty();
        }
        Path location = codeLocation.get();
        if (Files.isDirectory(location)) {
            Path bundle = location.resolve(BUNDLE_DIR);
            return Files.isDirectory(bundle) ? Optional.of(bundle) : Optional.empty();
        }
        if (Files.isRegularFile(location)) {
            return extractFromJar(location, distDir(home, runningVersion()));
        }
        return Optional.empty();
    }

    /** Extracts the jar's {@code bundle/} entries into {@code dist}. Empty if the jar has none. */
    public static Optional<Path> extractFromJar(Path jar, Path dist) {
        try (FileSystem zip = FileSystems.newFileSystem(jar)) {
            Path bundle = zip.getPath("/" + BUNDLE_DIR);
            if (!Files.isDirectory(bundle)) {
                return Optional.empty();
            }
            return Optional.of(extract(bundle, dist, fingerprint(jar)));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read bundled content from " + jar, e);
        }
    }

    /**
     * Copies {@code source} (a directory on any file system, including a zip file system) to
     * {@code dist} unless {@code dist} is already a complete extraction with the same
     * {@code fingerprint}. Returns {@code dist}.
     */
    public static Path extract(Path source, Path dist, String fingerprint) {
        if (isComplete(dist, fingerprint)) {
            return dist;
        }
        try {
            Path parent = dist.getParent();
            Files.createDirectories(parent);
            Path staging = parent.resolve("." + dist.getFileName() + ".tmp-" + UUID.randomUUID());
            try {
                copyTree(source, staging);
                Files.writeString(staging.resolve(COMPLETE_MARKER), fingerprint, StandardCharsets.UTF_8);

                if (isComplete(dist, fingerprint)) {
                    // A concurrent run finished first; keep its copy.
                    return dist;
                }
                if (Files.exists(dist)) {
                    deleteRecursively(dist);
                }
                try {
                    Files.move(staging, dist, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(staging, dist);
                }
            } finally {
                if (Files.exists(staging)) {
                    deleteRecursively(staging);
                }
            }
        } catch (IOException e) {
            if (isComplete(dist, fingerprint)) {
                return dist; // lost a benign race with another extraction
            }
            throw new UncheckedIOException("Could not extract bundled content to " + dist, e);
        }
        return dist;
    }

    private static boolean isComplete(Path dist, String fingerprint) {
        Path marker = dist.resolve(COMPLETE_MARKER);
        try {
            return Files.isRegularFile(marker)
                && fingerprint.equals(Files.readString(marker, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path src : (Iterable<Path>) walk::iterator) {
                // Resolve by string segments: source may live on a different FileSystem (zip).
                Path dest = target;
                for (Path segment : source.relativize(src)) {
                    dest = dest.resolve(segment.toString());
                }
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static String fingerprint(Path jar) throws IOException {
        return Files.size(jar) + ":" + Files.getLastModifiedTime(jar).toMillis();
    }

    /** {@code Implementation-Version} from the jar manifest (set by the shade plugin). */
    static String runningVersion() {
        String version = BundleExtractor.class.getPackage().getImplementationVersion();
        return (version == null || version.isBlank()) ? UNKNOWN_VERSION : version;
    }

    private static Optional<Path> codeLocation() {
        CodeSource source = BundleExtractor.class.getProtectionDomain().getCodeSource();
        URL url = source == null ? null : source.getLocation();
        if (url == null || !"file".equals(url.getProtocol())) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(url.toURI()));
        } catch (URISyntaxException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
