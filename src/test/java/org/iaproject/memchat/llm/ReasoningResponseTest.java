package org.iaproject.memchat.llm;

import com.sun.net.httpserver.HttpServer;
import org.iaproject.memchat.memory.strategy.OpenAICompactStrategy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ReasoningResponseTest {
    static Stream<Arguments> reasoningWithoutAnswer() {
        return Stream.of("reasoning", "reasoning_content").flatMap(field ->
                Stream.of("", "\"content\":null,", "\"content\":\"   \",")
                        .map(content -> Arguments.of(field, content)));
    }

    @ParameterizedTest
    @MethodSource("reasoningWithoutAnswer")
    void rejectsReasoningWithoutAFinalAnswer(String field, String content) throws Exception {
        HttpServer server = server("stop", content + "\"" + field + "\":\"I still need to inspect the files.\"");
        try {
            var exception = assertThrows(IllegalStateException.class,
                    () -> client(server).complete(List.of(LLmApiClient.ChatMessage.user("question")), List.of()));
            assertTrue(exception.getMessage().contains("incomplete completion"));
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"reasoning", "reasoning_content"})
    void keepsTheFinalAnswerWhenReasoningIsAlsoPresent(String field) throws Exception {
        HttpServer server = server("stop", "\"content\":\"final answer\",\"" + field + "\":\"internal trace\"");
        try {
            var response = client(server).complete(List.of(LLmApiClient.ChatMessage.user("question")), List.of());
            assertEquals("final answer", response.content());
            assertTrue(response.toolCalls().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"reasoning", "reasoning_content"})
    void acceptsActualToolCallsWithoutPromotingReasoningToContent(String field) throws Exception {
        HttpServer server = server("tool_calls", "\"content\":null,\"" + field + "\":\"inspect files\","
                + "\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"inspect\",\"arguments\":\"{}\"}}]");
        try {
            var response = client(server).complete(List.of(LLmApiClient.ChatMessage.user("question")), List.of());
            assertTrue(response.content().isBlank());
            assertEquals(List.of(new LLmApiClient.ToolCall("call-1", "inspect", "{}")), response.toolCalls());
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"reasoning", "reasoning_content"})
    void compactionCannotUseReasoningAsTheReplacementSummary(String field) throws Exception {
        HttpServer server = server("stop", "\"content\":null,\"" + field + "\":\"I should summarize the conversation.\"");
        try {
            var compactor = new OpenAICompactStrategy(client(server));
            assertThrows(IllegalStateException.class, () -> compactor.compact(
                    "Important facts to retain", List.of(LLmApiClient.ChatMessage.user("Another important fact"))));
        } finally {
            server.stop(0);
        }
    }

    private static OpenAICompatibleClient client(HttpServer server) {
        return new OpenAICompatibleClient("http://localhost:" + server.getAddress().getPort() + "/v1",
                "test", 0.1, "", HttpClient.newHttpClient());
    }

    private static HttpServer server(String finishReason, String messageFields) throws Exception {
        byte[] body = ("{\"choices\":[{\"finish_reason\":\"" + finishReason + "\",\"message\":{" + messageFields + "}}]}")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        return server;
    }
}
