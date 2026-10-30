package com.itzitzo.jobsfeed.service;

import com.itzitzo.jobsfeed.model.Job;
import com.itzitzo.jobsfeed.model.JobDetails;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class EmailIngestionService {
    private static final String DEFAULT_APPLICATION_URL = "https://itzitzo.nl/jobs/";
    private final EmailReader reader;
    private final JobExtractor extractor;
    private final JobStore store;

    public EmailIngestionService(EmailReader reader, JobExtractor extractor, JobStore store) {
        this.reader = reader;
        this.extractor = extractor;
        this.store = store;
    }

    public Result ingest(String messageId, Instant receivedAt) {
        if (store.isMessageProcessed(messageId)) {
            return new Result(0, true);
        }
        List<JobDetails> extracted = extractor.extractJobs(reader.read(messageId));
        if (extracted.size() > JobExtractor.MAX_JOBS) {
            throw new IllegalArgumentException("Too many extracted jobs");
        }
        List<Job> jobs = new ArrayList<>();
        for (int i = 0; i < extracted.size(); i++) {
            JobDetails details = extracted.get(i);
            if (details.applicationUrl() == null) {
                details = new JobDetails(details.title(), details.company(), details.location(),
                    details.description(), DEFAULT_APPLICATION_URL, details.publishedAt());
            }
            jobs.add(Job.fromEmail(messageId, receivedAt, i, details));
        }
        boolean saved = store.saveIngestion(messageId, List.copyOf(jobs));
        return new Result(saved ? jobs.size() : 0, !saved);
    }

    public record Result(int jobCount, boolean duplicate) {
    }
}
