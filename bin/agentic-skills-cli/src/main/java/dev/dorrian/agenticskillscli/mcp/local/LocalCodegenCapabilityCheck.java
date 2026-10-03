package dev.dorrian.agenticskillscli.mcp.local;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Read-only install-time hint: does this machine look able to run a local code model? It never
 * starts a server or calls a model, and never blocks an install. The real gate is the MCP's
 * {@code health} probe at runtime (a timed benchmark slice). The VRAM thresholds mirror the MCP's
 * tiers: a 14B Q4 model was measured at about 12.5 GB on one machine; the rest is a proposal.
 */
public final class LocalCodegenCapabilityCheck {

    static final long MIN_VRAM_MIB = 7_500;

    private LocalCodegenCapabilityCheck() {
    }

    /** A warning to print after install, or empty when the machine looks capable. */
    public static Optional<String> warning() {
        return warning(LocalCodegenCapabilityCheck::run, System.getProperty("os.name", ""),
            System.getProperty("os.arch", ""));
    }

    static Optional<String> warning(Function<String[], String> runner, String osName, String osArch) {
        String smi = runner.apply(new String[] {"nvidia-smi", "--query-gpu=memory.total", "--format=csv,noheader,nounits"});
        if (smi != null) {
            long best = -1;
            for (String line : smi.strip().split("\\R")) {
                try {
                    best = Math.max(best, Long.parseLong(line.strip()));
                } catch (NumberFormatException ignored) {
                    // skip unparsable line
                }
            }
            if (best >= MIN_VRAM_MIB) {
                return Optional.empty();
            }
            if (best >= 0) {
                return Optional.of(note("the GPU has " + best + " MiB of VRAM (about " + MIN_VRAM_MIB + " MiB needed)"));
            }
        }
        if (runner.apply(new String[] {"rocm-smi", "--showproductname"}) != null) {
            return Optional.empty();
        }
        if (osName.startsWith("Mac") && osArch.contains("aarch64")) {
            return Optional.empty();
        }
        return Optional.of(note("no supported GPU was detected"));
    }

    private static String note(String why) {
        return "local-codegen installed, but " + why + ". Local generation may be too slow here; the MCP's health "
            + "probe will report viable=false and slices will go to specialist agents. See docs/local-codegen.md.";
    }

    private static String run(String[] cmd) {
        java.nio.file.Path out = null;
        try {
            out = java.nio.file.Files.createTempFile("local-codegen-check", ".out");
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(out.toFile()).start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? java.nio.file.Files.readString(out) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (out != null) {
                try {
                    java.nio.file.Files.deleteIfExists(out);
                } catch (IOException ignored) {
                    // temp file cleanup is best-effort
                }
            }
        }
    }
}
