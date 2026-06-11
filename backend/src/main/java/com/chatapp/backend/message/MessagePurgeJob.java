package com.chatapp.backend.message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Drops messages older than the configured TTL (24h). History queries also
 * filter by the same cutoff, so expired messages never surface even between runs.
 */
@Component
public class MessagePurgeJob {

    private static final Logger log = LoggerFactory.getLogger(MessagePurgeJob.class);

    private final MessageRepository messageRepository;
    private final MessageImageRepository imageRepository;
    private final Duration ttl;

    public MessagePurgeJob(MessageRepository messageRepository, MessageImageRepository imageRepository,
                           @Value("${app.messages.ttl-hours}") long ttlHours) {
        this.messageRepository = messageRepository;
        this.imageRepository = imageRepository;
        this.ttl = Duration.ofHours(ttlHours);
    }

    @Scheduled(fixedRateString = "${app.messages.purge-rate-ms}")
    @Transactional
    public void purgeExpired() {
        Instant cutoff = Instant.now().minus(ttl);
        imageRepository.deleteForMessagesOlderThan(cutoff); // FK: images first
        int deleted = messageRepository.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Purged {} expired messages", deleted);
        }
    }
}
