package org.iaproject.memchat;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.iaproject.memchat.cli.MemchatArg;
import org.iaproject.memchat.llm.OpenAICompatibleClient;
import org.iaproject.memchat.tools.MemoryTools;
import org.iaproject.memchat.tools.MemoryToolsImpl;
import org.iaproject.memchat.tools.UserInteractionTools;
import picocli.CommandLine;

import java.util.Scanner;

public class Main {

    public static void main(String[] args) throws InterruptedException, JsonProcessingException {
        MemchatArg memchatArg = new MemchatArg();
        CommandLine commandLine = new CommandLine(memchatArg);
        commandLine.parseArgs(args);
        OpenAICompatibleClient client = new OpenAICompatibleClient(memchatArg.baseUrl, memchatArg.model, memchatArg.apiKey);
        MemoryTools memoryTools = new MemoryToolsImpl();
        Scanner scanner = new Scanner(System.in);
        String instruction = isEmpty(memchatArg.instruction) ? scanner.nextLine() : memchatArg.instruction;
        AgentLoop agentLoop = new AgentLoop(memchatArg.maxSteps, client, memoryTools, new UserInteractionTools());
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
