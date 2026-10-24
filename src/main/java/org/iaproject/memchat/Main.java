package org.iaproject.memchat;

import org.iaproject.memchat.agent.AgentBuilder;
import org.iaproject.memchat.agent.AgentLoop;
import org.iaproject.memchat.agent.AgentRunState;
import org.iaproject.memchat.cli.MemchatArg;
import org.iaproject.memchat.memory.impl.FileArchivalMemoryRepository;
import org.iaproject.memchat.memory.impl.LuceneArchivalMemoryRepository;
import picocli.CommandLine;
import java.io.IOException;

public class Main {
    public static void main(String[] args) throws IOException {
        MemchatArg memchatArg = new MemchatArg();
        CommandLine commandLine = new CommandLine(memchatArg);
        CommandLine.ParseResult parsed = commandLine.parseArgs(args);
        if (parsed.isUsageHelpRequested()) {
            commandLine.usage(System.out);
            return;
        }
        if (parsed.isVersionHelpRequested()) {
            commandLine.printVersionHelp(System.out);
            return;
        }
        if (memchatArg.dryRun) {
            System.out.println("Dry run: model=" + memchatArg.model + ", maxSteps=" + memchatArg.maxSteps);
            return;
        }

        UserInteraction console = new ConsoleUserInteraction();
        String instruction = memchatArg.instruction != null ? memchatArg.instruction : console.read();
        while (instruction != null && instruction.isBlank()) {
            instruction = console.read();
        }
        if (shouldExit(instruction)) {
            return;
        }
        try (LuceneArchivalMemoryRepository archive = memchatArg.archiveIndex == null ? null
                : new LuceneArchivalMemoryRepository(memchatArg.archiveIndex);
             AgentLoop agentLoop = AgentBuilder.builder()
                .baseUrl(memchatArg.baseUrl)
                .model(memchatArg.model)
                .apiKey(memchatArg.apiKey)
                .maxSteps(memchatArg.maxSteps)
                .archivalMemoryRepository(archive == null ? new FileArchivalMemoryRepository() : archive)
                .userInteraction(console)
                .build()) {
            agentLoop.subscribe(event -> {
                switch (event.type()) {
                    case AGENT_MESSAGE, RUN_COMPLETED -> { /* Printed from the direct result below. */ }
                    case TOOL_COMPLETED -> { /* Detailed tool outcomes are consumed by the TUI. */ }
                    case TOOL_EXECUTION -> System.out.println("Execute: " + event.message());
                    default -> System.out.println(event.message());
                }
            });
            while (!shouldExit(instruction)) {
                if (!instruction.isBlank()) {
                    var result = agentLoop.run(instruction);
                    if (result.status() == AgentRunState.SUCCEEDED) {
                        System.out.println("Agent> " + result.finalResponse());
                    } else {
                        System.err.println("Agent: " + result.status()
                                + (result.failure() == null ? "" : " - " + result.failure().message()));
                    }
                    if (result.recoveryRequired()
                            || result.status() == AgentRunState.CANCELLED) break;
                }
                instruction = console.read();
            }
        }
    }

    private static boolean shouldExit(String instruction) {
        return instruction == null || "/quit".equalsIgnoreCase(instruction.trim());
    }
}
