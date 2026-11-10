package com.itzitzo.jobsfeed.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;

class BedrockServiceTest {
    private static final String JOB = """
        {"title":"Java Developer","company":null,"location":"Remote",
         "description":"Build services.","applicationUrl":"https://example.com/job?a=1&b=2","publishedAt":null}
        """;

    @Test
    void extractsThroughConverseWithTheConfiguredProfile() {
        BedrockRuntimeClient client = mock(BedrockRuntimeClient.class);
        when(client.converse(any(ConverseRequest.class))).thenReturn(response(
            StopReason.END_TURN, "{\"jobs\":[" + JOB + "]}"));
        BedrockService service = new BedrockService(client, "eu.amazon.nova-micro-v1:0");
        assertEquals("Java Developer", service.extractJobs("Email \"body\"\nwith newlines").get(0).title());
        ArgumentCaptor<ConverseRequest> request = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(client).converse(request.capture());
        assertEquals("eu.amazon.nova-micro-v1:0", request.getValue().modelId());
        assertEquals("Email \"body\"\nwith newlines", request.getValue().messages().get(0).content().get(0).text());
        assertTrue(request.getValue().system().get(0).text().contains("untrusted"));
        assertTrue(request.getValue().system().get(0).text().contains("copy the exact value after \"URL:\""));
        assertEquals(0.0f, request.getValue().inferenceConfig().temperature());
    }

    @Test
    void rejectsTruncatedOrUnexpectedModelResponses() {
        BedrockRuntimeClient client = mock(BedrockRuntimeClient.class);
        BedrockService service = new BedrockService(client, "eu.amazon.nova-lite-v1:0");
        when(client.converse(any(ConverseRequest.class))).thenReturn(response(StopReason.MAX_TOKENS, "{\"jobs\":[]}"));
        assertThrows(IllegalStateException.class, () -> service.extractJobs("email"));
        when(client.converse(any(ConverseRequest.class))).thenReturn(response(StopReason.END_TURN, ""));
        assertThrows(IllegalStateException.class, () -> service.extractJobs("email"));
    }

    @Test
    void validatesExtractionRatherThanCoercingOrPartiallySaving() {
        assertEquals(List.of(), BedrockService.parseJobs("{\"jobs\":[]}"));
        assertEquals(1, BedrockService.parseJobs(
            "{\"jobs\":[{\"title\":\"Java Developer\",\"description\":\"Build services.\"}]}").size());
        for (String json : List.of(
            "not json", "{\"jobs\":null}", "{\"jobs\":[],\"extra\":true}",
            "{\"jobs\":[],\"jobs\":[]}",
            "{\"jobs\":[{}]}", "{\"jobs\":[" + JOB.replace("\"Java Developer\"", "42") + "]}",
            "{\"jobs\":[" + JOB.replace("https://example.com/job?a=1&b=2", "javascript:alert(1)") + "]}",
            "{\"jobs\":[" + String.join(",", java.util.Collections.nCopies(21, JOB)) + "]}",
            "{\"jobs\":[" + JOB.replace("\"publishedAt\":null", "\"publishedAt\":\"yesterday\"") + "]}")) {
            assertThrows(RuntimeException.class, () -> BedrockService.parseJobs(json));
        }
    }

    @Test
    void acceptsJsonWrappedInMarkdownOrProse() {
        assertEquals(1, BedrockService.parseJobs("""
            Here is the extracted result:
            ```json
            {"jobs":[{"title":"Java Developer","description":"Build services."}]}
            ```
            """).size());
        assertEquals(List.of(), BedrockService.parseJobs("""
            Extraction completed. {"jobs":[]} Thanks!
            """));
    }

    private static ConverseResponse response(StopReason reason, String text) {
        return ConverseResponse.builder().stopReason(reason).output(ConverseOutput.builder()
            .message(Message.builder().role("assistant").content(ContentBlock.fromText(text)).build()).build()).build();
    }
}
