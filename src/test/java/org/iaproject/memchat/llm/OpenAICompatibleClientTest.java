package org.iaproject.memchat.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenAICompatibleClientTest {
    @Test
    void sendsOpenAiMessagesAndParsesToolCalls() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"choices":[{"message":{"content":"","tool_calls":[{"id":"call-1","type":"function","function":{"name":"echo","arguments":"{\\"value\\":\\"hello\\"}"}}]}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        try {
            var client = new OpenAICompatibleClient(
                    "http://localhost:" + server.getAddress().getPort() + "/v1",
                    "test-model",
                    0.1,
                    "test-key",
                    HttpClient.newHttpClient()
            );

            var response = client.complete(
                    List.of(LLmApiClient.ChatMessage.user("hello")),
                    List.of()
            );

            assertEquals(1, response.toolCalls().size());
            assertEquals("echo", response.toolCalls().getFirst().name());
            assertEquals("call-1", response.toolCalls().getFirst().id());
            assertTrue(requestBody.get().contains("\"parallel_tool_calls\":false"));
        } finally {
            server.stop(0);
        }
    }
}
