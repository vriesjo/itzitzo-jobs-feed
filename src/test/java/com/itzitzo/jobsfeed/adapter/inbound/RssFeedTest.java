package com.itzitzo.jobsfeed.adapter.inbound;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itzitzo.jobsfeed.application.FeedVacancies;
import com.itzitzo.jobsfeed.application.RssRenderer;
import com.itzitzo.jobsfeed.domain.ContactMessage;
import com.itzitzo.jobsfeed.domain.FeedPage;
import com.itzitzo.jobsfeed.domain.Job;
import com.itzitzo.jobsfeed.domain.JobDetails;
import com.itzitzo.jobsfeed.domain.port.ContactMessageSink;
import com.itzitzo.jobsfeed.domain.port.JobStore;
import java.io.StringReader;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

class RssFeedTest {
    private final RssRenderer renderer = new RssRenderer("Jobs & Careers", "Recent jobs",
        "https://example.com", "https://example.com/feed.xml");

    @Test
    void rendersParseableRssWithEscapingUnicodeStableGuidsAndDates() throws Exception {
        Job job = Job.fromEmail("private-message-id", Instant.parse("2026-10-22T12:00:00Z"), 0,
            new JobDetails("Java & <Cloud> \u20ac", "Example & Company", null, "Build <services>\u0001",
                "https://example.com/job?a=1&b=2", null));
        String xml = renderer.render(List.of(job));
        Document document = parse(xml);
        assertEquals("rss", document.getDocumentElement().getTagName());
        assertEquals("Java & <Cloud> \u20ac", document.getElementsByTagName("title").item(1).getTextContent());
        assertEquals("Example & Company", document.getElementsByTagName("itzitzo:company").item(0).getTextContent());
        assertEquals("Build <services>\ufffd", document.getElementsByTagName("description").item(1).getTextContent());
        assertEquals("false", document.getElementsByTagName("guid").item(0).getAttributes()
            .getNamedItem("isPermaLink").getNodeValue());
        assertTrue(document.getElementsByTagName("pubDate").item(0).getTextContent().contains("22 Oct 2026"));
        assertFalse(xml.contains("private-message-id"));
        assertEquals(xml, renderer.render(List.of(job)));
    }

    @Test
    void returnsEmptyRssAndRestrictsRoutesWithoutReadingStorage() throws Exception {
        JobStore store = mock(JobStore.class);
        HttpFeedHandler handler = new HttpFeedHandler(store, renderer);
        when(store.recentJobPage(FeedVacancies.FEED_LIMIT, null)).thenReturn(new FeedPage(List.of(), false));
        Map<String, Object> result = http(handler, "GET", "/feed.xml");
        assertEquals(200, result.get("statusCode"));
        assertEquals(0, parse(result.get("body").toString()).getElementsByTagName("item").getLength());
        assertEquals(Map.of("Content-Type", "application/rss+xml; charset=utf-8"), result.get("headers"));
        assertEquals(false, result.get("isBase64Encoded"));
        assertEquals(404, http(handler, "GET", "/admin").get("statusCode"));
        Map<String, Object> wrongMethod = http(handler, "POST", "/feed.xml");
        assertEquals(405, wrongMethod.get("statusCode"));
        assertEquals("GET", ((Map<?, ?>) wrongMethod.get("headers")).get("Allow"));
        verify(store, times(1)).recentJobPage(FeedVacancies.FEED_LIMIT, null);
    }

    @Test
    void reportsDependencyFailuresRatherThanReturningAnEmptyFeed() {
        JobStore store = mock(JobStore.class);
        when(store.recentJobPage(FeedVacancies.FEED_LIMIT, null))
            .thenThrow(new IllegalStateException("private error details"));
        Map<String, Object> result = http(new HttpFeedHandler(store, renderer), "GET", "/feed.xml");
        assertEquals(500, result.get("statusCode"));
        assertFalse(result.get("body").toString().contains("private error"));
    }

    @Test
    void acceptsOnlyAllowlistedContactRequestsWithToken() {
        ContactMessageSink sink = mock(ContactMessageSink.class);
        ContactRequestHandler contact = new ContactRequestHandler("vriesjo@gmail.com", "secret-token", sink);
        HttpFeedHandler handler = new HttpFeedHandler(mock(JobStore.class), renderer, contact);
        Map<String, Object> accepted = http(handler, "POST", "/contact", Map.of(
            "content-type", "application/json",
            "x-contact-token", "secret-token"), """
                {"name":"Johan","email":"vriesjo@gmail.com","subject":"test","message":"Testing"}
                """, false);
        assertEquals(202, accepted.get("statusCode"));
        verify(sink).submit(new ContactMessage("Johan", "vriesjo@gmail.com", "test", "Testing"));

        Map<String, Object> missingToken = http(handler, "POST", "/contact", Map.of(
            "content-type", "application/json"), """
                {"name":"Johan","email":"vriesjo@gmail.com","subject":"test","message":"Testing"}
                """, false);
        assertEquals(401, missingToken.get("statusCode"));
        verifyNoMoreInteractions(sink);

        Map<String, Object> forbiddenEmail = http(handler, "POST", "/contact", Map.of(
            "content-type", "application/json",
            "x-contact-token", "secret-token"), """
                {"name":"Johan","email":"other@example.com","subject":"test","message":"Testing"}
                """, false);
        assertEquals(403, forbiddenEmail.get("statusCode"));

        Map<String, Object> wrongToken = http(handler, "POST", "/contact", Map.of(
            "content-type", "application/json",
            "x-contact-token", "wrong"), """
                {"name":"Johan","email":"vriesjo@gmail.com","subject":"test","message":"Testing"}
                """, false);
        assertEquals(403, wrongToken.get("statusCode"));
        verifyNoMoreInteractions(sink);
    }

    @Test
    void rejectsInvalidContactPayloadsWithoutSideEffects() {
        ContactMessageSink sink = mock(ContactMessageSink.class);
        ContactRequestHandler contact = new ContactRequestHandler("vriesjo@gmail.com", "secret-token", sink);
        HttpFeedHandler handler = new HttpFeedHandler(mock(JobStore.class), renderer, contact);
        Map<String, Object> badPayload = http(handler, "POST", "/contact", Map.of(
            "content-type", "application/json",
            "x-contact-token", "secret-token"), """
                {"name":"Johan","email":"vriesjo@gmail.com","subject":"test","message":"<script>alert(1)</script>"}
                """, false);
        assertEquals(400, badPayload.get("statusCode"));
        Map<String, Object> wrongMethod = http(handler, "GET", "/contact");
        assertEquals(405, wrongMethod.get("statusCode"));
        assertEquals("POST", ((Map<?, ?>) wrongMethod.get("headers")).get("Allow"));
        verifyNoInteractions(sink);
    }

    private static Map<String, Object> http(HttpFeedHandler handler, String method, String path) {
        return http(handler, method, path, Map.of(), null, false);
    }

    private static Map<String, Object> http(
        HttpFeedHandler handler,
        String method,
        String path,
        Map<String, String> headers,
        String body,
        boolean isBase64Encoded
    ) {
        return handler.handle(new ObjectMapper().valueToTree(Map.of("version", "2.0", "requestContext",
            Map.of("http", Map.of("method", method, "path", path)),
            "headers", headers,
            "body", body == null ? "" : body,
            "isBase64Encoded", isBase64Encoded)));
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }
}
