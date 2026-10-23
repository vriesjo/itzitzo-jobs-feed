package com.itzitzo.jobsfeed.service;

import com.itzitzo.jobsfeed.model.Job;
import com.itzitzo.jobsfeed.model.JobDetails;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class EmailIngestionService {
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
            jobs.add(Job.fromEmail(messageId, receivedAt, i, extracted.get(i)));
        }
        boolean saved = store.saveIngestion(messageId, List.copyOf(jobs));
        return new Result(saved ? jobs.size() : 0, !saved);
    }

    public record Result(int jobCount, boolean duplicate) {
    }
}
