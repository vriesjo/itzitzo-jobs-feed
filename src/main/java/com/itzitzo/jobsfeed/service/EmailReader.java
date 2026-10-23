package com.itzitzo.jobsfeed.service;

@FunctionalInterface
public interface EmailReader {
    String read(String messageId);
}
