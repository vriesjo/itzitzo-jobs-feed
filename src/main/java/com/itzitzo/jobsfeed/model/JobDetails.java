package com.itzitzo.jobsfeed.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;

public record JobDetails(
    String title,
    String company,
    String location,
    String description,
    String applicationUrl,
    Instant publishedAt
) {
    public JobDetails {
        requireText(title, "title", 300);
        requireText(description, "description", 12_000);
        optionalText(company, "company", 300);
        optionalText(location, "location", 300);
        optionalText(applicationUrl, "applicationUrl", 2_048);
        if (applicationUrl != null) {
            URI uri;
            try {
                uri = new URI(applicationUrl);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Invalid application URL");
            }
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("Invalid application URL");
            }
        }
    }

    private static void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing job field: " + field);
        }
        optionalText(value, field, maxLength);
    }

    private static void optionalText(String value, String field, int maxLength) {
        if (value != null && (value.isBlank() || value.length() > maxLength)) {
            throw new IllegalArgumentException("Invalid job field: " + field);
        }
    }
}
