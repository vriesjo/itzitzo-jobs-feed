package com.itzitzo.jobsfeed;

import com.itzitzo.jobsfeed.service.BedrockGateway;
import com.itzitzo.jobsfeed.service.JobStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LambdaHandlerTest {
    @Test
    void shouldReturnHealthByDefault() {
        LambdaHandler handler = new LambdaHandler(prompt -> "unused", new InMemoryJobStore());
        Map<String, Object> result = handler.handleRequest(Map.of(), nullLoggerContext());
        Assertions.assertEquals("ok", result.get("status"));
        Assertions.assertEquals("ok", result.get("message"));
    }

    @Test
    void shouldSaveAndGetJob() {
        InMemoryJobStore store = new InMemoryJobStore();
        LambdaHandler handler = new LambdaHandler(prompt -> "unused", store);
        Map<String, Object> saveInput = new HashMap<>();
        saveInput.put("action", "saveJob");
        saveInput.put("pk", "job");
        saveInput.put("sk", "123");
        saveInput.put("payload", "{\"name\":\"demo\"}");

        Map<String, Object> saveResult = handler.handleRequest(saveInput, nullLoggerContext());
        Assertions.assertEquals("ok", saveResult.get("status"));

        Map<String, Object> getResult = handler.handleRequest(Map.of("action", "getJob", "pk", "job", "sk", "123"), nullLoggerContext());
        Assertions.assertEquals("ok", getResult.get("status"));
        Assertions.assertEquals("{\"name\":\"demo\"}", getResult.get("payload"));
    }

    @Test
    void shouldInvokeBedrock() {
        BedrockGateway bedrock = prompt -> "model-response-for:" + prompt;
        LambdaHandler handler = new LambdaHandler(bedrock, new InMemoryJobStore());
        Map<String, Object> result = handler.handleRequest(Map.of("action", "bedrockInvoke", "prompt", "hello"), nullLoggerContext());
        Assertions.assertEquals("ok", result.get("status"));
        Assertions.assertEquals("model-response-for:hello", result.get("response"));
    }

    private com.amazonaws.services.lambda.runtime.Context nullLoggerContext() {
        return new com.amazonaws.services.lambda.runtime.Context() {
            @Override
            public String getAwsRequestId() {
                return "test";
            }

            @Override
            public String getLogGroupName() {
                return "test";
            }

            @Override
            public String getLogStreamName() {
                return "test";
            }

            @Override
            public String getFunctionName() {
                return "test";
            }

            @Override
            public String getFunctionVersion() {
                return "test";
            }

            @Override
            public String getInvokedFunctionArn() {
                return "test";
            }

            @Override
            public com.amazonaws.services.lambda.runtime.CognitoIdentity getIdentity() {
                return null;
            }

            @Override
            public com.amazonaws.services.lambda.runtime.ClientContext getClientContext() {
                return null;
            }

            @Override
            public int getRemainingTimeInMillis() {
                return 1_000;
            }

            @Override
            public int getMemoryLimitInMB() {
                return 512;
            }

            @Override
            public com.amazonaws.services.lambda.runtime.LambdaLogger getLogger() {
                return new com.amazonaws.services.lambda.runtime.LambdaLogger() {
                    @Override
                    public void log(String message) {
                    }

                    @Override
                    public void log(byte[] message) {
                    }
                };
            }
        };
    }

    private static final class InMemoryJobStore implements JobStore {
        private final Map<String, String> payloads = new HashMap<>();

        @Override
        public void saveJob(String pk, String sk, String payload) {
            payloads.put(pk + "#" + sk, payload);
        }

        @Override
        public Optional<String> getJob(String pk, String sk) {
            return Optional.ofNullable(payloads.get(pk + "#" + sk));
        }
    }
}
