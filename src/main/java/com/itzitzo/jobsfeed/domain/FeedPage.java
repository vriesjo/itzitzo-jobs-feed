package com.itzitzo.jobsfeed.domain;

import java.util.List;

public record FeedPage(List<Job> jobs, boolean more) {
    public FeedPage {
        jobs = List.copyOf(jobs);
    }
}
