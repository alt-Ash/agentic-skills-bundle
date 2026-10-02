package dev.dorrian.usagestore;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretRedactorTest {

    @Test
    void redactsBasicAuthInUrl() {
        String result = SecretRedactor.redact("curl https://user:hunter2@example.com/api");
        assertEquals("curl https://[REDACTED]@example.com/api", result);
    }

    @Test
    void redactsBearerToken() {
        String result = SecretRedactor.redact("curl -H \"Authorization: Bearer abc123XYZ\"");
        assertTrue(result.contains("Bearer [REDACTED]"));
        assertFalse(result.contains("abc123XYZ"));
    }

    @Test
    void redactsGithubToken() {
        String result = SecretRedactor.redact("export TOKEN_VAL=ghp_" + "a".repeat(30));
        assertFalse(result.contains("ghp_"));
    }

    @Test
    void redactsAwsAccessKey() {
        String result = SecretRedactor.redact("aws configure set aws_access_key_id AKIAABCDEFGHIJKLMNOP");
        assertFalse(result.contains("AKIAABCDEFGHIJKLMNOP"));
        assertTrue(result.contains("[REDACTED]"));
    }

    @Test
    void redactsOpenAiStyleSecretKey() {
        String result = SecretRedactor.redact("curl -H \"Authorization: Bearer sk-" + "a".repeat(25) + "\"");
        assertFalse(result.contains("sk-aaaaa"));
    }

    @Test
    void redactsJwt() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";
        String result = SecretRedactor.redact("curl -H \"X-Token: " + jwt + "\"");
        assertFalse(result.contains(jwt));
        assertTrue(result.contains("[REDACTED]"));
    }

    @Test
    void redactsCurlDashULoginPair() {
        String result = SecretRedactor.redact("curl -u admin:supersecret https://example.com");
        assertFalse(result.contains("supersecret"));
        assertTrue(result.contains("-u [REDACTED]"));
    }

    @Test
    void redactsSensitiveEnvAssignmentButNotOrdinaryOne() {
        String result = SecretRedactor.redact("API_KEY=abc123 FOO=bar");
        assertTrue(result.contains("API_KEY=[REDACTED]"));
        assertTrue(result.contains("FOO=bar"));
    }

    @Test
    void redactsCliSecretFlags() {
        String result = SecretRedactor.redact("mytool --password hunter2 --other value");
        assertFalse(result.contains("hunter2"));
        assertTrue(result.contains("--other value"));
    }

    @Test
    void redactsPemPrivateKeyBlock() {
        String pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIBOgIBAAJBAK\n-----END RSA PRIVATE KEY-----";
        String result = SecretRedactor.redact("cat key.pem: " + pem);
        assertFalse(result.contains("MIIBOgIBAAJBAK"));
        assertTrue(result.contains("[REDACTED PRIVATE KEY]"));
    }

    @Test
    void truncatesLongCommands() {
        String longCommand = "echo " + "a".repeat(3000);
        String result = SecretRedactor.redact(longCommand);
        assertTrue(result.endsWith("...[truncated]"));
        assertEquals(2000 + "...[truncated]".length(), result.length());
    }

    @Test
    void leavesOrdinaryCommandsUntouched() {
        String result = SecretRedactor.redact("ls -la /tmp");
        assertEquals("ls -la /tmp", result);
    }

    @Test
    void doesNotFalsePositiveOnAnOrdinaryGitCommitSha() {
        String cmd = "git show 5e30633aab1122334455667788990011223344 --stat";
        assertEquals(cmd, SecretRedactor.redact(cmd));
    }

    @Test
    void doesNotSwallowTheClosingQuoteAfterAQuotedBearerToken() {
        String result = SecretRedactor.redact(
                "curl -H \"Authorization: Bearer sk-ant-abcdefghijklmnopqrstuvwxyz123456\" https://api.example.com");
        assertEquals("curl -H \"Authorization: Bearer [REDACTED]\" https://api.example.com", result);
    }

    @Test
    void isIdempotentOnceAValueHasAlreadyBeenRedacted() {
        String once = SecretRedactor.redact("DB_PASSWORD=hunter2");
        String twice = SecretRedactor.redact(once);
        assertEquals(once, twice);
    }

    @Test
    void regressionDoesNotCatastrophicallyBacktrackOnALongRunOfASensitiveKeywordWithNoTrailingEquals() {
        // Previously ~10s for a 250KB string of repeated "TOKEN" with no "=" to complete the
        // match — the env-var rule used to be `[A-Za-z0-9_]*(?:TOKEN|...)[A-Za-z0-9_]*`, a
        // "wildcard around an alternation" shape that backtracks exponentially on this input.
        String input = "echo " + "TOKEN".repeat(50_000);
        long start = System.currentTimeMillis();
        SecretRedactor.redact(input);
        assertTrue(System.currentTimeMillis() - start < 500);
    }

    @Test
    void redactErrorRedactsSecretsCollapsesWhitespaceAndCapsLength() {
        assertNull(SecretRedactor.redactError(null));
        assertEquals("command not found", SecretRedactor.redactError("command   not\nfound\n"));

        String redacted = SecretRedactor.redactError("failed: curl -H 'Authorization: Bearer abc.def.ghi' https://x");
        assertTrue(redacted.contains("[REDACTED]"));
        assertFalse(redacted.contains("abc.def.ghi"));

        String capped = SecretRedactor.redactError("line of a quoted file\n".repeat(200));
        assertTrue(capped.length() <= 300 + "...[truncated]".length());
        assertTrue(capped.endsWith("...[truncated]"));
    }
}
