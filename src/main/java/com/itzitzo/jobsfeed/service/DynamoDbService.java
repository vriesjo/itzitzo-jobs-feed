package com.itzitzo.jobsfeed.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

public class DynamoDbService implements JobStore {
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
}
