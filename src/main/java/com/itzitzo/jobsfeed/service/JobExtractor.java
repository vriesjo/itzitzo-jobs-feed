package com.itzitzo.jobsfeed.service;

import com.itzitzo.jobsfeed.model.JobDetails;
import java.util.List;

@FunctionalInterface
public interface JobExtractor {
    int MAX_JOBS = 20;

    List<JobDetails> extractJobs(String emailText);
}
