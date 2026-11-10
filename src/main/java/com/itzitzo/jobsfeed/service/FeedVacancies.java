package com.itzitzo.jobsfeed.service;

import com.itzitzo.jobsfeed.model.Job;
import com.itzitzo.jobsfeed.model.JobDetails;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class FeedVacancies {
    public static final int FEED_LIMIT = 50;
    private static final int PAGE_SIZE = 50;

    private FeedVacancies() {
    }

    public static List<Job> collect(JobStore store, int limit) {
        if (limit < 1 || limit > FEED_LIMIT) {
            throw new IllegalArgumentException("Feed limit must be between 1 and 50");
        }
        List<Job> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cursor = null;
        boolean more = true;
        while (more && unique.size() < limit) {
            FeedPage page = store.recentJobPage(PAGE_SIZE, cursor);
            if (page.jobs().isEmpty()) {
                break;
            }
            for (Job job : page.jobs()) {
                if (seen.add(vacancyKey(job)) && unique.size() < limit) {
                    unique.add(job);
                }
            }
            String nextCursor = page.jobs().get(page.jobs().size() - 1).sortKey();
            if (nextCursor.equals(cursor)) {
                break;
            }
            cursor = nextCursor;
            more = page.more();
        }
        return List.copyOf(unique);
    }

    static String vacancyKey(Job job) {
        String url = job.details().applicationUrl();
        if (url != null && !JobDetails.PLACEHOLDER_APPLICATION_URL.equals(url)) {
            return "url\n" + url;
        }
        return "text\n" + normalize(job.details().title()) + "\n" + normalize(job.details().company());
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
