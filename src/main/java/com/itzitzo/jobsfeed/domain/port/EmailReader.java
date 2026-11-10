package com.itzitzo.jobsfeed.domain.port;

@FunctionalInterface
public interface EmailReader {
    String read(String messageId);
}
