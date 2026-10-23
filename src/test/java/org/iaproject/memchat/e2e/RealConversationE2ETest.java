package org.iaproject.memchat.e2e;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.iaproject.memchat.Main;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Black-box conversation test. It intentionally uses the configured real model
 * and real persistence instead of replacing any application component.
 *
 * Enable explicitly with: -Dmemchat.e2e=true
 *
 * Optional environment variables:
 * MEMCHAT_E2E_BASE_URL (default: http://127.0.0.1:11434/v1)
 * MEMCHAT_E2E_API_KEY (default: ollama)
 * MEMCHAT_E2E_MAX_STEPS (default: 20)
 */
@Tag("e2e")
@EnabledIfSystemProperty(named = "memchat.e2e", matches = "true")
class RealConversationE2ETest {
    @TempDir
    Path workingDirectory;

    @Test
    void realConversationStoresAndUsesLongTermMemory() throws Exception {
        String model = environment("MEMCHAT_E2E_MODEL", "qwen3.5:9b");
        String baseUrl = environment("MEMCHAT_E2E_BASE_URL", "http://127.0.0.1:11434/v1");
        String apiKey = environment("MEMCHAT_E2E_API_KEY", "ollama");
        String maxSteps = environment("MEMCHAT_E2E_MAX_STEPS", "20");

        List<Question> questions = loadQuestions();
        List<Question> facts = questions.subList(0, 3);
        List<Question> recallQuestions = questions.subList(3, questions.size());
        SessionResult writeSession = runSession(facts, model, baseUrl, apiKey, maxSteps);
        SessionResult readSession = runSession(recallQuestions, model, baseUrl, apiKey, maxSteps);
        Path transcriptFile = saveTranscript(facts, writeSession, recallQuestions, readSession);

        assertFalse(writeSession.output().isBlank(), "The write session produced no observation: " + transcriptFile);
        assertFalse(readSession.output().isBlank(), "The recall session produced no observation: " + transcriptFile);
        assertTrue(readSession.output().contains("Execute: archivalSearch"),
                "The second session did not observe an archival memory search: " + transcriptFile);
        assertTrue(Files.exists(workingDirectory.resolve("memory-index")),
                "The real archival index was not created: " + transcriptFile);
        assertTrue(Files.exists(workingDirectory.resolve("recall.json")),
                "The real recall storage was not created: " + transcriptFile);
    }

    private String runSession(List<Question> questions, String model, String baseUrl,
                              String apiKey, String maxSteps) throws Exception {
        String conversation = String.join(System.lineSeparator(),
                questions.stream().map(Question::q).toList())
                + System.lineSeparator() + "/quit" + System.lineSeparator();
        Process process = new ProcessBuilder(
                javaExecutable(), "-cp", System.getProperty("java.class.path"), Main.class.getName(),
                "--model", model, "--base-url", baseUrl, "--api-key", apiKey,
                "--max-steps", maxSteps
        ).directory(workingDirectory.toFile()).redirectErrorStream(true).start();
        CompletableFuture<String> transcriptFuture = CompletableFuture.supplyAsync(() -> readOutput(process));
        try {
            process.getOutputStream().write(conversation.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            assertTrue(process.waitFor(5, TimeUnit.MINUTES), "MemChat did not terminate");
            String transcript = transcriptFuture.get(10, TimeUnit.SECONDS);
            return new SessionResult(process.exitValue(), transcript);
        } finally {
            process.destroyForcibly();
        }
    }

    private List<Question> loadQuestions() throws IOException {
        try (var input = getClass().getResourceAsStream("/e2e/conversation.json")) {
            assertNotNull(input, "The E2E conversation file is missing");
            return new ObjectMapper().readValue(input, new TypeReference<>() { });
        }
    }

    private Path saveTranscript(List<Question> writeInputs, SessionResult writeSession,
                                List<Question> recallInputs, SessionResult recallSession) throws IOException {
        Path directory = Path.of("target", "e2e-transcripts");
        Files.createDirectories(directory);
        Path file = directory.resolve("memory-conversation-" + System.currentTimeMillis() + ".log");
        String observed = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(
                new Transcript(writeInputs, writeSession, recallInputs, recallSession));
        Files.writeString(file, observed, StandardCharsets.UTF_8);
        return file;
    }

    private static String readOutput(Process process) {
        try {
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to capture MemChat transcript", exception);
        }
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private record Question(String q) { }

    private record SessionResult(int exitCode, String output) { }

    private record Transcript(
            List<Question> writeInputs,
            SessionResult writeSession,
            List<Question> recallInputs,
            SessionResult recallSession
    ) { }
}
