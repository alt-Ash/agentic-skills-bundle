package dev.dorrian.agenticskillscli.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalMcpConfigRegistryTest {

    private static final String C7 = "https://mcp.context7.com/mcp";
    private static final String FIGMA_REMOTE = "https://mcp.figma.com/mcp";
    private static final String FIGMA_DESKTOP = "http://127.0.0.1:3845/mcp";
    private static final Map<String, Object> BEARER = Map.of("Authorization", "Bearer abc123");

    // --- engram (unchanged: local binary) ---

    @Test
    void engramOpencodeUsesLocalArrayCommand() {
        Map<String, Object> cfg = GlobalMcpConfigRegistry.engram("opencode");
        assertEquals("local", cfg.get("type"));
        assertEquals(List.of("engram", "mcp"), cfg.get("command"));
    }

    @Test
    void engramZedUsesCustomSource() {
        Map<String, Object> cfg = GlobalMcpConfigRegistry.engram("zed");
        assertEquals("custom", cfg.get("source"));
        assertEquals("engram", cfg.get("command"));
        assertEquals(List.of("mcp"), cfg.get("args"));
    }

    @Test
    void engramClaudeUsesStdio() {
        Map<String, Object> cfg = GlobalMcpConfigRegistry.engram("claude");
        assertEquals("stdio", cfg.get("type"));
        assertEquals("engram", cfg.get("command"));
        assertEquals(List.of("mcp"), cfg.get("args"));
    }

    // --- context7: hosted remote endpoint for every tool, no npx anywhere ---

    @Test
    void context7Opencode() {
        assertEquals(Map.of("type", "remote", "url", C7, "enabled", true),
            GlobalMcpConfigRegistry.context7("opencode", null));
        assertEquals(Map.of("type", "remote", "url", C7, "enabled", true, "headers", BEARER),
            GlobalMcpConfigRegistry.context7("opencode", "abc123"));
    }

    @Test
    void context7Claude() {
        // Consumed by ClaudeCliMcpRegistrar -> `claude mcp add --transport http --header ...`
        assertEquals(Map.of("type", "http", "url", C7),
            GlobalMcpConfigRegistry.context7("claude", null));
        assertEquals(Map.of("type", "http", "url", C7, "headers", BEARER),
            GlobalMcpConfigRegistry.context7("claude", "abc123"));
    }

    @Test
    void context7Cursor() {
        assertEquals(Map.of("url", C7), GlobalMcpConfigRegistry.context7("cursor", null));
        assertEquals(Map.of("url", C7, "headers", BEARER), GlobalMcpConfigRegistry.context7("cursor", "abc123"));
    }

    @Test
    void context7Vscode() {
        assertEquals(Map.of("type", "http", "url", C7), GlobalMcpConfigRegistry.context7("vscode", null));
        assertEquals(Map.of("type", "http", "url", C7, "headers", BEARER),
            GlobalMcpConfigRegistry.context7("vscode", "abc123"));
    }

    @Test
    void context7Windsurf() {
        assertEquals(Map.of("serverUrl", C7), GlobalMcpConfigRegistry.context7("windsurf", null));
        assertEquals(Map.of("serverUrl", C7, "headers", BEARER),
            GlobalMcpConfigRegistry.context7("windsurf", "abc123"));
    }

    @Test
    void context7Zed() {
        assertEquals(Map.of("url", C7), GlobalMcpConfigRegistry.context7("zed", null));
        assertEquals(Map.of("url", C7, "headers", BEARER), GlobalMcpConfigRegistry.context7("zed", "abc123"));
    }

    @Test
    void context7BlankApiKeyTreatedAsAbsent() {
        assertEquals(Map.of("url", C7), GlobalMcpConfigRegistry.context7("cursor", ""));
    }

    // --- figma: OAuth-only remote server for Figma-catalog clients, desktop server otherwise ---

    @Test
    void figmaClaudeUsesRemoteHttpWithoutHeaders() {
        assertEquals(Map.of("type", "http", "url", FIGMA_REMOTE), GlobalMcpConfigRegistry.figma("claude"));
    }

    @Test
    void figmaCursorUsesRemoteUrl() {
        assertEquals(Map.of("url", FIGMA_REMOTE), GlobalMcpConfigRegistry.figma("cursor"));
    }

    @Test
    void figmaVscodeUsesRemoteHttp() {
        assertEquals(Map.of("type", "http", "url", FIGMA_REMOTE), GlobalMcpConfigRegistry.figma("vscode"));
    }

    @Test
    void figmaOpencodeUsesDesktopServer() {
        assertEquals(Map.of("type", "remote", "url", FIGMA_DESKTOP, "enabled", true),
            GlobalMcpConfigRegistry.figma("opencode"));
    }

    @Test
    void figmaWindsurfUsesDesktopServer() {
        assertEquals(Map.of("serverUrl", FIGMA_DESKTOP), GlobalMcpConfigRegistry.figma("windsurf"));
    }

    @Test
    void figmaZedUsesDesktopServer() {
        assertEquals(Map.of("url", FIGMA_DESKTOP), GlobalMcpConfigRegistry.figma("zed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"opencode", "claude", "cursor", "vscode", "windsurf", "zed"})
    void noGlobalServerNeedsNpxOrAFigmaToken(String toolKey) {
        String all = GlobalMcpConfigRegistry.context7(toolKey, "k") + " " + GlobalMcpConfigRegistry.figma(toolKey);
        assertEquals(false, all.contains("npx"), all);
        assertEquals(false, all.contains("FIGMA_ACCESS_TOKEN"), all);
    }
}
