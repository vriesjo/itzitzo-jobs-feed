package com.itzitzo.jobsfeed.adapter.inbound;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itzitzo.jobsfeed.domain.ContactMessage;
import com.itzitzo.jobsfeed.domain.port.ContactMessageSink;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContactRequestHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ContactRequestHandler.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern TAG_PATTERN = Pattern.compile("<\\s*/?\\s*[a-zA-Z!][^>]*>");
    private final String allowedEmail;
    private final String expectedToken;
    private final ContactMessageSink sink;

    public ContactRequestHandler(String allowedEmail, String expectedToken, ContactMessageSink sink) {
        this.allowedEmail = allowedEmail == null ? "" : allowedEmail.trim();
        this.expectedToken = expectedToken == null ? "" : expectedToken;
        this.sink = sink;
    }

    public Map<String, Object> handle(JsonNode event, String method) {
        if (!"POST".equals(method)) {
            return response(405, "Method not allowed", Map.of("Allow", "POST"));
        }
        if (allowedEmail.isBlank() || expectedToken.isBlank()) {
            LOG.error("event=contact_rejected code=misconfigured");
            return response(503, "Contact endpoint is not configured", Map.of());
        }
        Optional<String> contentType = header(event, "content-type");
        if (contentType.isEmpty() || !contentType.get().toLowerCase().startsWith("application/json")) {
            return response(400, "Invalid request payload", Map.of());
        }
        ContactMessage message = parseAndValidate(event);
        Optional<String> token = header(event, "x-contact-token");
        if (token.isEmpty()) {
            return response(401, "Missing authentication token", Map.of());
        }
        if (!allowedEmail.equals(message.email()) || !equalsConstantTime(expectedToken, token.get())) {
            LOG.warn("event=contact_rejected code=forbidden");
            return response(403, "Forbidden", Map.of());
        }
        sink.submit(message);
        LOG.info("event=contact_accepted email={} subjectLength={}", message.email(), message.subject().length());
        return response(202, "Accepted", Map.of());
    }

    private ContactMessage parseAndValidate(JsonNode event) {
        JsonNode body;
        try {
            body = JSON.readTree(readBody(event));
        } catch (JsonProcessingException e) {
            throw badRequest();
        }
        String name = text(body, "name", 120);
        String email = text(body, "email", 320);
        String subject = text(body, "subject", 200);
        String message = text(body, "message", 8_000);
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw badRequest();
        }
        if (containsTag(name) || containsTag(subject) || containsTag(message)) {
            throw badRequest();
        }
        return new ContactMessage(name, email, subject, message);
    }

    private String readBody(JsonNode event) {
        if (!event.path("body").isTextual()) {
            throw badRequest();
        }
        String body = event.path("body").textValue();
        if (body == null || body.isBlank()) {
            throw badRequest();
        }
        if (event.path("isBase64Encoded").asBoolean(false)) {
            try {
                return new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw badRequest();
            }
        }
        return body;
    }

    private String text(JsonNode body, String field, int maxLength) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual()) {
            throw badRequest();
        }
        String text = value.textValue();
        if (text == null || text.isBlank() || text.length() > maxLength || hasInvalidControlCharacter(text)) {
            throw badRequest();
        }
        return text.trim();
    }

    private boolean hasInvalidControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') {
                return true;
            }
        }
        return false;
    }

    private boolean containsTag(String value) {
        return TAG_PATTERN.matcher(value).find();
    }

    private Optional<String> header(JsonNode event, String headerName) {
        JsonNode headers = event.path("headers");
        if (!headers.isObject()) {
            return Optional.empty();
        }
        String normalized = headerName.toLowerCase();
        var fields = headers.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(normalized) && entry.getValue().isTextual()) {
                return Optional.of(entry.getValue().textValue());
            }
        }
        return Optional.empty();
    }

    private boolean equalsConstantTime(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    private IllegalArgumentException badRequest() {
        return new IllegalArgumentException("Invalid request payload");
    }

    private Map<String, Object> response(int status, String body, Map<String, String> extraHeaders) {
        HashMap<String, String> headers = new HashMap<>(extraHeaders);
        headers.put("Content-Type", "text/plain; charset=utf-8");
        return Map.of("statusCode", status, "headers", headers, "body", body, "isBase64Encoded", false);
    }
}
