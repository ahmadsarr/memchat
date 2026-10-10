package org.iaproject.memchat;

public class OpenAICompatibleClient {
    private final String baseUrl;
    private final String model;
    private Long temperature;
    private


    public OpenAICompatibleClient(String baseUrl, String model) {
        this.baseUrl = baseUrl;
        this.model = model;
    }
}
