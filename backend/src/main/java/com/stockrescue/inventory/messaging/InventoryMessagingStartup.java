package com.stockrescue.inventory.messaging;

import org.apache.kafka.common.KafkaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.stockrescue.platform.kafka.KafkaAvailability;

@Component
public class InventoryMessagingStartup {
    private static final Logger log=LoggerFactory.getLogger(InventoryMessagingStartup.class);
    private static final String LISTENER_ID="inventory-change-projection";

    private final KafkaAdmin admin;
    private final KafkaAvailability brokerAvailability;
    private final KafkaListenerEndpointRegistry listeners;
    private boolean applicationReady;
    private boolean listenerStarted;
    private boolean startupFailureLogged;

    public InventoryMessagingStartup(KafkaAdmin admin,KafkaAvailability brokerAvailability,
            KafkaListenerEndpointRegistry listeners) {
        this.admin=admin;this.brokerAvailability=brokerAvailability;this.listeners=listeners;
    }

    @EventListener
    public synchronized void startAfterApplicationReady(ApplicationReadyEvent ignored) {
        applicationReady=true;
        startIfAvailable();
    }

    @Scheduled(fixedDelayString="${stockrescue.inventory.messaging.listener-startup-retry:5s}")
    public synchronized void retryUnavailableListener() {
        if(applicationReady && !listenerStarted) startIfAvailable();
    }

    private void startIfAvailable() {
        MessageListenerContainer listener=listeners.getListenerContainer(LISTENER_ID);
        if(listener==null) throw new IllegalStateException("Inventory projection listener is not registered");
        if(listenerStarted || listener.isRunning()) {
            listenerStarted=true;
            return;
        }
        if(!brokerAvailability.available()) {
            logWaitingForKafka();
            return;
        }

        try {
            if(!admin.initialize()) {
                logWaitingForKafka();
                return;
            }
            listener.start();
            if(listener.isRunning()) {
                listenerStarted=true;
                if(startupFailureLogged) log.info("Inventory messaging started after Kafka recovery");
                startupFailureLogged=false;
            }
        } catch(RuntimeException failure) {
            if(!isKafkaStartupFailure(failure)) throw failure;
            logWaitingForKafka();
        }
    }

    private void logWaitingForKafka() {
        if(!startupFailureLogged) {
            log.warn("Inventory messaging is waiting for Kafka; database-backed API remains available");
            startupFailureLogged=true;
        }
    }

    private static boolean isKafkaStartupFailure(Throwable failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            if(cause instanceof KafkaException || cause instanceof org.springframework.kafka.KafkaException) return true;
        }
        return false;
    }
}
