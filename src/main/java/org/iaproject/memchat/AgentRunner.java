package org.iaproject.memchat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.iaproject.memchat.action.ActionParser;
import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.llm.LLmApiClient.*;
import org.iaproject.memchat.llm.PromptBuilder;
import org.iaproject.memchat.tools.ToolParser;

import java.util.List;
import java.util.Locale;

public class AgentRunner {
    private final int maxStep;
    private final LLmApiClient client;
    private final List<ToolParser.ToolDescription> descriptions;
    private final ObjectMapper mapper;
    private final ActionParser actionParser;


    public AgentRunner(int maxStep, LLmApiClient client, Object... tools) {
        this.maxStep = maxStep;
        this.client = client;
        this.descriptions = ToolParser.parse(List.of(tools));
        this.mapper = new ObjectMapper();
        this.actionParser = new ActionParser();
    }

    public void run(String instruction) throws InterruptedException, JsonProcessingException {
        ChatMessage chatMessage = new LLmApiClient.ChatMessage(Role.SYSTEM, PromptBuilder.system(mapper.writeValueAsString(this.descriptions)));
        for (int i = 0; i < maxStep; i++) {

            ChatResponse result = client.complete(List.of(
                    chatMessage,
                    new ChatMessage(Role.USER, instruction)
            ));
            ActionParser.Action action = actionParser.parse(result.reponse());
            System.out.println(action.thought());
            System.out.println("Action: "+action.name().toUpperCase(Locale.ROOT)+" "+ action.args().values().stream().findAny().orElse(""));
            Thread.sleep(100);
        }
    }

}
