package dev.dorrian.agenticskillscli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HomeDirTest {

    @Test
    void usesTheRealHomeWhenNoOverrideEnvVarIsSet() {
        Path resolved = HomeDir.resolve(Map.of(), "/Users/real-home");
        assertEquals(Path.of("/Users/real-home"), resolved);
    }

    @Test
    void usesTheOverrideWhenTheEnvVarIsSet() {
        Path resolved = HomeDir.resolve(
            Map.of(HomeDir.OVERRIDE_ENV_VAR, "/tmp/scratch-home"), "/Users/real-home"
        );
        assertEquals(Path.of("/tmp/scratch-home"), resolved);
    }

    @Test
    void treatsABlankOverrideAsUnset() {
        Path resolved = HomeDir.resolve(
            Map.of(HomeDir.OVERRIDE_ENV_VAR, "   "), "/Users/real-home"
        );
        assertEquals(Path.of("/Users/real-home"), resolved);
    }

    @Test
    void noArgOverloadReadsTheProcessEnvironmentAndSystemProperty() {
        // Proves the no-arg convenience overload is wired to the real env/property,
        // not just the testable overload — this is what every production call site uses.
        Path resolved = HomeDir.resolve();
        assertEquals(System.getenv().getOrDefault(HomeDir.OVERRIDE_ENV_VAR, System.getProperty("user.home")),
            resolved.toString());
    }
}
