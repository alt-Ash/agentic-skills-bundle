package dev.dorrian.agenticskillscli.mcp.local;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class LocalCodegenCapabilityCheckTest {

    private static Function<String[], String> smi(String output) {
        return cmd -> "nvidia-smi".equals(cmd[0]) ? output : null;
    }

    @Test
    void bigNvidiaGpuIsSilent() {
        assertTrue(LocalCodegenCapabilityCheck.warning(smi("16376\n"), "Linux", "amd64").isEmpty());
    }

    @Test
    void smallNvidiaGpuWarnsWithTheVramFigure() {
        Optional<String> w = LocalCodegenCapabilityCheck.warning(smi("4096\n"), "Linux", "amd64");
        assertTrue(w.isPresent() && w.get().contains("4096 MiB") && w.get().contains("viable=false"));
    }

    @Test
    void cpuOnlyMachineWarns() {
        Optional<String> w = LocalCodegenCapabilityCheck.warning(cmd -> null, "Linux", "amd64");
        assertTrue(w.isPresent() && w.get().contains("no supported GPU"));
    }

    @Test
    void appleSiliconAndAmdAreSilent() {
        assertTrue(LocalCodegenCapabilityCheck.warning(cmd -> null, "Mac OS X", "aarch64").isEmpty());
        assertTrue(LocalCodegenCapabilityCheck.warning(
            cmd -> "rocm-smi".equals(cmd[0]) ? "ok" : null, "Linux", "amd64").isEmpty());
    }

    @Test
    void unparsableSmiOutputFallsThroughToNoGpu() {
        assertTrue(LocalCodegenCapabilityCheck.warning(smi("garbage"), "Linux", "amd64").isPresent());
    }
}
