package com.itzitzo.jobsfeed.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itzitzo.jobsfeed.model.Job;
import com.itzitzo.jobsfeed.model.JobDetails;
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
            new JobDetails("Java & <Cloud> \u20ac", null, null, "Build <services>\u0001",
                "https://example.com/job?a=1&b=2", null));
        String xml = renderer.render(List.of(job));
        Document document = parse(xml);
        assertEquals("rss", document.getDocumentElement().getTagName());
        assertEquals("Java & <Cloud> \u20ac", document.getElementsByTagName("title").item(1).getTextContent());
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
        when(store.recentJobs(50)).thenReturn(List.of());
        Map<String, Object> result = http(handler, "GET", "/feed.xml");
        assertEquals(200, result.get("statusCode"));
        assertEquals(0, parse(result.get("body").toString()).getElementsByTagName("item").getLength());
        assertEquals(Map.of("Content-Type", "application/rss+xml; charset=utf-8"), result.get("headers"));
        assertEquals(false, result.get("isBase64Encoded"));
        assertEquals(404, http(handler, "GET", "/admin").get("statusCode"));
        Map<String, Object> wrongMethod = http(handler, "POST", "/feed.xml");
        assertEquals(405, wrongMethod.get("statusCode"));
        assertEquals("GET", ((Map<?, ?>) wrongMethod.get("headers")).get("Allow"));
        verify(store, times(1)).recentJobs(anyInt());
    }

    @Test
    void reportsDependencyFailuresRatherThanReturningAnEmptyFeed() {
        JobStore store = mock(JobStore.class);
        when(store.recentJobs(50)).thenThrow(new IllegalStateException("private error details"));
        Map<String, Object> result = http(new HttpFeedHandler(store, renderer), "GET", "/feed.xml");
        assertEquals(500, result.get("statusCode"));
        assertFalse(result.get("body").toString().contains("private error"));
    }

    private static Map<String, Object> http(HttpFeedHandler handler, String method, String path) {
        return handler.handle(new ObjectMapper().valueToTree(Map.of("version", "2.0", "requestContext",
            Map.of("http", Map.of("method", method, "path", path)))));
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }
}
