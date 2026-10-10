package org.iaproject.memchat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import org.iaproject.memchat.cli.MemchatArg;
import org.iaproject.memchat.llm.LLmApiClient;

import java.util.Arrays;
import java.util.List;

public class agentRunner {
    private final int maxStep;
    private final List<Object> tools;
    private final LLmApiClient client;


    @Builder
    public agentRunner(int maxStep, LLmApiClient client, Object... tools) {
        this.maxStep = maxStep;
        this.tools = Arrays.asList(tools);
        this.client = client;
    }

    public void run(String instruction) throws InterruptedException {
        for (int i = 0; i < maxStep; i++) {


            Thread.sleep(100);
        }
    }
}
