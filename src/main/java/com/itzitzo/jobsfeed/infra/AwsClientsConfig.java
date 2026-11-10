package com.itzitzo.jobsfeed.infra;

import java.time.Duration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;

public final class AwsClientsConfig {
    private AwsClientsConfig() {
    }

    public static BedrockRuntimeClient bedrockClient(String region) {
        return BedrockRuntimeClient.builder()
            .region(Region.of(region))
            .overrideConfiguration(ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(90))
                .apiCallAttemptTimeout(Duration.ofSeconds(40))
                .build())
            .build();
    }

    public static DynamoDbClient dynamoDbClient(String region) {
        return DynamoDbClient.builder()
            .region(Region.of(region))
            .overrideConfiguration(ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(10)).build())
            .build();
    }

    public static S3Client s3Client(String region) {
        return S3Client.builder()
            .region(Region.of(region))
            .overrideConfiguration(ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(15)).build())
            .build();
    }
}
