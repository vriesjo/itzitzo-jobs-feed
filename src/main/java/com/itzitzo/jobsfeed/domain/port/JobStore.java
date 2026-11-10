package com.itzitzo.jobsfeed.domain.port;

import com.itzitzo.jobsfeed.domain.FeedPage;
import com.itzitzo.jobsfeed.domain.Job;
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
