package com.itzitzo.jobsfeed.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.itzitzo.jobsfeed.model.Job;
import com.itzitzo.jobsfeed.model.JobDetails;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FeedVacanciesTest {
    @Test
    void keepsTheNewestRealUrlAndCollapsesPlaceholderCopies() {
        Job newest = job("new", "2026-10-22T12:00:00.200000000Z", "Senior Java", "Example", "https://jobs.example/1");
        Job olderUrl = job("old-url", "2026-10-22T12:00:00.100000000Z", "Other title", "Other", "https://jobs.example/1");
        Job newestPlaceholder = job("new-text", "2026-10-22T12:00:00.050000000Z", "  Platform Engineer ", "Acme", null);
        Job olderPlaceholder = job("old-text", "2026-10-22T12:00:00Z", "platform   engineer", "acme",
            JobDetails.PLACEHOLDER_APPLICATION_URL);
        Job distinct = job("distinct", "2026-10-22T11:00:00Z", "Platform Engineer", "Other Co",
            JobDetails.PLACEHOLDER_APPLICATION_URL);

        List<Job> feed = FeedVacancies.collect(new ListStore(List.of(
            newest, olderUrl, newestPlaceholder, olderPlaceholder, distinct)), 50);

        assertEquals(List.of(newest, newestPlaceholder, distinct), feed);
    }

    @Test
    void continuesPastAPageOfDuplicatesUntilTheFeedIsFull() {
        List<Job> stored = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            stored.add(job("dup-" + i, instant(50 - i), "Same role", "Same Co", "https://jobs.example/same"));
        }
        stored.add(job("second", instant(0), "Second role", "Same Co", "https://jobs.example/second"));

        List<Job> feed = FeedVacancies.collect(new ListStore(stored), 2);

        assertEquals(List.of(stored.get(0), stored.get(50)), feed);
    }

    private static Instant instant(int seconds) {
        return Instant.parse("2026-10-22T12:00:00Z").plusSeconds(seconds);
    }

    private static Job job(String messageId, String receivedAt, String title, String company, String url) {
        return job(messageId, Instant.parse(receivedAt), title, company, url);
    }

    private static Job job(String messageId, Instant receivedAt, String title, String company, String url) {
        return Job.fromEmail(messageId, receivedAt, 0, new JobDetails(title, company, null, "Build services.", url, null));
    }

    private static final class ListStore implements JobStore {
        private final List<Job> jobs;

        private ListStore(List<Job> jobs) {
            this.jobs = jobs;
        }

        @Override
        public void saveJob(String pk, String sk, String payload) {
        }

        @Override
        public Optional<String> getJob(String pk, String sk) {
            return Optional.empty();
        }

        @Override
        public boolean isMessageProcessed(String messageId) {
            return false;
        }

        @Override
        public boolean saveIngestion(String messageId, List<Job> ingested) {
            return false;
        }

        @Override
        public List<Job> recentJobs(int limit) {
            return recentJobPage(limit, null).jobs();
        }

        @Override
        public FeedPage recentJobPage(int limit, String exclusiveSortKey) {
            List<Job> ordered = jobs.stream()
                .sorted(Comparator.comparing(Job::sortKey).reversed())
                .filter(job -> exclusiveSortKey == null || job.sortKey().compareTo(exclusiveSortKey) < 0)
                .toList();
            List<Job> page = ordered.stream().limit(limit).toList();
            return new FeedPage(page, ordered.size() > page.size());
        }
    }
}
