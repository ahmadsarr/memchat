package org.iaproject.memchat.memory.strategy;

import org.iaproject.memchat.llm.LLmApiClient;

import java.util.List;

public class ContextControlStrategyImpl implements ContextControlStrategy {
    private static final int DEFAULT_MAX_TOKENS = 8_000;
    private static final int DEFAULT_RESPONSE_RESERVE = 1_000;
    private static final double DEFAULT_WARNING_RATIO = 0.80;
    private static final double DEFAULT_FLUSH_RATIO = 0.95;

    private final int warningTokens;
    private final int flushTokens;

    public ContextControlStrategyImpl() {
        this(DEFAULT_MAX_TOKENS, DEFAULT_RESPONSE_RESERVE, DEFAULT_WARNING_RATIO, DEFAULT_FLUSH_RATIO);
    }

    public ContextControlStrategyImpl(
            int maxTokens,
            int responseReserve,
            double warningRatio,
            double flushRatio
    ) {
        if (responseReserve < 0
                || responseReserve >= maxTokens
                || warningRatio <= 0
                || warningRatio >= flushRatio
                || flushRatio > 1) {
            throw new IllegalArgumentException(
                    "Invalid context control thresholds"
            );
        }

        int availableTokens = maxTokens - responseReserve;

        this.warningTokens =
                (int) (availableTokens * warningRatio);

        this.flushTokens =
                (int) (availableTokens * flushRatio);
    }

    @Override
    public Pressure pressure(List<LLmApiClient.ChatMessage> messages) {
        int estimatedTokens = estimateTokens(messages);
        if (estimatedTokens >= flushTokens) {
            return Pressure.FLUSH_REQUIRED;
        }
        if (estimatedTokens >= warningTokens) {
            return Pressure.WARNING;
        }
        return Pressure.NORMAL;
    }

    @Override
    public int estimateTokens(List<LLmApiClient.ChatMessage> messages) {
        if (messages == null) {
            return 0;
        }
        return messages.stream()
                .mapToInt(this::tokenByMessage)
                .sum();
    }

    private int tokenByMessage(LLmApiClient.ChatMessage message) {
        if (message == null)
            return 0;
        String content = message.content();
        List<LLmApiClient.ToolCall> toolCalls = message.toolCalls();
        int count = 0;
        if (content != null) {
            count += content.length();
        }
        if (toolCalls != null) {
            count += toolCalls.stream().mapToInt(t -> len(t.arguments()) + len(t.name()) + len(t.id())).sum();
        }
        int estimation = (count + 3) / 4;
        return estimation + 4;
    }

    int len(String n) {
        return n == null ? 0 : n.length();
    }
}
