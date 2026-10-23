package org.iaproject.memchat.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Disabled
class LlmFailureTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "", "null", "{}", "{\"choices\":[]}",
            "{\"choices\":[{}]}",
            "{\"choices\":[{\"message\":{\"content\":null}}]}",
            "{\"choices\":[{\"message\":{\"content\":\"   \"}}]}",
            "{\"choices\":[{\"message\":{\"content\":123}}]}",
            "{\"choices\":[{\"message\":{\"content\":\"answer\",\"tool_calls\":{}}}]}",
            "{\"choices\":[{\"message\":{\"tool_calls\":[{}]}}]}",
            "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"\",\"function\":{\"name\":\"echo\",\"arguments\":\"{}\"}}]}}]}",
            "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"one\",\"function\":{\"name\":\"\",\"arguments\":\"{}\"}}]}}]}",
            "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"one\",\"function\":{\"name\":\"echo\",\"arguments\":{}}}]}}]}",
            "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}",
            "{\"choices\":[{\"finish_reason\":\"content_filter\",\"message\":{\"content\":\"partial\"}}]}",
            "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"content\":\"thinking\"}}]}"
    })
    void rejectsInvalidOrIncompleteSuccessfulHttpResponses(String body) throws Exception {
        HttpServer server = server(200, body);
        try {
            var failure = assertThrows(IllegalStateException.class, () -> complete(client(server)));
            assertTrue(failure.getMessage().contains("LLM response"), failure.toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsATextCompletion() throws Exception {
        HttpServer server = server(200,
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"answer\"}}]}");
        try {
            assertEquals("answer", complete(client(server)).content());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesTheInterruptFlagWhenTheRequestIsCancelled() throws Exception {
        HttpServer server = server(200, "{\"choices\":[]}");
        try {
            var client = client(server);
            Thread.currentThread().interrupt();
            assertThrows(RuntimeException.class, () -> complete(client));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            server.stop(0);
        }
    }

    private static LLmApiClient.ChatResponse complete(OpenAICompatibleClient client) {
        return client.complete(List.of(LLmApiClient.ChatMessage.user("hello")), List.of());
    }

    private static OpenAICompatibleClient client(HttpServer server) {
        return new OpenAICompatibleClient("http://localhost:" + server.getAddress().getPort() + "/v1",
                "test", 0.1, "", HttpClient.newHttpClient());
    }

    private static HttpServer server(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        return server;
    }
}
