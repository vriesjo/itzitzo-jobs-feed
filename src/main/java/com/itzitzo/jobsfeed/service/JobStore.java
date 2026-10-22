package com.itzitzo.jobsfeed.service;

import java.util.Optional;

public interface JobStore {
    void saveJob(String pk, String sk, String payload);

    Optional<String> getJob(String pk, String sk);
}
