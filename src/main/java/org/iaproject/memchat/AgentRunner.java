package org.iaproject.memchat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.iaproject.memchat.action.ActionExecutor;
import org.iaproject.memchat.action.ActionParser;
import org.iaproject.memchat.llm.LLmApiClient;
import org.iaproject.memchat.llm.LLmApiClient.*;
import org.iaproject.memchat.llm.PromptBuilder;
import org.iaproject.memchat.tools.ToolParser;
import org.iaproject.memchat.tools.ToolResult;

import java.util.ArrayList;
import java.util.List;

public class AgentRunner {
    private final int maxStep;
    private final LLmApiClient client;
    private final ObjectMapper mapper;
    private final ActionExecutor actionExecutor;
    private final ActionParser actionParser;


    public AgentRunner(int maxStep, LLmApiClient client, Object... tools) {
        this.maxStep = maxStep;
        this.client = client;
        this.mapper = new ObjectMapper();
        this.actionExecutor = new ActionExecutor(ToolParser.parse(List.of(tools)));
        this.actionParser = new ActionParser();
    }

    public void run(String instruction)
            throws InterruptedException, JsonProcessingException {

        List<ChatMessage> messages = new ArrayList<>();

        messages.add(new ChatMessage(
                Role.SYSTEM,
                PromptBuilder.system(
                        mapper.writeValueAsString(
                                actionExecutor.getToolsDescriptions()
                        )
                )
        ));

        messages.add(new ChatMessage(Role.USER, instruction));

        for (int i = 0; i < maxStep; i++) {

            ChatResponse response = client.complete(messages);
            String output = response.reponse();

            messages.add(new ChatMessage(Role.ASSISTANT, output));
            ActionParser.Action action = actionParser.parse(output);
            System.out.println(action.thought());
            System.out.println("Execute " + action.name());
            ToolResult result = actionExecutor.execute(action);

            messages.add(new ChatMessage(
                    Role.USER,
                    "TOOL_RESULT:\n" + mapper.writeValueAsString(result)
            ));

            if (i == maxStep - 1) {
                System.out.println("Maximum steps reached");
            }

            Thread.sleep(100);
        }
    }

}
