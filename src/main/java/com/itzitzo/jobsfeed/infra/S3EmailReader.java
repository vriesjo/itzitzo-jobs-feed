package com.itzitzo.jobsfeed.infra;

import com.itzitzo.jobsfeed.domain.port.EmailReader;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

public class S3EmailReader implements EmailReader {
    static final int MAX_RAW_BYTES = 5 * 1024 * 1024;
    static final int MAX_TEXT_BYTES = 64 * 1024;
    private final S3Client client;
    private final String bucket;
    private final String prefix;

    public S3EmailReader(S3Client client, String bucket, String prefix) {
        this.client = client;
        this.bucket = bucket;
        this.prefix = prefix;
    }

    @Override
    public String read(String messageId) {
        if (!messageId.matches("[A-Za-z0-9_-]{1,256}")) {
            throw new IllegalArgumentException("Invalid SES message ID");
        }
        try (ResponseInputStream<GetObjectResponse> stream = client.getObject(
            GetObjectRequest.builder().bucket(bucket).key(prefix + messageId).build())) {
            if (stream.response().contentLength() > MAX_RAW_BYTES) {
                stream.abort();
                throw new IllegalArgumentException("Email exceeds raw size limit");
            }
            byte[] raw = stream.readNBytes(MAX_RAW_BYTES + 1);
            if (raw.length > MAX_RAW_BYTES) {
                stream.abort();
                throw new IllegalArgumentException("Email exceeds raw size limit");
            }
            return parse(raw);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read email object", e);
        }
    }

    static String parse(byte[] raw) {
        if (raw.length > MAX_RAW_BYTES) {
            throw new IllegalArgumentException("Email exceeds raw size limit");
        }
        try {
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw));
            Body body = body(message, 0, new AtomicInteger());
            if (body.text().isBlank()) {
                throw new IllegalArgumentException("Email has no readable body");
            }
            String subject = message.getSubject();
            String text = (subject == null ? "" : "Subject: " + subject + "\n\n") + body.text();
            if (!body.links().isEmpty()) {
                text += "\n\nLinks:\n" + String.join("\n", body.links().stream().distinct().toList());
            }
            if (text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
                throw new IllegalArgumentException("Email exceeds normalized text limit");
            }
            return text;
        } catch (MessagingException | IOException e) {
            throw new IllegalArgumentException("Unable to parse MIME email");
        }
    }

    private static Body body(Part part, int depth, AtomicInteger count) throws MessagingException, IOException {
        if (depth > 30 || count.incrementAndGet() > 1_000) {
            throw new IllegalArgumentException("Email exceeds MIME complexity limit");
        }
        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null) {
            return new Body("", false, List.of());
        }
        if (part.isMimeType("text/plain")) {
            return new Body(unfoldQuotedPrintable((String) part.getContent()), true, List.of());
        }
        if (part.isMimeType("text/html")) {
            Document document = Jsoup.parse(unfoldQuotedPrintable((String) part.getContent()));
            document.select("script,style,noscript,template").remove();
            List<String> links = new ArrayList<>();
            for (Element link : document.select("a[href]")) {
                String url = link.attr("href").trim();
                if (isHttpUrl(url)) {
                    String label = link.text().strip();
                    links.add("Link: " + (label.isEmpty() ? url : label) + " | URL: " + url);
                }
            }
            return new Body(document.text(), false, links);
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            List<Body> children = new ArrayList<>();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                Body content = body(child, depth + 1, count);
                if (!content.text().isBlank()) {
                    children.add(content);
                }
            }
            if (part.isMimeType("multipart/alternative")) {
                Body preferred = children.stream().filter(Body::plain).findFirst()
                    .orElseGet(() -> children.isEmpty() ? new Body("", false, List.of()) : children.get(0));
                return new Body(preferred.text(), preferred.plain(),
                    children.stream().flatMap(child -> child.links().stream()).toList());
            }
            return new Body(String.join("\n\n", children.stream().map(Body::text).toList()), false,
                children.stream().flatMap(child -> child.links().stream()).toList());
        }
        return new Body("", false, List.of());
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = new URI(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static String unfoldQuotedPrintable(String value) {
        return value.replace("=\r\n", "").replace("=\n", "").replace("=\r", "");
    }

    private record Body(String text, boolean plain, List<String> links) {
    }
}
