package com.itzitzo.jobsfeed;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itzitzo.jobsfeed.config.AwsClientsConfig;
import com.itzitzo.jobsfeed.model.SesMessage;
import com.itzitzo.jobsfeed.service.BedrockGateway;
import com.itzitzo.jobsfeed.service.BedrockService;
import com.itzitzo.jobsfeed.service.DynamoDbService;
import com.itzitzo.jobsfeed.service.EmailIngestionService;
import com.itzitzo.jobsfeed.service.HttpFeedHandler;
import com.itzitzo.jobsfeed.service.JobStore;
import com.itzitzo.jobsfeed.service.RssRenderer;
import com.itzitzo.jobsfeed.service.S3EmailReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LambdaHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(LambdaHandler.class);
    private final BedrockGateway bedrockGateway;
    private final JobStore jobStore;
    private final EmailIngestionService ingestion;
    private final HttpFeedHandler feed;

    public LambdaHandler() {
        String region = requiredEnv("AWS_REGION");
        String modelId = requiredEnv("BEDROCK_MODEL_ID");
        String tableName = requiredEnv("DYNAMODB_TABLE_NAME");
        BedrockService bedrock = new BedrockService(AwsClientsConfig.bedrockClient(region), modelId);
        this.bedrockGateway = bedrock;
        this.jobStore = new DynamoDbService(AwsClientsConfig.dynamoDbClient(region), tableName);
        this.ingestion = new EmailIngestionService(new S3EmailReader(AwsClientsConfig.s3Client(region),
            requiredEnv("EMAIL_BUCKET_NAME"), requiredEnv("EMAIL_OBJECT_PREFIX")), bedrock, jobStore);
        this.feed = new HttpFeedHandler(jobStore, new RssRenderer(
            requiredEnv("FEED_TITLE"), requiredEnv("FEED_DESCRIPTION"),
            requiredEnv("FEED_WEBSITE_URL"), requiredEnv("FEED_PUBLIC_URL")));
    }

    LambdaHandler(BedrockGateway bedrockGateway, JobStore jobStore, EmailIngestionService ingestion, HttpFeedHandler feed) {
        this.bedrockGateway = bedrockGateway;
        this.jobStore = jobStore;
        this.ingestion = ingestion;
        this.feed = feed;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        JsonNode event = JSON.valueToTree(input == null ? Map.of() : input);
        if (event.has("requestContext") || event.has("version") || event.has("httpMethod") || event.has("rawPath")) {
            return feed.handle(event);
        }
        if (event.has("Records")) {
            int count = 0;
            int duplicates = 0;
            try {
                for (SesMessage message : SesMessage.parseRecords(event.path("Records"))) {
                    long start = System.nanoTime();
                    LOG.info("event=email_started messageId={} requestId={}", message.messageId(),
                        context == null ? "local" : context.getAwsRequestId());
                    EmailIngestionService.Result result = ingestion.ingest(message.messageId(), message.receivedAt());
                    count += result.jobCount();
                    duplicates += result.duplicate() ? 1 : 0;
                    LOG.info("event=email_processed messageId={} jobCount={} duplicate={} durationMs={}",
                        message.messageId(), result.jobCount(), result.duplicate(), (System.nanoTime() - start) / 1_000_000);
                }
            } catch (RuntimeException e) {
                LOG.error("event=ingestion_failed requestId={} failureType={}",
                    context == null ? "local" : context.getAwsRequestId(), e.getClass().getSimpleName());
                throw e;
            }
            return ok(Map.of("jobCount", count, "duplicates", duplicates));
        }
        if (!event.isEmpty() && !event.has("action")) {
            LOG.warn("event=invocation_rejected code=unsupported_event");
            throw new IllegalArgumentException("Unsupported event");
        }
        String action = stringValue(input, "action").orElse("health");
        switch (action) {
            case "saveJob":
                return saveJob(input);
            case "getJob":
                return getJob(input);
            case "bedrockInvoke":
                return invokeBedrock(input);
            case "health":
                return ok(Map.of("message", "ok"));
            default:
                LOG.warn("event=invocation_rejected code=unsupported_action");
                throw new IllegalArgumentException("Unsupported action");
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
