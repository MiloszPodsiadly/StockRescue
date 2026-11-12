package com.stockrescue.inventory.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="stockrescue.inventory.outbox-relay",name="enabled",havingValue="true",matchIfMissing=true)
public class InventoryOutboxPoller {
    private final InventoryOutboxRelay relay;

    public InventoryOutboxPoller(InventoryOutboxRelay relay) { this.relay=relay; }

    @Scheduled(fixedDelayString="${stockrescue.inventory.outbox-relay.fixed-delay:1000}")
    public void poll() { relay.dispatchOnce(); }
}
