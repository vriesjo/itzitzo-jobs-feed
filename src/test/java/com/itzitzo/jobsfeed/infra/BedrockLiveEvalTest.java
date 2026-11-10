package com.itzitzo.jobsfeed.infra;

import com.itzitzo.jobsfeed.infra.ExtractionEval.Case;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("bedrock-eval")
class BedrockLiveEvalTest {
    @Test
    void scoresEachFixtureThreeTimesAgainstNova() {
        String modelId = setting("BEDROCK_MODEL_ID", "eu.amazon.nova-micro-v1:0");
        String region = setting("AWS_REGION", "eu-west-1");
        BedrockService service = new BedrockService(AwsClientsConfig.bedrockClient(region), modelId);
        for (Case fixture : ExtractionEval.loadAll()) {
            for (int run = 1; run <= 3; run++) {
                try {
                    ExtractionEval.score(service.extractJobs(fixture.email()), fixture);
                } catch (AssertionError e) {
                    throw new AssertionError(fixture.name() + " run " + run + " with " + modelId, e);
                }
            }
        }
    }

    private static String setting(String key, String fallback) {
        String property = System.getProperty(key);
        if (property != null && !property.isBlank()) {
            return property;
        }
        String env = System.getenv(key);
        return env == null || env.isBlank() ? fallback : env;
    }
}
