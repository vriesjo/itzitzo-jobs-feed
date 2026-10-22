package com.itzitzo.jobsfeed;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.itzitzo.jobsfeed.config.AwsClientsConfig;
import com.itzitzo.jobsfeed.service.BedrockGateway;
import com.itzitzo.jobsfeed.service.BedrockService;
import com.itzitzo.jobsfeed.service.DynamoDbService;
import com.itzitzo.jobsfeed.service.JobStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class LambdaHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    private final BedrockGateway bedrockGateway;
    private final JobStore jobStore;

    public LambdaHandler() {
        String region = requiredEnv("AWS_REGION");
        String modelId = requiredEnv("BEDROCK_MODEL_ID");
        String tableName = requiredEnv("DYNAMODB_TABLE_NAME");
        this.bedrockGateway = new BedrockService(AwsClientsConfig.bedrockClient(region), modelId);
        this.jobStore = new DynamoDbService(AwsClientsConfig.dynamoDbClient(region), tableName);
    }

    LambdaHandler(BedrockGateway bedrockGateway, JobStore jobStore) {
        this.bedrockGateway = bedrockGateway;
        this.jobStore = jobStore;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        String action = stringValue(input, "action").orElse("health");
        context.getLogger().log("Processing action: " + action);
        switch (action) {
            case "saveJob":
                return saveJob(input);
            case "getJob":
                return getJob(input);
            case "bedrockInvoke":
                return invokeBedrock(input);
            case "health":
            default:
                return ok(Map.of("message", "ok"));
        }
    }

    private Map<String, Object> saveJob(Map<String, Object> input) {
        String pk = stringValue(input, "pk").orElse("job");
        String sk = stringValue(input, "sk").orElse(UUID.randomUUID().toString());
        String payload = stringValue(input, "payload").orElse("{}");
        jobStore.saveJob(pk, sk, payload);
        return ok(Map.of("pk", pk, "sk", sk));
    }

    private Map<String, Object> getJob(Map<String, Object> input) {
        String pk = stringValue(input, "pk").orElseThrow(() -> new IllegalArgumentException("Missing required field: pk"));
        String sk = stringValue(input, "sk").orElseThrow(() -> new IllegalArgumentException("Missing required field: sk"));
        Optional<String> payload = jobStore.getJob(pk, sk);
        if (payload.isEmpty()) {
            return error("not_found", Map.of("pk", pk, "sk", sk));
        }
        return ok(Map.of("pk", pk, "sk", sk, "payload", payload.get()));
    }

    private Map<String, Object> invokeBedrock(Map<String, Object> input) {
        String prompt = stringValue(input, "prompt").orElseThrow(() -> new IllegalArgumentException("Missing required field: prompt"));
        String response = bedrockGateway.invokePrompt(prompt);
        return ok(Map.of("response", response));
    }

    private Map<String, Object> ok(Map<String, Object> details) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.putAll(details);
        return result;
    }

    private Map<String, Object> error(String code, Map<String, Object> details) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "error");
        result.put("code", code);
        result.putAll(details);
        return result;
    }

    private Optional<String> stringValue(Map<String, Object> input, String key) {
        if (input == null || !input.containsKey(key) || input.get(key) == null) {
            return Optional.empty();
        }
        Object value = input.get(key);
        if (value instanceof String) {
            return Optional.of((String) value);
        }
        return Optional.of(value.toString());
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + key);
        }
        return value;
    }
}
