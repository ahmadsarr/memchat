package org.iaproject.memchat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MainIntegrationTest {
    @TempDir Path directory;

    static Stream<Arguments> noRequestCases() {
        return Stream.of(
                Arguments.of(List.of("--help"), ""),
                Arguments.of(List.of("--model", "test", "--dry-run"), "question\n/quit\n"),
                Arguments.of(List.of("--model", "test"), ""),
                Arguments.of(List.of("--model", "test"), "/quit\n"),
                Arguments.of(List.of("--model", "test"), "\n/quit\n"));
    }

    @ParameterizedTest
    @MethodSource("noRequestCases")
    void exitsWithoutCallingTheModelOrWritingMemory(List<String> args, String input) throws Exception {
        var requests = new AtomicInteger();
        var server = server(requests, new AtomicReference<>());
        try {
            run(args, input, server);
            assertEquals(0, requests.get());
            assertFalse(Files.exists(directory.resolve("recall.json")));
            assertFalse(Files.exists(directory.resolve("memory.json")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesTheInstructionOptionAndExitsCleanlyAtEndOfInput() throws Exception {
        var requests = new AtomicInteger();
        var body = new AtomicReference<String>();
        var server = server(requests, body);
        try {
            String output = run(List.of("--model", "test", "--instruction", "configured instruction"), "", server);
            assertEquals(1, requests.get());
            var messages = new ObjectMapper().readTree(body.get()).path("messages");
            assertEquals("configured instruction", messages.get(messages.size() - 1).path("content").asText());
            assertTrue(output.contains("server answer"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void interactiveConversationStopsBeforeSendingQuitToTheModel() throws Exception {
        var requests = new AtomicInteger();
        var server = server(requests, new AtomicReference<>());
        try {
            run(List.of("--model", "test"), "question\n/quit\n", server);
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    private String run(List<String> args, String input, HttpServer server) throws Exception {
        String classpath = java.util.Arrays.stream(System.getProperty("java.class.path")
                .split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .map(path -> Path.of(path).toAbsolutePath().toString())
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath, Main.class.getName()));
        command.addAll(args);
        command.addAll(List.of("--base-url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"));
        Path log = directory.resolve("console.log");
        var process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            try (var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "CLI timed out");
            String output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            return output;
        } finally {
            process.destroyForcibly();
        }
    }

    private HttpServer server(AtomicInteger requests, AtomicReference<String> body) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.incrementAndGet();
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"server answer\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(response);
            }
        });
        server.start();
        return server;
    }
}
