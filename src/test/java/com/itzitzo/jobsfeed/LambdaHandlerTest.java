package com.itzitzo.jobsfeed;

import com.itzitzo.jobsfeed.service.BedrockGateway;
import com.itzitzo.jobsfeed.model.Job;
import com.itzitzo.jobsfeed.model.JobDetails;
import com.itzitzo.jobsfeed.service.EmailIngestionService;
import com.itzitzo.jobsfeed.service.FeedPage;
import com.itzitzo.jobsfeed.service.HttpFeedHandler;
import com.itzitzo.jobsfeed.service.JobStore;
import com.itzitzo.jobsfeed.service.RssRenderer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LambdaHandlerTest {
    @Test
    void shouldReturnHealthByDefault() {
        LambdaHandler handler = handler(prompt -> "unused", new InMemoryJobStore());
        Map<String, Object> result = handler.handleRequest(Map.of(), nullLoggerContext());
        Assertions.assertEquals("ok", result.get("status"));
        Assertions.assertEquals("ok", result.get("message"));
    }

    @Test
    void shouldSaveAndGetJob() {
        InMemoryJobStore store = new InMemoryJobStore();
        LambdaHandler handler = handler(prompt -> "unused", store);
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
        LambdaHandler handler = handler(bedrock, new InMemoryJobStore());
        Map<String, Object> result = handler.handleRequest(Map.of("action", "bedrockInvoke", "prompt", "hello"), nullLoggerContext());
        Assertions.assertEquals("ok", result.get("status"));
        Assertions.assertEquals("model-response-for:hello", result.get("response"));
    }

    @Test
    void shouldRouteHttpWithoutExposingInternalActions() {
        LambdaHandler handler = handler(prompt -> {
            throw new AssertionError("HTTP must not invoke Bedrock");
        }, new InMemoryJobStore());
        Map<String, Object> result = handler.handleRequest(Map.of(
            "version", "2.0", "action", "bedrockInvoke", "prompt", "ignored",
            "requestContext", Map.of("http", Map.of("method", "GET", "path", "/feed.xml"))), nullLoggerContext());
        Assertions.assertEquals(200, result.get("statusCode"));
        Assertions.assertTrue(result.get("body").toString().contains("<channel>"));
    }

    @Test
    void shouldRejectUnsupportedAndMixedEventSourcesBeforeIngestion() {
        LambdaHandler handler = handler(prompt -> "unused", new InMemoryJobStore());
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> handler.handleRequest(Map.of("Records", List.of()), nullLoggerContext()));
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> handler.handleRequest(Map.of("Records", List.of(sesRecord("one"),
                Map.of("eventSource", "aws:s3"))), nullLoggerContext()));
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> handler.handleRequest(Map.of("unexpected", true), nullLoggerContext()));
    }

    @Test
    void shouldRetryMultiRecordEventsWithoutReprocessingCompletedMessages() {
        InMemoryJobStore store = new InMemoryJobStore();
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        List<String> reads = new ArrayList<>();
        EmailIngestionService ingestion = new EmailIngestionService(messageId -> {
            reads.add(messageId);
            if ("two".equals(messageId) && fail.getAndSet(false)) {
                throw new IllegalStateException("Temporary read failure");
            }
            return "email";
        }, email -> List.of(), store);
        LambdaHandler handler = new LambdaHandler(prompt -> "unused", store, ingestion, feed(store));
        Map<String, Object> event = Map.of("Records", List.of(sesRecord("one"), sesRecord("two")));
        Assertions.assertThrows(IllegalStateException.class, () -> handler.handleRequest(event, nullLoggerContext()));
        Map<String, Object> result = handler.handleRequest(event, nullLoggerContext());
        Assertions.assertEquals(1, result.get("duplicates"));
        Assertions.assertEquals(List.of("one", "two", "two"), reads);
    }

    @Test
    void shouldRejectUnsafeVerdictsAndMalformedHttp() {
        LambdaHandler handler = handler(prompt -> "unused", new InMemoryJobStore());
        Map<String, Object> unsafe = Map.of("eventSource", "aws:ses",
            "ses", Map.of("mail", Map.of("messageId", "one", "timestamp", Instant.now().toString()),
                "receipt", Map.of("spamVerdict", Map.of("status", "FAIL"))));
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> handler.handleRequest(Map.of("Records", List.of(unsafe)), nullLoggerContext()));
        Assertions.assertEquals(400, handler.handleRequest(Map.of("version", "1.0", "action", "saveJob"),
            nullLoggerContext()).get("statusCode"));
    }

    @Test
    void shouldPublishAnIngestedJobOnlyOnceWithAStableGuid() {
        InMemoryJobStore store = new InMemoryJobStore();
        java.util.concurrent.atomic.AtomicInteger extractions = new java.util.concurrent.atomic.AtomicInteger();
        EmailIngestionService ingestion = new EmailIngestionService(id -> "Synthetic job email", text -> {
            extractions.incrementAndGet();
            return List.of(new JobDetails("Java & AWS Developer", "Example", "Remote",
                "Build services.", "https://example.com/job", null));
        }, store);
        LambdaHandler handler = new LambdaHandler(prompt -> "unused", store, ingestion, feed(store));
        Map<String, Object> email = Map.of("Records", List.of(sesRecord("message-private")));
        Assertions.assertEquals(1, handler.handleRequest(email, nullLoggerContext()).get("jobCount"));
        Map<String, Object> http = Map.of("version", "2.0", "requestContext",
            Map.of("http", Map.of("method", "GET", "path", "/feed.xml")));
        String firstFeed = handler.handleRequest(http, nullLoggerContext()).get("body").toString();
        Assertions.assertEquals(1, handler.handleRequest(email, nullLoggerContext()).get("duplicates"));
        Assertions.assertEquals(firstFeed, handler.handleRequest(http, nullLoggerContext()).get("body"));
        Assertions.assertTrue(firstFeed.contains("Java &amp; AWS Developer"));
        Assertions.assertFalse(firstFeed.contains("message-private"));
        Assertions.assertEquals(1, extractions.get());
    }

    private static LambdaHandler handler(BedrockGateway bedrock, JobStore store) {
        return new LambdaHandler(bedrock, store,
            new EmailIngestionService(id -> "email", email -> List.of(), store), feed(store));
    }

    private static HttpFeedHandler feed(JobStore store) {
        return new HttpFeedHandler(store, new RssRenderer("Jobs", "Recent jobs",
            "https://example.com", "https://example.com/feed.xml"));
    }

    private static Map<String, Object> sesRecord(String messageId) {
        Map<String, Object> pass = Map.of("status", "PASS");
        return Map.of("eventSource", "aws:ses", "ses", Map.of(
            "mail", Map.of("messageId", messageId, "timestamp", "2026-10-22T12:00:00Z"),
            "receipt", Map.of("spamVerdict", pass, "virusVerdict", pass, "dmarcVerdict", pass,
                "spfVerdict", pass, "dkimVerdict", pass)));
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
        private final HashSet<String> processed = new HashSet<>();
        private final List<Job> jobs = new ArrayList<>();

        @Override
        public void saveJob(String pk, String sk, String payload) {
            payloads.put(pk + "#" + sk, payload);
        }

        @Override
        public Optional<String> getJob(String pk, String sk) {
            return Optional.ofNullable(payloads.get(pk + "#" + sk));
        }

        @Override
        public boolean isMessageProcessed(String messageId) {
            return processed.contains(messageId);
        }

        @Override
        public boolean saveIngestion(String messageId, List<Job> ingested) {
            if (!processed.add(messageId)) {
                return false;
            }
            jobs.addAll(ingested);
            return true;
        }

        @Override
        public List<Job> recentJobs(int limit) {
            return recentJobPage(limit, null).jobs();
        }

        @Override
        public FeedPage recentJobPage(int limit, String exclusiveSortKey) {
            List<Job> ordered = jobs.stream()
                .sorted(java.util.Comparator.comparing(Job::sortKey).reversed())
                .filter(job -> exclusiveSortKey == null || job.sortKey().compareTo(exclusiveSortKey) < 0)
                .toList();
            List<Job> page = ordered.stream().limit(limit).toList();
            return new FeedPage(page, ordered.size() > page.size());
        }
    }
}
