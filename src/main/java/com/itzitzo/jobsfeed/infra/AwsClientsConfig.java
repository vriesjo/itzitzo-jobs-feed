package com.itzitzo.jobsfeed.infra;

import java.time.Duration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * AwsClientsConfig is a utility class that provides methods to create and configure AWS SDK clients
 * for Bedrock Runtime, DynamoDB, and S3 services. It ensures that the clients are properly configured
 * with the specified region and timeout settings.
 */
public final class AwsClientsConfig {
    private AwsClientsConfig() {
    }

    // BedrockRuntimeClient is used to interact with AWS Bedrock Runtime service.
    // It is configured with a region and custom timeout settings for API calls.
    public static BedrockRuntimeClient bedrockClient(String region) {
        return BedrockRuntimeClient.builder()
            .region(Region.of(region))
            .overrideConfiguration(ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(90))
                .apiCallAttemptTimeout(Duration.ofSeconds(40))
                .build())
            .build();
    }

    // DynamoDbClient is used to interact with AWS DynamoDB service.
    // It is configured with a region and a custom timeout setting for API calls.
    public static DynamoDbClient dynamoDbClient(String region) {
        return DynamoDbClient.builder()
            .region(Region.of(region))
            .overrideConfiguration(ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(10)).build())
            .build();
    }

    // S3Client is used to interact with AWS S3 service.
    // It is configured with a region and a custom timeout setting for API calls.
    public static S3Client s3Client(String region) {
        return S3Client.builder()
            .region(Region.of(region))
            .overrideConfiguration(ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(15)).build())
            .build();
    }
}
