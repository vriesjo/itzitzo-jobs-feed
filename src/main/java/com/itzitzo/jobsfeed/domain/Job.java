package com.itzitzo.jobsfeed.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Objects;
import java.util.UUID;

public record Job(String id, String sourceMessageId, Instant receivedAt, JobDetails details) {
    private static final DateTimeFormatter SORT_TIME = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();

    public Job {
        Objects.requireNonNull(id);
        Objects.requireNonNull(sourceMessageId);
        Objects.requireNonNull(receivedAt);
        Objects.requireNonNull(details);
    }

    public static Job fromEmail(String messageId, Instant receivedAt, int ordinal, JobDetails details) {
        String id = UUID.nameUUIDFromBytes((messageId + ":" + ordinal).getBytes(StandardCharsets.UTF_8)).toString();
        return new Job(id, messageId, receivedAt, details);
    }

    public String sortKey() {
        return SORT_TIME.format(receivedAt) + "#" + id;
    }
}
