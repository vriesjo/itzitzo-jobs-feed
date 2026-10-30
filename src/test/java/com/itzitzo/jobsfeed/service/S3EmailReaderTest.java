package com.itzitzo.jobsfeed.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

class S3EmailReaderTest {
    @Test
    void decodesHeadersAndPrefersPlainTextInMultipartAlternatives() {
        String mime = """
            MIME-Version: 1.0
            Subject: =?UTF-8?B?SmF2YSBqb2I=?=
            Content-Type: multipart/mixed; boundary=outer

            --outer
            Content-Type: multipart/alternative; boundary=inner

            --inner
            Content-Type: text/html; charset=UTF-8

            <p>HTML duplicate</p>
            --inner
            Content-Type: text/plain; charset=UTF-8
            Content-Transfer-Encoding: base64

            SmF2YSBkZXZlbG9wZXI=
            --inner--
            --outer
            Content-Type: text/plain
            Content-Disposition: attachment; filename=private.txt

            PRIVATE ATTACHMENT
            --outer--
            """;
        String text = parse(mime);
        assertTrue(text.contains("Subject: Java job"));
        assertTrue(text.contains("Java developer"));
        assertFalse(text.contains("HTML duplicate"));
        assertFalse(text.contains("PRIVATE ATTACHMENT"));
    }

    @Test
    void convertsHtmlToTextWithoutScriptsOrExternalRequests() {
        String text = parse("""
            Content-Type: text/html; charset=UTF-8

            <style>hidden style</style><script>secret script</script>
            <p>Build Java &amp; AWS services.</p>
            <a href="https://freelance.nl/jobs/123">Bekijk opdracht</a>
            <a href="javascript:alert(1)">unsafe link</a>
            """);
        assertTrue(text.startsWith("Build Java & AWS services. Bekijk opdracht unsafe link"));
        assertTrue(text.contains("Link: Bekijk opdracht | URL: https://freelance.nl/jobs/123"));
        assertFalse(text.contains("javascript:"));
    }

    @Test
    void preservesHtmlLinksWhenPreferringPlainTextMultipartAlternative() {
        String text = parse("""
            MIME-Version: 1.0
            Content-Type: multipart/alternative; boundary=alt

            --alt
            Content-Type: text/plain; charset=UTF-8

            Bekijk alle matches
            --alt
            Content-Type: text/html; charset=UTF-8

            <a href="https://freelance.nl/jobs/123">Bekijk alle matches</a>
            --alt--
            """);
        assertTrue(text.startsWith("Bekijk alle matches"));
        assertTrue(text.contains("Link: Bekijk alle matches | URL: https://freelance.nl/jobs/123"));
    }

    @Test
    void joinsQuotedPrintableSoftLineBreaksInsideUrls() {
        String text = parse("""
            Content-Type: text/plain; charset=UTF-8

            Bekijk opdracht:
            https://webmail.freelance.nl/e3t/first-part=
            second-part
            """);
        assertTrue(text.contains("https://webmail.freelance.nl/e3t/first-partsecond-part"));
        assertFalse(text.contains("first-part=\n"));
    }

    @Test
    void rejectsOversizeOrUnreadableMessagesWithoutTruncation() {
        assertThrows(IllegalArgumentException.class, () -> S3EmailReader.parse(new byte[S3EmailReader.MAX_RAW_BYTES + 1]));
        assertThrows(IllegalArgumentException.class, () -> parse("Content-Type: text/plain\n\n" + "a".repeat(65_537)));
        assertThrows(IllegalArgumentException.class,
            () -> parse("Content-Type: text/plain; charset=UTF-8\n\n" + "\u20ac".repeat(22_000)));
        assertThrows(IllegalArgumentException.class, () -> parse("Content-Type: application/octet-stream\n\nbinary"));
    }

    @Test
    void readsOnlyTheConfiguredBucketAndMessagePrefix() {
        S3Client client = mock(S3Client.class);
        byte[] raw = "Content-Type: text/plain\n\nJob opening".getBytes(StandardCharsets.UTF_8);
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
            GetObjectResponse.builder().contentLength((long) raw.length).build(),
            AbortableInputStream.create(new ByteArrayInputStream(raw))));
        S3EmailReader reader = new S3EmailReader(client, "email-bucket", "incoming/");
        assertEquals("Job opening", reader.read("message-1"));
        verify(client).getObject(GetObjectRequest.builder().bucket("email-bucket").key("incoming/message-1").build());
        assertThrows(IllegalArgumentException.class, () -> reader.read("../other-prefix"));
    }

    private static String parse(String mime) {
        return S3EmailReader.parse(mime.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
    }
}
