package org.iaproject.memchat;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.iaproject.memchat.agent.AgentBuilder;
import org.iaproject.memchat.agent.AgentLoop;
import org.iaproject.memchat.cli.MemchatArg;
import picocli.CommandLine;


public class Main {

    public static void main(String[] args) throws InterruptedException, JsonProcessingException {
        MemchatArg memchatArg = new MemchatArg();
        CommandLine commandLine = new CommandLine(memchatArg);
        commandLine.parseArgs(args);
        UserInteraction console = new ConsoleUserInteraction();
        String instruction = console.read();
        AgentLoop agentLoop = AgentBuilder.builder()
                .baseUrl(memchatArg.baseUrl)
                .model(memchatArg.model)
                .apiKey(memchatArg.apiKey)
                .maxSteps(memchatArg.maxSteps)
                .userInteraction(console)
                .build();
        agentLoop.subscribe(event -> {
            switch (event.type()) {
                case AGENT_MESSAGE -> System.out.println("Agent: " + event.message());
                case TOOL_EXECUTION -> System.out.println("Execute: " + event.message());
                default -> System.out.println(event.message());
            }
        });
        while (true) {
            agentLoop.run(instruction);
            instruction = console.read();
            ;
            if ("/quit".equalsIgnoreCase(instruction))
                return;

        }
    }


}
