package org.iaproject.memchat;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.iaproject.memchat.cli.MemchatArg;
import org.iaproject.memchat.llm.LLmApiClient.*;
import org.iaproject.memchat.llm.OpenAICompatibleClient;
import org.iaproject.memchat.llm.PromptBuilder;
import org.iaproject.memchat.tools.MemoryTools;
import org.iaproject.memchat.tools.MemoryToolsImpl;
import picocli.CommandLine;

import java.util.List;
import java.util.Map;

public class Main {

    public static void main(String[] args) throws InterruptedException, JsonProcessingException {
        MemchatArg memchatArg = new MemchatArg();
        CommandLine commandLine = new CommandLine(memchatArg);
        commandLine.parseArgs(args);
        OpenAICompatibleClient client = new OpenAICompatibleClient(memchatArg.baseUrl, memchatArg.model, memchatArg.apiKey);
        MemoryTools memoryTools = new MemoryToolsImpl();
        new AgentRunner(memchatArg.maxSteps, client,memoryTools)
                .run(memchatArg.instruction);
    }



}
