package dev.dorrian.agenticskillscli.install;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Shared command-list helpers. */
public final class CommandCatalog {

    private CommandCatalog() {
    }

    /** Skills' commands take priority over agents' on a name collision, matching the original's filter order. */
    public static List<CommandDescriptor> dedupe(List<CommandDescriptor> skillCmds, List<CommandDescriptor> agentCmds) {
        Set<String> seen = new LinkedHashSet<>();
        List<CommandDescriptor> result = new ArrayList<>();
        for (CommandDescriptor c : skillCmds) {
            if (seen.add(c.name())) result.add(c);
        }
        for (CommandDescriptor c : agentCmds) {
            if (seen.add(c.name())) result.add(c);
        }
        return result;
    }
}
