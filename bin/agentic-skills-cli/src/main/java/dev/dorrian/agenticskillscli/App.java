package dev.dorrian.agenticskillscli;

import dev.dorrian.agenticskillscli.dashboard.DashboardCommand;
import dev.dorrian.agenticskillscli.data.DataCommands;
import dev.dorrian.agenticskillscli.data.VerifyCommands;
import dev.dorrian.agenticskillscli.detect.InstalledToolDetector;
import dev.dorrian.agenticskillscli.discovery.AgentDescriptor;
import dev.dorrian.agenticskillscli.discovery.AgentDiscovery;
import dev.dorrian.agenticskillscli.discovery.SkillDescriptor;
import dev.dorrian.agenticskillscli.discovery.SkillDiscovery;
import dev.dorrian.agenticskillscli.flow.FullInstallFlow;
import dev.dorrian.agenticskillscli.flow.QuickInstallFlow;
import dev.dorrian.agenticskillscli.flow.TokenUpdateFlow;
import dev.dorrian.agenticskillscli.flow.UninstallWizard;
import dev.dorrian.agenticskillscli.ui.Ansi;
import dev.dorrian.agenticskillscli.ui.Prompter;

import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;

import java.util.List;
import java.util.Map;

/**
 * CLI entrypoint — port of {@code bin/install.js}'s {@code main()} mode
 * dispatch (source read directly, lines ~2328-2390, on 2026-09-30).
 *
 * <p>All four modes are fully wired: {@link
 * dev.dorrian.agenticskillscli.flow.QuickInstallFlow}, {@link
 * dev.dorrian.agenticskillscli.flow.FullInstallFlow}, {@link
 * dev.dorrian.agenticskillscli.flow.TokenUpdateFlow}, and {@link
 * dev.dorrian.agenticskillscli.flow.UninstallWizard}. Both the top-level
 * "Uninstall" menu choice and the {@code --uninstall} CLI flag reach the
 * same {@code UninstallWizard}, matching how the original {@code main()}
 * unifies the two entry paths into a single {@code mode} variable.
 */
public final class App {

    private App() {
    }

    public static void main(String[] args) {
        if (containsFlag(args, "--version")) {
            // Before PackageRoot init: must not extract the bundle (Homebrew's formula test runs this).
            System.out.println(BundleExtractor.runningVersion());
            return;
        }
        if (args.length > 0 && "data".equals(args[0])) {
            // Non-interactive and independent of the bundle: no extraction, no prompts.
            System.exit(DataCommands.runDefault(java.util.List.of(args).subList(1, args.length)));
        }
        if (args.length > 0 && "verify".equals(args[0])) {
            System.exit(VerifyCommands.runDefault(java.util.List.of(args).subList(1, args.length)));
        }
        if (args.length > 0 && "dashboard".equals(args[0])) {
            System.exit(DashboardCommand.run(java.util.List.of(args).subList(1, args.length),
                dev.dorrian.usagestore.UsageDb.defaultPath(), System.out, System.err));
        }
        PackageRoot.initFromArgs(args);

        boolean uninstallRequested = containsFlag(args, "--uninstall");

        try (Prompter prompter = new Prompter()) {
            String mode;
            if (uninstallRequested) {
                printBanner();
                mode = "uninstall";
            } else {
                printBanner();
                mode = promptTopLevelMenu(prompter);
            }

            System.out.println("  " + Ansi.dim("Discovering skills & agents…"));
            List<SkillDescriptor> availableSkills = SkillDiscovery.discover();
            List<AgentDescriptor> availableAgentFiles = AgentDiscovery.discover();
            Map<String, Boolean> detectedTools = InstalledToolDetector.detect();

            if (availableSkills.isEmpty() && availableAgentFiles.isEmpty()) {
                System.out.println("  " + Ansi.yellow("No skills or agents found."));
                return;
            }

            switch (mode) {
                case "uninstall" -> UninstallWizard.run(prompter, availableSkills, availableAgentFiles, detectedTools);
                case "update-token" -> TokenUpdateFlow.run(prompter);
                case "install-quick" -> QuickInstallFlow.run(prompter, availableSkills, availableAgentFiles, detectedTools);
                default -> FullInstallFlow.run(prompter, availableSkills, availableAgentFiles, detectedTools);
            }
        } catch (UserInterruptException e) {
            System.out.println();
            System.out.println("  " + Ansi.dim("Cancelled."));
            System.exit(130);
        } catch (EndOfFileException e) {
            System.out.println();
            System.out.println("  " + Ansi.dim("No input received — exiting."));
            System.exit(1);
        }
    }

    private static boolean containsFlag(String[] args, String flag) {
        for (String arg : args) {
            if (flag.equals(arg)) return true;
        }
        return false;
    }

    private static String promptTopLevelMenu(Prompter prompter) {
        List<String> choices = List.of(
            Ansi.boldGreen("Quick install") + " — select tools, install everything globally (recommended)",
            Ansi.green("Install") + "   — add skills, agents, and commands",
            Ansi.yellow("Update token") + " — replace an expired PAT for issue-tickets",
            Ansi.red("Uninstall") + " — remove skills, agents, and commands"
        );
        String chosen = prompter.list("What do you want to do?", choices);
        if (chosen.startsWith(Ansi.boldGreen("Quick install"))) return "install-quick";
        if (chosen.startsWith(Ansi.yellow("Update token"))) return "update-token";
        if (chosen.startsWith(Ansi.red("Uninstall"))) return "uninstall";
        return "install";
    }

    private static void printBanner() {
        System.out.println();
        System.out.println("  ╔═══════════════════════════════════════╗");
        System.out.println("  ║   Agentic  Skills Bundle Installer   ║");
        System.out.println("  ╚═══════════════════════════════════════╝");
        System.out.println();
        System.out.println("  " + Ansi.dim("Install AI agent skills for your projects or global setup."));
        System.out.println();
    }
}
