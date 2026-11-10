package com.itzitzo.jobsfeed.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.itzitzo.jobsfeed.domain.port.EmailReader;
import com.itzitzo.jobsfeed.domain.port.JobExtractor;
import com.itzitzo.jobsfeed.domain.port.JobStore;
import com.itzitzo.jobsfeed.domain.Job;
import com.itzitzo.jobsfeed.domain.JobDetails;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.MockitoAnnotations;

class EmailIngestionServiceTest {
    @Captor
    private ArgumentCaptor<List<Job>> jobs;

    @Test
    void usesStableIdsAndReceiptTimesAndAvoidsDuplicateInference() throws Exception {
        try (AutoCloseable ignored = MockitoAnnotations.openMocks(this)) {
            EmailReader reader = mock(EmailReader.class);
            JobExtractor extractor = mock(JobExtractor.class);
            JobStore store = mock(JobStore.class);
            when(reader.read("message")).thenReturn("Job email");
            when(extractor.extractJobs("Job email")).thenReturn(List.of(
                new JobDetails("Java Developer", null, null, "Build services.", null, null)));
            when(store.saveIngestion(eq("message"), anyList())).thenReturn(true);
            Instant receivedAt = Instant.parse("2026-10-22T12:00:00Z");
            EmailIngestionService service = new EmailIngestionService(reader, extractor, store);
            assertEquals(new EmailIngestionService.Result(1, false), service.ingest("message", receivedAt));
            service.ingest("message", receivedAt);
            verify(store, times(2)).saveIngestion(eq("message"), jobs.capture());
            assertEquals(jobs.getAllValues().get(0), jobs.getAllValues().get(1));
            assertEquals(receivedAt, jobs.getValue().get(0).receivedAt());
            when(store.isMessageProcessed("message")).thenReturn(true);
            assertEquals(new EmailIngestionService.Result(0, true), service.ingest("message", receivedAt));
            verify(extractor, times(2)).extractJobs(anyString());
        }
    }

    @Test
    void leavesFailedExtractionsUnmarkedAndReportsConcurrentDuplicates() {
        JobStore store = mock(JobStore.class);
        EmailIngestionService failing = new EmailIngestionService(id -> "email", text -> {
            throw new IllegalArgumentException("Invalid extraction");
        }, store);
        assertThrows(IllegalArgumentException.class, () -> failing.ingest("message", Instant.now()));
        verify(store, never()).saveIngestion(anyString(), anyList());
        EmailIngestionService concurrent = new EmailIngestionService(id -> "email", text -> List.of(), store);
        assertEquals(new EmailIngestionService.Result(0, true), concurrent.ingest("message", Instant.now()));
        verify(store).saveIngestion("message", List.of());
    }

    @Test
    void usesJobsPageAsFallbackAndPreservesExtractedApplicationUrls() throws Exception {
        try (AutoCloseable ignored = MockitoAnnotations.openMocks(this)) {
            JobStore store = mock(JobStore.class);
            JobDetails withoutUrl = new JobDetails("Java Developer", null, null, "Build services.", null, null);
            JobDetails withUrl = new JobDetails(
                "Data Engineer", null, null, "Build data services.", "https://freelance.nl/jobs/456", null);
            when(store.saveIngestion(anyString(), anyList())).thenReturn(true);
            EmailIngestionService service = new EmailIngestionService(
                id -> "email", text -> List.of(withoutUrl, withUrl), store);

            service.ingest("message", Instant.parse("2026-10-22T12:00:00Z"));

            verify(store).saveIngestion(eq("message"), jobs.capture());
            assertEquals("https://itzitzo.nl/jobs/", jobs.getValue().get(0).details().applicationUrl());
            assertEquals("https://freelance.nl/jobs/456", jobs.getValue().get(1).details().applicationUrl());
        }
    }
}
