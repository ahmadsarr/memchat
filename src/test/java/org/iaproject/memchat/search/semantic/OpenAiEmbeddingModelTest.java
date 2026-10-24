package org.iaproject.memchat.search.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.iaproject.memchat.search.api.SearchDocument;
import org.iaproject.memchat.search.api.SearchException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiEmbeddingModelTest {
    @TempDir Path directory;

    @Test
    void sendsTheModelTextDimensionsAndAuthorization() throws Exception {
        var body = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"data\":[{\"index\":0,\"embedding\":[3,4]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try (var model = new OpenAiEmbeddingModel(baseUrl(server) + "/", "embedding-test", "test-key", 2)) {
            assertArrayEquals(new float[]{0.6f, 0.8f}, model.embed("Un texte français"), 0.0001f);
            var request = new ObjectMapper().readTree(body.get());
            assertEquals("embedding-test", request.path("model").asText());
            assertEquals("Un texte français", request.path("input").asText());
            assertEquals(2, request.path("dimensions").asInt());
            assertEquals("float", request.path("encoding_format").asText());
            assertEquals("Bearer test-key", authorization.get());
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "{\"data\":[]}", "{\"data\":[{}]}",
            "{\"data\":[{\"embedding\":[1]}]}", "{\"data\":[{\"embedding\":[0,0]}]}",
            "{\"data\":[{\"embedding\":[\"1\",2]}]}", "{\"data\":[{\"embedding\":[1e100,2]}]}",
            "{\"data\":[{\"embedding\":[1,2]},{\"embedding\":[1,2]}]}"})
    void rejectsMalformedEmbeddings(String response) throws Exception {
        HttpServer server = server(200, response, 0);
        try (var model = new OpenAiEmbeddingModel(baseUrl(server), "test", null, 2)) {
            assertThrows(SearchException.class, () -> model.embed("text"));
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 429, 500})
    void surfacesHttpFailures(int status) throws Exception {
        HttpServer server = server(status, "{}", 0);
        try (var model = new OpenAiEmbeddingModel(baseUrl(server), "test", null, 2)) {
            var failure = assertThrows(SearchException.class, () -> model.embed("text"));
            assertTrue(failure.getMessage().contains("HTTP " + status));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesTheInterruptFlag() throws Exception {
        HttpServer server = server(200, "{\"data\":[{\"embedding\":[1,0]}]}", 0);
        try (var model = new OpenAiEmbeddingModel(baseUrl(server), "test", null, 2)) {
            try {
                Thread.currentThread().interrupt();
                assertThrows(SearchException.class, () -> model.embed("text"));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void boundsTheRequestDuration() throws Exception {
        HttpServer server = server(200, "{\"data\":[{\"embedding\":[1,0]}]}", 500);
        try (var model = new OpenAiEmbeddingModel(baseUrl(server), "test", null, 2,
                HttpClient.newHttpClient(), Duration.ofMillis(50))) {
            var failure = assertThrows(SearchException.class, () -> model.embed("text"));
            assertInstanceOf(HttpTimeoutException.class, failure.getCause());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void obtainsVectorsOverHttpAndSearchesTheStandaloneIndex() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            String input = new ObjectMapper().readTree(exchange.getRequestBody()).path("input").asText();
            String vector = input.equals("bread recipe") ? "[0,1]" : "[1,0]";
            byte[] response = ("{\"data\":[{\"embedding\":" + vector + "}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try (var model = new OpenAiEmbeddingModel(baseUrl(server), "test", null, 2);
             var search = new LuceneSemanticSearch(directory, model)) {
            search.index(new SearchDocument("transport", "automobile repair", Map.of()));
            search.index(new SearchDocument("food", "bread recipe", Map.of()));
            assertEquals("transport", search.search("fix my car", 1).results().getFirst().document().id());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsInvalidConfigurationAndEmptyText() {
        assertThrows(IllegalArgumentException.class, () -> new OpenAiEmbeddingModel("file:///tmp", "test", null, 2));
        assertThrows(IllegalArgumentException.class, () -> new OpenAiEmbeddingModel("http://localhost/v1", "", null, 2));
        assertThrows(IllegalArgumentException.class, () -> new OpenAiEmbeddingModel("http://localhost/v1", "test", null, 0));
        try (var model = new OpenAiEmbeddingModel("http://localhost/v1", "test", null, 2)) {
            assertThrows(IllegalArgumentException.class, () -> model.embed(" "));
        }
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static HttpServer server(int status, String body, int delayMillis) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try { Thread.sleep(delayMillis); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        return server;
    }
}
