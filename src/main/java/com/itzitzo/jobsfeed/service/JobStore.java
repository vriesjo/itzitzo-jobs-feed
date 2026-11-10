package com.itzitzo.jobsfeed.service;

import com.itzitzo.jobsfeed.model.Job;
import java.util.List;
import java.util.Optional;

public interface JobStore {
    void saveJob(String pk, String sk, String payload);

    Optional<String> getJob(String pk, String sk);

    boolean isMessageProcessed(String messageId);

    boolean saveIngestion(String messageId, List<Job> jobs);

    List<Job> recentJobs(int limit);

    FeedPage recentJobPage(int limit, String exclusiveSortKey);
}
