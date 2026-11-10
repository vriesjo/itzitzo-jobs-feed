package com.itzitzo.jobsfeed.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.itzitzo.jobsfeed.model.Job;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpFeedHandler {
    private static final Logger LOG = LoggerFactory.getLogger(HttpFeedHandler.class);
    private final JobStore store;
    private final RssRenderer renderer;
    private final ContactRequestHandler contact;

    public HttpFeedHandler(JobStore store, RssRenderer renderer) {
        this(store, renderer, new ContactRequestHandler("", "", message -> {
        }));
    }

    public HttpFeedHandler(JobStore store, RssRenderer renderer, ContactRequestHandler contact) {
        this.store = store;
        this.renderer = renderer;
        this.contact = contact;
    }

    public Map<String, Object> handle(JsonNode event) {
        JsonNode http = event.path("requestContext").path("http");
        if (!event.path("version").isTextual() || !"2.0".equals(event.path("version").textValue())
            || !http.path("method").isTextual() || !http.path("path").isTextual()) {
            LOG.warn("event=http_rejected code=invalid_event");
            return response(400, "Invalid HTTP event", Map.of());
        }
        String path = http.path("path").textValue();
        String method = http.path("method").textValue();
        if ("/feed.xml".equals(path)) {
            return feed(method);
        }
        if ("/contact".equals(path)) {
            try {
                return contact.handle(event, method);
            } catch (IllegalArgumentException e) {
                return response(400, "Invalid request payload", Map.of());
            }
        }
        return response(404, "Not found", Map.of());
    }

    private Map<String, Object> feed(String method) {
        if (!"GET".equals(method)) {
            return response(405, "Method not allowed", Map.of("Allow", "GET"));
        }
        try {
            long start = System.nanoTime();
            List<Job> jobs = FeedVacancies.collect(store, FeedVacancies.FEED_LIMIT);
            String xml = renderer.render(jobs);
            LOG.info("event=feed_rendered jobCount={} durationMs={}", jobs.size(), (System.nanoTime() - start) / 1_000_000);
            return Map.of(
                "statusCode", 200,
                "headers", Map.of("Content-Type", "application/rss+xml; charset=utf-8"),
                "body", xml,
                "isBase64Encoded", false);
        } catch (RuntimeException e) {
            LOG.error("event=feed_failed failureType={}", e.getClass().getSimpleName());
            return response(500, "Unable to load feed", Map.of());
        }
    }

    private Map<String, Object> response(int status, String body, Map<String, String> extraHeaders) {
        HashMap<String, String> headers = new HashMap<>(extraHeaders);
        headers.put("Content-Type", "text/plain; charset=utf-8");
        return Map.of("statusCode", status, "headers", headers, "body", body, "isBase64Encoded", false);
    }
}
