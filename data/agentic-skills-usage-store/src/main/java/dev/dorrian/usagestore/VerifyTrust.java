package dev.dorrian.usagestore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;

/**
 * Per-project approval for {@code .agentic-skills/verify.json}. That file lists shell commands the
 * verify hook runs on its own, outside the AI tool's permission system, and it lives in the repository
 * (so a cloned repo, or the model itself, could write one). A project is therefore only honored once the
 * user approved it, and the approval is tied to the SHA-256 of the file's exact contents: any later change
 * makes it untrusted again until it is re-approved.
 *
 * <p>Stored in {@code <home>/.agentic-skills/verify-trust.properties} as absolute project path to hash.
 * Anything unreadable counts as "not trusted"; this class never throws on read.
 */
public final class VerifyTrust {

    private static final String FILE_NAME = "verify-trust.properties";

    private VerifyTrust() {
    }

    public static Path file(Path home) {
        return home.resolve(".agentic-skills").resolve(FILE_NAME);
    }

    public static String fingerprint(byte[] configBytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(configBytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String key(Path project) {
        return project.toAbsolutePath().normalize().toString();
    }

    /** True only if this exact file content was approved for this project path. */
    public static boolean isTrusted(Path home, Path project, byte[] configBytes) {
        try {
            String approved = load(home).getProperty(key(project));
            return approved != null && approved.equals(fingerprint(configBytes));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Approves this exact content for the project. */
    public static void trust(Path home, Path project, byte[] configBytes) throws IOException {
        Properties props = load(home);
        props.setProperty(key(project), fingerprint(configBytes));
        store(home, props);
    }

    /** Removes the approval. Returns true if there was one. */
    public static boolean untrust(Path home, Path project) throws IOException {
        Properties props = load(home);
        if (props.remove(key(project)) == null) return false;
        store(home, props);
        return true;
    }

    private static Properties load(Path home) {
        Properties props = new Properties();
        Path file = file(home);
        if (!Files.isRegularFile(file)) return props;
        try (InputStream in = Files.newInputStream(file)) {
            props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            return new Properties(); // unreadable or corrupt: nothing is trusted
        }
        return props;
    }

    private static void store(Path home, Properties props) throws IOException {
        Path file = file(home);
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), FILE_NAME + ".", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                props.store(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8),
                    "Approved verify.json files (project path = SHA-256). Managed by `agentic-skills verify`.");
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
