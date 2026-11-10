package com.itzitzo.jobsfeed.service;

import com.itzitzo.jobsfeed.model.Job;
import java.util.List;

public record FeedPage(List<Job> jobs, boolean more) {
    public FeedPage {
        jobs = List.copyOf(jobs);
    }
}
