package org.iaproject.memchat.tui;

import org.iaproject.memchat.agent.AgentBuilder;
import org.iaproject.memchat.agent.AgentLoop;
import org.iaproject.memchat.cli.MemchatArg;
import picocli.CommandLine;

import java.io.IOException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TuiMain {
    private TuiMain() {
    }

    public static void main(String[] args) {
        silenceLogging();
        MemchatArg options = new MemchatArg();
        CommandLine commandLine = new CommandLine(options);
        CommandLine.ParseResult parsed = commandLine.parseArgs(args);
        if (parsed.isUsageHelpRequested()) {
            commandLine.usage(System.out);
            return;
        }
        if (parsed.isVersionHelpRequested()) {
            commandLine.printVersionHelp(System.out);
            return;
        }
        if (options.dryRun) {
            System.out.println("Dry run: model=" + options.model + ", maxSteps=" + options.maxSteps);
            return;
        }

        TuiUserInteraction interaction = new TuiUserInteraction();
        try (AgentLoop agent = AgentBuilder.builder()
                .baseUrl(options.baseUrl)
                .model(options.model)
                .apiKey(options.apiKey)
                .maxSteps(options.maxSteps)
                .userInteraction(interaction)
                .build()) {
            new TuiApplication(agent, options.model, new TuiRenderer(), interaction)
                    .run(options.instruction);
        } catch (IOException exception) {
            System.err.println("Unable to start TUI: " + exception.getMessage());
        }
    }

    private static void silenceLogging() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.OFF);
        for (Handler handler : root.getHandlers()) {
            handler.setLevel(Level.OFF);
        }
    }
}
