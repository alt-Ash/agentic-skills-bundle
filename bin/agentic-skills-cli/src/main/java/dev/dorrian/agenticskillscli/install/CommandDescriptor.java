package dev.dorrian.agenticskillscli.install;

import java.nio.file.Path;

/**
 * A resolved command file ready to install: its (extensionless) name and its
 * source markdown file. Java port of the {@code { name, srcFile }} plain
 * objects built by {@code resolveCommands}/{@code resolveAgentCommands} in
 * {@code bin/install.js}.
 */
public record CommandDescriptor(String name, Path srcFile) {
}
