package org.iaproject.memchat;

import org.iaproject.memchat.agent.AgentBuilder;
import org.iaproject.memchat.agent.AgentLoop;
import org.iaproject.memchat.cli.MemchatArg;
import org.iaproject.memchat.memory.impl.LuceneArchivalMemoryRepository;
import picocli.CommandLine;

public class Main {
    public static void main(String[] args) throws InterruptedException, JsonProcessingException {
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
        AgentLoop agentLoop = AgentBuilder.builder()
                .baseUrl(memchatArg.baseUrl)
                .model(memchatArg.model)
                .apiKey(memchatArg.apiKey)
                .maxSteps(memchatArg.maxSteps)
                .archivalMemoryRepository(new LuceneArchivalMemoryRepository())
                .userInteraction(console)
                .build();
        try {
            agentLoop.subscribe(event -> {
                switch (event.type()) {
                    case AGENT_MESSAGE -> System.out.println("Agent> " + event.message());
                    case TOOL_EXECUTION -> System.out.println("Execute: " + event.message());
                    default -> System.out.println(event.message());
                }
            });
            while (!shouldExit(instruction)) {
                if (!instruction.isBlank()) {
                    agentLoop.run(instruction);
                }
                instruction = console.read();
            }
        } finally {
            agentLoop.close();
        }
    }

    private static boolean shouldExit(String instruction) {
        return instruction == null || "/quit".equalsIgnoreCase(instruction.trim());
    }
}
