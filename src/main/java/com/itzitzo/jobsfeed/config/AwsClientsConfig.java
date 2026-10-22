package com.itzitzo.jobsfeed.config;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public final class AwsClientsConfig {
    private AwsClientsConfig() {
    }

    public static BedrockRuntimeClient bedrockClient(String region) {
        return BedrockRuntimeClient.builder()
            .region(Region.of(region))
            .build();
    }

    public static DynamoDbClient dynamoDbClient(String region) {
        return DynamoDbClient.builder()
            .region(Region.of(region))
            .build();
    }
}
