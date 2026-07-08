package com.orderplatform.inventory.outbox;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Finds unpublished outbox rows on a fixed interval and hands each to OutboxRelay. */
@Component
public class OutboxPoller {

    private final OutboxEventRepository repository;
    private final OutboxRelay relay;

    public OutboxPoller(OutboxEventRepository repository, OutboxRelay relay) {
        this.repository = repository;
        this.relay = relay;
    }

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        List<UUID> unpublished = repository.findUnpublishedIds();
        for (UUID id : unpublished) {
            relay.relayOne(id);
        }
    }
}
