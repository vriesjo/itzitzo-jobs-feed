package com.itzitzo.jobsfeed.service;

import com.itzitzo.jobsfeed.model.Job;
import com.itzitzo.jobsfeed.model.JobDetails;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

public class DynamoDbService implements JobStore {
    private static final long JOB_TTL_DAYS = 21;
    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public DynamoDbService(DynamoDbClient dynamoDbClient, String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    @Override
    public void saveJob(String pk, String sk, String payload) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", AttributeValue.builder().s(pk).build());
        item.put("sk", AttributeValue.builder().s(sk).build());
        item.put("payload", AttributeValue.builder().s(payload).build());
        item.put("expiresAt", number(Instant.now().plus(JOB_TTL_DAYS, ChronoUnit.DAYS).getEpochSecond()));
        PutItemRequest request = PutItemRequest.builder()
            .tableName(tableName)
            .item(item)
            .build();
        dynamoDbClient.putItem(request);
    }

    @Override
    public Optional<String> getJob(String pk, String sk) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("pk", AttributeValue.builder().s(pk).build());
        key.put("sk", AttributeValue.builder().s(sk).build());
        GetItemRequest request = GetItemRequest.builder()
            .tableName(tableName)
            .key(key)
            .build();
        GetItemResponse response = dynamoDbClient.getItem(request);
        if (!response.hasItem()) {
            return Optional.empty();
        }
        AttributeValue payload = response.item().get("payload");
        return payload == null ? Optional.empty() : Optional.ofNullable(payload.s());
    }

    @Override
    public boolean isMessageProcessed(String messageId) {
        return dynamoDbClient.getItem(GetItemRequest.builder()
            .tableName(tableName).consistentRead(true)
            .key(key("EMAIL#" + messageId, "METADATA")).build()).hasItem();
    }

    @Override
    public boolean saveIngestion(String messageId, List<Job> jobs) {
        if (jobs.size() > JobExtractor.MAX_JOBS) {
            throw new IllegalArgumentException("Too many jobs in ingestion");
        }
        List<TransactWriteItem> writes = new ArrayList<>();
        Map<String, AttributeValue> marker = new HashMap<>(key("EMAIL#" + messageId, "METADATA"));
        marker.put("jobCount", AttributeValue.builder().n(Integer.toString(jobs.size())).build());
        writes.add(TransactWriteItem.builder().put(Put.builder().tableName(tableName).item(marker)
            .conditionExpression("attribute_not_exists(pk)").build()).build());
        HashSet<String> keys = new HashSet<>();
        int bytes = itemBytes(marker);
        for (Job job : jobs) {
            if (!messageId.equals(job.sourceMessageId()) || !keys.add(job.sortKey())) {
                throw new IllegalArgumentException("Invalid ingestion jobs");
            }
            Map<String, AttributeValue> item = toItem(job);
            int size = itemBytes(item);
            if (size > 350 * 1024) {
                throw new IllegalArgumentException("Job exceeds storage size limit");
            }
            bytes += size;
            writes.add(TransactWriteItem.builder().put(Put.builder().tableName(tableName).item(item).build()).build());
        }
        if (bytes > 3 * 1024 * 1024) {
            throw new IllegalArgumentException("Ingestion exceeds transaction size limit");
        }
        try {
            dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(writes).build());
            return true;
        } catch (TransactionCanceledException e) {
            boolean duplicate = !e.cancellationReasons().isEmpty()
                && "ConditionalCheckFailed".equals(e.cancellationReasons().get(0).code());
            if (duplicate && isMessageProcessed(messageId)) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public List<Job> recentJobs(int limit) {
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("Feed limit must be between 1 and 50");
        }
        List<Job> jobs = new ArrayList<>();
        Map<String, AttributeValue> cursor = Map.of();
        do {
            QueryRequest.Builder request = QueryRequest.builder()
                .tableName(tableName).consistentRead(true)
                .keyConditionExpression("pk = :pk")
                .expressionAttributeValues(Map.of(":pk", text("FEED#jobs")))
                .scanIndexForward(false).limit(limit - jobs.size());
            if (!cursor.isEmpty()) {
                request.exclusiveStartKey(cursor);
            }
            QueryResponse response = dynamoDbClient.query(request.build());
            for (Map<String, AttributeValue> item : response.items()) {
                jobs.add(fromItem(item));
            }
            cursor = response.lastEvaluatedKey();
        } while (!cursor.isEmpty() && jobs.size() < limit);
        return List.copyOf(jobs);
    }

    private static Map<String, AttributeValue> toItem(Job job) {
        Map<String, AttributeValue> item = new HashMap<>(key("FEED#jobs", job.sortKey()));
        item.put("id", text(job.id()));
        item.put("sourceMessageId", text(job.sourceMessageId()));
        item.put("receivedAt", text(job.receivedAt().toString()));
        item.put("expiresAt", number(job.receivedAt().plus(JOB_TTL_DAYS, ChronoUnit.DAYS).getEpochSecond()));
        item.put("title", text(job.details().title()));
        item.put("description", text(job.details().description()));
        putOptional(item, "company", job.details().company());
        putOptional(item, "location", job.details().location());
        putOptional(item, "applicationUrl", job.details().applicationUrl());
        if (job.details().publishedAt() != null) {
            item.put("publishedAt", text(job.details().publishedAt().toString()));
        }
        return item;
    }

    private static Job fromItem(Map<String, AttributeValue> item) {
        String publishedAt = optional(item, "publishedAt");
        return new Job(required(item, "id"), required(item, "sourceMessageId"),
            Instant.parse(required(item, "receivedAt")),
            new JobDetails(required(item, "title"), optional(item, "company"), optional(item, "location"),
                required(item, "description"), optional(item, "applicationUrl"),
                publishedAt == null ? null : Instant.parse(publishedAt)));
    }

    private static Map<String, AttributeValue> key(String pk, String sk) {
        return Map.of("pk", text(pk), "sk", text(sk));
    }

    private static AttributeValue text(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }

    private static void putOptional(Map<String, AttributeValue> item, String field, String value) {
        if (value != null) {
            item.put(field, text(value));
        }
    }

    private static String optional(Map<String, AttributeValue> item, String field) {
        AttributeValue value = item.get(field);
        return value == null ? null : value.s();
    }

    private static String required(Map<String, AttributeValue> item, String field) {
        String value = optional(item, field);
        if (value == null) {
            throw new IllegalStateException("Invalid stored job field: " + field);
        }
        return value;
    }

    private static int itemBytes(Map<String, AttributeValue> item) {
        return item.entrySet().stream().mapToInt(entry ->
            entry.getKey().getBytes(StandardCharsets.UTF_8).length
                + (entry.getValue().s() == null ? 16
                : entry.getValue().s().getBytes(StandardCharsets.UTF_8).length)).sum();
    }
}
