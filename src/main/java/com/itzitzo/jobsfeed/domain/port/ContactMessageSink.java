package com.itzitzo.jobsfeed.domain.port;

import com.itzitzo.jobsfeed.domain.ContactMessage;

@FunctionalInterface
public interface ContactMessageSink {
    void submit(ContactMessage message);
}
