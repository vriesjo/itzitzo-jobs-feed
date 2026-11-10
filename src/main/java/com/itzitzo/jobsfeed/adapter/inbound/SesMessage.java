package com.itzitzo.jobsfeed.adapter.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record SesMessage(String messageId, Instant receivedAt) {
    public static List<SesMessage> parseRecords(JsonNode records) {
        if (!records.isArray() || records.isEmpty()) {
            throw new IllegalArgumentException("Expected nonempty SES records");
        }
        List<SesMessage> messages = new ArrayList<>();
        for (JsonNode record : records) {
            if (!"aws:ses".equals(record.path("eventSource").asText())) {
                throw new IllegalArgumentException("Unsupported or mixed event sources");
            }
            JsonNode mail = record.path("ses").path("mail");
            if (!mail.path("messageId").isTextual() || !mail.path("timestamp").isTextual()) {
                throw new IllegalArgumentException("Invalid SES mail metadata");
            }
            String messageId = mail.path("messageId").asText();
            if (!messageId.matches("[A-Za-z0-9_-]{1,256}")) {
                throw new IllegalArgumentException("Invalid SES message ID");
            }
            Instant receivedAt = Instant.parse(mail.path("timestamp").asText());
            JsonNode receipt = record.path("ses").path("receipt");
            if (!passed(receipt, "spamVerdict") || !passed(receipt, "virusVerdict")
                || !passed(receipt, "dmarcVerdict")
                || !(passed(receipt, "spfVerdict") || passed(receipt, "dkimVerdict"))) {
                throw new IllegalArgumentException("SES receipt verdict rejected");
            }
            messages.add(new SesMessage(messageId, receivedAt));
        }
        return List.copyOf(messages);
    }

    private static boolean passed(JsonNode receipt, String field) {
        return "PASS".equals(receipt.path(field).path("status").asText());
    }
}
