package dev.dorrian.agenticskillscli.mcp.local;

import java.nio.file.Path;

public record McpInstallResult(boolean success, Path installDir) {
}
