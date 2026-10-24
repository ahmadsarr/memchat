package org.iaproject.memchat.search.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.iaproject.memchat.search.api.SearchException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Optional client for OpenAI-compatible /embeddings endpoints, including Ollama's /v1 API. */
public final class OpenAiEmbeddingModel implements EmbeddingModel, AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper();
    private final URI endpoint;
    private final String model;
    private final String apiKey;
    private final int dimensions;
    private final HttpClient client;
    private final Duration timeout;

    public OpenAiEmbeddingModel(String baseUrl, String model, String apiKey, int dimensions) {
        this(baseUrl, model, apiKey, dimensions, null, Duration.ofSeconds(60));
    }

    /** Takes ownership of the supplied HTTP client; null creates a client with a 10-second connection timeout. */
    public OpenAiEmbeddingModel(String baseUrl, String model, String apiKey, int dimensions,
                                HttpClient client, Duration timeout) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("Base URL is required");
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/embeddings");
        if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Base URL must be an HTTP(S) API base URL");
        }
        if (model == null || model.isBlank()) throw new IllegalArgumentException("Model ID is required");
        if (dimensions <= 0) throw new IllegalArgumentException("Dimensions must be positive");
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Request timeout must be positive");
        }
        this.model = model;
        this.apiKey = apiKey;
        this.dimensions = dimensions;
        this.client = client == null
                ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() : client;
        this.timeout = timeout;
    }

    @Override
    public String modelId() { return model; }

    @Override
    public int dimensions() { return dimensions; }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Embedding text is required");
        try {
            String payload = mapper.writeValueAsString(mapper.createObjectNode()
                    .put("model", model).put("input", text)
                    .put("encoding_format", "float").put("dimensions", dimensions));
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload));
            if (apiKey != null && !apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new SearchException("Embedding endpoint returned HTTP " + response.statusCode(), null);
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode data = root == null ? null : root.get("data");
            if (data == null || !data.isArray() || data.size() != 1) {
                throw new SearchException("Embedding response must contain exactly one data item", null);
            }
            JsonNode values = data.get(0).get("embedding");
            if (values == null || !values.isArray() || values.size() != dimensions) {
                throw new SearchException("Embedding response must contain " + dimensions + " dimensions", null);
            }
            float[] vector = new float[dimensions];
            for (int i = 0; i < dimensions; i++) {
                if (!values.get(i).isNumber()) {
                    throw new SearchException("Embedding components must be numbers", null);
                }
                vector[i] = values.get(i).floatValue();
            }
            // Return a stable unit vector; this also rejects zero and overflowing components.
            return EmbeddingVectors.normalizedCopy(vector, dimensions);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SearchException("Embedding request interrupted", exception);
        } catch (IOException exception) {
            throw new SearchException("Unable to obtain embedding", exception);
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
