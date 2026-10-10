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

public class AgentLoop {
    private final int maxStep;
    private final LLmApiClient client;
    private final ObjectMapper mapper;
    private final ActionExecutor actionExecutor;
    private final ActionParser actionParser;
    private final  List<ChatMessage> messages;

    public AgentLoop(int maxStep, LLmApiClient client, Object... tools) throws JsonProcessingException {
        this.maxStep = maxStep;
        this.client = client;
        this.mapper = new ObjectMapper();
        this.actionExecutor = new ActionExecutor(ToolParser.parse(List.of(tools)));
        this.actionParser = new ActionParser();
        this.messages = new ArrayList<>();
        messages.add(ChatMessage.system(
                PromptBuilder.SYSTEM
        ));
    }

    public void run(String instruction)
            throws InterruptedException, JsonProcessingException {

        messages.add(ChatMessage.user(instruction));

        for (int i = 0; i < maxStep; i++) {

            ChatResponse response = client.complete(
                    List.copyOf(messages),
                    actionExecutor.getToolsDescriptions()
            );

            if (response.toolCalls() == null
                    || response.toolCalls().isEmpty()) {

                messages.add(ChatMessage.assistant(response.content()));

                System.out.println("Agent: " + response.content());
                return;
            }

            messages.add(ChatMessage.assistant(
                    response.content(),
                    response.toolCalls()
            ));

            for (ToolCall call : response.toolCalls()) {

                System.out.println("Execute: " + call.name());

                ToolResult result = actionExecutor.execute(
                        call.name(),
                        call.arguments()
                );

                messages.add(ChatMessage.tool(
                        call.id(),
                        mapper.writeValueAsString(result)
                ));
            }

            Thread.sleep(100);
        }

        System.out.println("Maximum steps reached");
    }

}
