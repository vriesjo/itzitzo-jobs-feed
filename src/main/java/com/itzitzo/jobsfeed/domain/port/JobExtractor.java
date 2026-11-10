package com.itzitzo.jobsfeed.domain.port;

import com.itzitzo.jobsfeed.domain.JobDetails;
import java.util.List;

@FunctionalInterface
public interface JobExtractor {
    int MAX_JOBS = 20;

    List<JobDetails> extractJobs(String emailText);
}
