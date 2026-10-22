package com.itzitzo.jobsfeed.service;

import java.nio.charset.StandardCharsets;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

public class BedrockService implements BedrockGateway {
    private final BedrockRuntimeClient client;
    private final String modelId;

    public BedrockService(BedrockRuntimeClient client, String modelId) {
        this.client = client;
        this.modelId = modelId;
    }

    @Override
    public String invokePrompt(String prompt) {
        String payload = "{\"inputText\":\"" + escapeJson(prompt) + "\"}";
        InvokeModelRequest request = InvokeModelRequest.builder()
            .modelId(modelId)
            .contentType("application/json")
            .accept("application/json")
            .body(SdkBytes.fromString(payload, StandardCharsets.UTF_8))
            .build();
        InvokeModelResponse response = client.invokeModel(request);
        return response.body().asUtf8String();
    }

    private String escapeJson(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
