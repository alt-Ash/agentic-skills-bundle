package dev.dorrian.agenticskillscli.registry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalMcpConfigRegistryTest {

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

    @Test
    void context7OpencodeRemoteWithoutApiKeyOmitsHeaders() {
        Map<String, Object> cfg = GlobalMcpConfigRegistry.context7("opencode", null);
        assertEquals("remote", cfg.get("type"));
        assertEquals("https://mcp.context7.com/mcp", cfg.get("url"));
        assertEquals(Boolean.TRUE, cfg.get("enabled"));
        assertFalse(cfg.containsKey("headers"));
    }

    @Test
    void context7OpencodeRemoteWithApiKeyIncludesHeaders() {
        Map<String, Object> cfg = GlobalMcpConfigRegistry.context7("opencode", "abc123");
        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) cfg.get("headers");
        assertEquals("abc123", headers.get("CONTEXT7_API_KEY"));
    }

    @Test
    void context7ClaudeUsesNpxStdioWithApiKeyArg() {
        Map<String, Object> cfg = GlobalMcpConfigRegistry.context7("claude", "abc123");
        assertEquals("stdio", cfg.get("type"));
        assertEquals("npx", cfg.get("command"));
        assertEquals(List.of("-y", "@upstash/context7-mcp", "--api-key", "abc123"), cfg.get("args"));
    }

    @Test
    void context7ClaudeWithoutApiKeyOmitsFlag() {
        Map<String, Object> cfg = GlobalMcpConfigRegistry.context7("claude", null);
        assertEquals(List.of("-y", "@upstash/context7-mcp"), cfg.get("args"));
    }

    @Test
    void context7VscodeUsesHttpType() {
        Map<String, Object> cfg = GlobalMcpConfigRegistry.context7("vscode", null);
        assertEquals("http", cfg.get("type"));
        assertEquals("https://mcp.context7.com/mcp", cfg.get("url"));
    }

    @Test
    void context7WindsurfUsesServerUrlKey() {
        Map<String, Object> cfg = GlobalMcpConfigRegistry.context7("windsurf", null);
        assertEquals("https://mcp.context7.com/mcp", cfg.get("serverUrl"));
        assertNull(cfg.get("type"));
    }

    @Test
    void figmaOpencodeUsesEnvPlaceholder() {
        Map<String, Object> cfg = GlobalMcpConfigRegistry.figma("opencode");
        assertEquals("local", cfg.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> env = (Map<String, Object>) cfg.get("environment");
        assertEquals("{env:FIGMA_ACCESS_TOKEN}", env.get("FIGMA_ACCESS_TOKEN"));
    }

    @Test
    void figmaZedUsesCustomSourceWithEnvMap() {
        Map<String, Object> cfg = GlobalMcpConfigRegistry.figma("zed");
        assertEquals("custom", cfg.get("source"));
        assertTrue(cfg.containsKey("env"));
    }
}
