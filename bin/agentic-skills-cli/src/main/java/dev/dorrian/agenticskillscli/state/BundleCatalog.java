package dev.dorrian.agenticskillscli.state;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/** The names a bundle ships, as the "KIND:name" entries {@link InstallManifest#recordBundle} stores. */
public final class BundleCatalog {

    private BundleCatalog() {
    }

    public static String entry(ItemKind kind, String name) {
        return kind + ":" + name;
    }

    /** Sorted, de-duplicated entries for everything the bundle ships (any argument may be empty). */
    public static List<String> items(Collection<String> skills, Collection<String> agents,
                                     Collection<String> commands, Collection<String> mcpJars) {
        TreeSet<String> out = new TreeSet<>();
        skills.forEach(n -> out.add(entry(ItemKind.SKILL, n)));
        agents.forEach(n -> out.add(entry(ItemKind.AGENT, n)));
        commands.forEach(n -> out.add(entry(ItemKind.COMMAND, n)));
        mcpJars.forEach(n -> out.add(entry(ItemKind.MCP_JAR, n)));
        return List.copyOf(out);
    }
}
