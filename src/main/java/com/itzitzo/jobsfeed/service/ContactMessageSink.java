package com.itzitzo.jobsfeed.service;

@FunctionalInterface
public interface ContactMessageSink {
    void submit(ContactMessage message);
}
