package dev.dorrian.agenticskillscli.registry;

import java.util.List;

/** AI tools that have a documented hook mechanism we can register into. Others are skipped. */
public final class HookToolSupport {

    /** Tool keys with hook support, in install order. */
    public static final List<String> TOOLS = List.of("claude", "gemini", "opencode");

    private HookToolSupport() {
    }

    public static boolean supports(String toolKey) {
        return TOOLS.contains(toolKey);
    }
}
