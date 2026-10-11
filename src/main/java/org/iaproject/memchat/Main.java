package org.iaproject.memchat;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.iaproject.memchat.cli.MemchatArg;
import org.iaproject.memchat.llm.OpenAICompatibleClient;
import org.iaproject.memchat.tools.MemoryTools;
import org.iaproject.memchat.memory.MemoryToolsImpl;
import org.iaproject.memchat.memory.MemoryManager;
import org.iaproject.memchat.memory.impl.FileArchivalMemoryImpl;
import org.iaproject.memchat.memory.impl.FileRecallStorageImpl;
import org.iaproject.memchat.memory.impl.ContextControlImpl;
import org.iaproject.memchat.memory.impl.OpenAICompactStrategy;
import org.iaproject.memchat.memory.impl.WorkingMemoryImpl;
import org.iaproject.memchat.tools.UserInteractionTools;
import picocli.CommandLine;

import java.util.Scanner;

public class Main {

    public static void main(String[] args) throws InterruptedException, JsonProcessingException {
        MemchatArg memchatArg = new MemchatArg();
        CommandLine commandLine = new CommandLine(memchatArg);
        commandLine.parseArgs(args);
        OpenAICompatibleClient client = new OpenAICompatibleClient(memchatArg.baseUrl, memchatArg.model, memchatArg.apiKey);
        MemoryManager memoryManager = new MemoryManager(
                new WorkingMemoryImpl(),
                new FileRecallStorageImpl(),
                new FileArchivalMemoryImpl(),
                new ContextControlImpl(),
                new OpenAICompactStrategy(client)
        );
        MemoryTools memoryTools = new MemoryToolsImpl(memoryManager);
        Scanner scanner = new Scanner(System.in);
        String instruction = isEmpty(memchatArg.instruction) ? scanner.nextLine() : memchatArg.instruction;
        AgentLoop agentLoop = new AgentLoop(
                memchatArg.maxSteps,
                client,
                memoryManager,
                memoryTools,
                new UserInteractionTools()
        );
        while (true) {

            agentLoop.run(instruction);
            System.out.print("User:");
            instruction = scanner.nextLine();
            if ("/quit".equalsIgnoreCase(instruction))
                return;

        }
    }

    private static boolean isEmpty(String str) {
        return str == null || str.isBlank();
    }


}
