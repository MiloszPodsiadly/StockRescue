package com.stockrescue.inventory.messaging;

import java.time.Duration;

import com.stockrescue.platform.kafka.KafkaAvailability;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.common.KafkaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.event.ConcurrentContainerStoppedEvent;
import org.springframework.kafka.event.ConsumerFailedToStartEvent;
import org.springframework.kafka.event.ConsumerStartedEvent;
import org.springframework.kafka.event.ConsumerStoppedEvent;
import org.springframework.kafka.event.KafkaEvent;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InventoryMessagingStartup {
    private static final Logger log=LoggerFactory.getLogger(InventoryMessagingStartup.class);
    private static final String LISTENER_ID="inventory-change-projection";
    private static final Duration START_TIMEOUT=Duration.ofSeconds(30);
    private static final Duration STABLE_RUNTIME=Duration.ofSeconds(30);

    private final KafkaAdmin admin;
    private final KafkaAvailability brokerAvailability;
    private final KafkaListenerEndpointRegistry listeners;
    private final InventoryMessagingMetrics metrics;
    private final Duration restartInitialBackoff;
    private final Duration restartMaximumBackoff;
    private final int restartMaximumAttempts;
    private boolean applicationReady;
    private volatile boolean desiredRunning;
    private volatile boolean shutdownRequested;
    private boolean startAttempted;
    private boolean startPending;
    private boolean recoveryStopRequested;
    private boolean waitingLogWritten;
    private boolean restartLimitLogged;
    private long startPendingSinceNanos;
    private long nextStartNanos;
    private long runningSinceNanos;
    private volatile int restartAttempts;
    private volatile boolean brokerAvailable;
    private volatile State state=State.STARTING;
    private volatile String lastFailureType;

    public InventoryMessagingStartup(KafkaAdmin admin,KafkaAvailability brokerAvailability,
            KafkaListenerEndpointRegistry listeners,InventoryMessagingMetrics metrics,
            @Value("${stockrescue.inventory.messaging.listener-restart-initial-backoff:1s}") Duration restartInitialBackoff,
            @Value("${stockrescue.inventory.messaging.listener-restart-maximum-backoff:30s}") Duration restartMaximumBackoff,
            @Value("${stockrescue.inventory.messaging.listener-restart-maximum-attempts:5}") int restartMaximumAttempts,
            @Value("${stockrescue.inventory.messaging.listener-enabled:true}") boolean listenerEnabled) {
        if(restartInitialBackoff.isNegative() || restartInitialBackoff.isZero()
                || restartMaximumBackoff.compareTo(restartInitialBackoff)<0
                || restartMaximumBackoff.compareTo(Duration.ofMinutes(5))>0 || restartMaximumAttempts<1) {
            throw new IllegalArgumentException("Inventory listener restart bounds are invalid");
        }
        this.admin=admin;
        this.brokerAvailability=brokerAvailability;
        this.listeners=listeners;
        this.metrics=metrics;
        this.restartInitialBackoff=restartInitialBackoff;
        this.restartMaximumBackoff=restartMaximumBackoff;
        this.restartMaximumAttempts=restartMaximumAttempts;
        this.desiredRunning=listenerEnabled;
        if(!listenerEnabled) state=State.HALTED;
    }

    @EventListener
    public synchronized void startAfterApplicationReady(ApplicationReadyEvent ignored) {
        applicationReady=true;
        reconcile();
    }

    @EventListener
    public void applicationClosing(ContextClosedEvent ignored) {
        shutdownRequested=true;
        desiredRunning=false;
        state=State.STOPPING;
    }

    @Scheduled(fixedDelayString="${stockrescue.inventory.messaging.listener-startup-retry:5s}")
    public synchronized void retryUnavailableListener() { reconcile(); }

    @EventListener
    public synchronized void consumerStarting(org.springframework.kafka.event.ConsumerStartingEvent event) {
        if(isInventoryListenerEvent(event) && desiredRunning && !shutdownRequested) state=State.STARTING;
    }

    @EventListener
    public synchronized void consumerStarted(ConsumerStartedEvent event) {
        if(!isInventoryListenerEvent(event) || !desiredRunning || shutdownRequested) return;
        MessageListenerContainer listener=listeners.getListenerContainer(LISTENER_ID);
        if(!isFullyRunning(listener)) {
            state=State.STARTING;
            return;
        }
        startPending=false;
        runningSinceNanos=System.nanoTime();
        nextStartNanos=0;
        lastFailureType=null;
        restartLimitLogged=false;
        state=brokerAvailable?State.RUNNING:State.RETRYING;
    }

    @EventListener
    public synchronized void consumerFailedToStart(ConsumerFailedToStartEvent event) {
        if(!isInventoryListenerEvent(event) || !desiredRunning || shutdownRequested) return;
        startPending=false;
        recordStartFailure("ConsumerFailedToStartEvent");
    }

    @EventListener
    public synchronized void consumerStopped(ConsumerStoppedEvent event) {
        if(!isInventoryListenerEvent(event) || shutdownRequested || !desiredRunning) return;
        if(recoveryStopRequested) return;
        scheduleUnexpectedRestart(event.getReason().name());
    }

    @EventListener
    public synchronized void concurrentContainerStopped(ConcurrentContainerStoppedEvent event) {
        if(!isInventoryListenerEvent(event) || shutdownRequested || !desiredRunning) return;
        if(recoveryStopRequested) return;
        scheduleUnexpectedRestart(event.getReason().name());
    }

    public synchronized void halt() {
        desiredRunning=false;
        startPending=false;
        nextStartNanos=0;
        state=State.STOPPING;
        MessageListenerContainer listener=listeners.getListenerContainer(LISTENER_ID);
        if(listener==null || !listener.isRunning()) {
            state=State.HALTED;
            return;
        }
        listener.stop(()->finishHalt());
    }

    public synchronized void resume() {
        if(shutdownRequested) return;
        desiredRunning=true;
        restartAttempts=0;
        restartLimitLogged=false;
        nextStartNanos=0;
        startPending=false;
        state=State.STARTING;
        if(applicationReady) reconcile();
    }

    public State state() { return state; }
    public boolean listenerRunning() { return isFullyRunning(listeners.getListenerContainer(LISTENER_ID)); }
    public boolean brokerAvailable() { return brokerAvailable; }
    public int restartAttempts() { return restartAttempts; }
    public int restartMaximumAttempts() { return restartMaximumAttempts; }
    public String lastFailureType() { return lastFailureType; }
    public boolean desiredRunning() { return desiredRunning; }

    private void reconcile() {
        if(!applicationReady) return;
        if(shutdownRequested) {
            state=State.STOPPING;
            return;
        }
        MessageListenerContainer listener=listeners.getListenerContainer(LISTENER_ID);
        if(listener==null) {
            failStopped("listener_missing");
            return;
        }
        if(!desiredRunning) {
            if(listener.isRunning()) {
                state=State.STOPPING;
                listener.stop(()->finishHalt());
            } else state=State.HALTED;
            return;
        }

        brokerAvailable=brokerAvailability.available();
        if(shutdownRequested || !desiredRunning) {
            state=State.STOPPING;
            return;
        }
        boolean running=listener.isRunning();
        boolean expected=listener.isInExpectedState();
        boolean fullyRunning=isFullyRunning(listener);
        long now=System.nanoTime();
        if(running) {
            if(!expected || !fullyRunning) {
                if(!brokerAvailable) {
                    state=State.RETRYING;
                    logWaitingForKafka();
                    return;
                }
                if(startPending && now-startPendingSinceNanos<START_TIMEOUT.toNanos()) {
                    state=State.STARTING;
                    return;
                }
                if(startPending) lastFailureType="ListenerStartTimedOut";
                startPending=false;
                stopUnexpectedChildren(listener);
                return;
            }
            startPending=false;
            if(runningSinceNanos==0) runningSinceNanos=now;
            if(now-runningSinceNanos>=STABLE_RUNTIME.toNanos()) {
                restartAttempts=0;
                restartLimitLogged=false;
            }
            state=brokerAvailable?State.RUNNING:State.RETRYING;
            if(!brokerAvailable) logWaitingForKafka();
            else waitingLogWritten=false;
            return;
        }

        if(startPending) {
            if(now-startPendingSinceNanos<START_TIMEOUT.toNanos()) {
                state=State.STARTING;
                return;
            }
            startPending=false;
            recordStartFailure("ListenerStartTimedOut");
        }
        if(recoveryStopRequested) {
            state=State.STOPPING;
            return;
        }
        if(!brokerAvailable) {
            state=State.WAITING_FOR_KAFKA;
            logWaitingForKafka();
            return;
        }
        if(startAttempted && restartAttempts>=restartMaximumAttempts) {
            failStopped(lastFailureType==null?"restart_limit_reached":lastFailureType);
            return;
        }
        if(now<nextStartNanos) {
            state=State.RETRYING;
            return;
        }
        startListener(listener);
    }

    private void startListener(MessageListenerContainer listener) {
        boolean restart=startAttempted;
        state=restart?State.RETRYING:State.STARTING;
        if(restart) {
            if(restartAttempts>=restartMaximumAttempts) {
                failStopped(lastFailureType==null?"restart_limit_reached":lastFailureType);
                return;
            }
            restartAttempts++;
            metrics.consumerListenerRestartAttempted();
        }
        startAttempted=true;
        try {
            boolean adminReady=admin.initialize();
            if(shutdownRequested || !desiredRunning) {
                state=State.STOPPING;
                return;
            }
            if(!adminReady) {
                brokerAvailable=false;
                recordStartFailure("KafkaAdminUnavailable");
                return;
            }
            startPending=true;
            startPendingSinceNanos=System.nanoTime();
            runningSinceNanos=0;
            nextStartNanos=0;
            listener.start();
            if(shutdownRequested || !desiredRunning) {
                state=State.STOPPING;
                return;
            }
            if(isFullyRunning(listener)) {
                startPending=false;
                runningSinceNanos=System.nanoTime();
                state=brokerAvailable?State.RUNNING:State.RETRYING;
            }
        } catch(RuntimeException failure) {
            startPending=false;
            if(shutdownRequested || !desiredRunning) {
                state=State.STOPPING;
                return;
            }
            brokerAvailable=brokerAvailability.available();
            recordStartFailure(failure.getClass().getSimpleName());
            if(isKafkaStartupFailure(failure)) logWaitingForKafka();
        }
    }

    private void stopUnexpectedChildren(MessageListenerContainer listener) {
        if(recoveryStopRequested) return;
        recoveryStopRequested=true;
        state=State.STOPPING;
        listener.stopAbnormally(()->{
            synchronized(this) {
                recoveryStopRequested=false;
                runningSinceNanos=0;
                scheduleUnexpectedRestart("UnexpectedChildStop");
            }
        });
    }

    private void scheduleUnexpectedRestart(String reason) {
        if(shutdownRequested || !desiredRunning) return;
        if(state==State.STOPPED_UNEXPECTEDLY && nextStartNanos>System.nanoTime()) return;
        lastFailureType=safeFailureType(reason);
        startPending=false;
        runningSinceNanos=0;
        if(restartAttempts>=restartMaximumAttempts && startAttempted) {
            failStopped(lastFailureType);
            return;
        }
        nextStartNanos=System.nanoTime()+restartDelayNanos(Math.max(1,restartAttempts+1));
        state=State.STOPPED_UNEXPECTEDLY;
        log.warn("Inventory projection listener stopped unexpectedly; restart scheduled (attempt {}/{})",
                restartAttempts+1,restartMaximumAttempts);
    }

    private void recordStartFailure(String reason) {
        lastFailureType=safeFailureType(reason);
        if(startAttempted && restartAttempts>=restartMaximumAttempts) {
            failStopped(lastFailureType);
            return;
        }
        nextStartNanos=System.nanoTime()+restartDelayNanos(Math.max(1,restartAttempts+1));
        state=brokerAvailable?State.RETRYING:State.WAITING_FOR_KAFKA;
        log.warn("Inventory projection listener start failed ({})",lastFailureType);
    }

    private long restartDelayNanos(int attempt) {
        long delay=restartInitialBackoff.toNanos();
        long maximum=restartMaximumBackoff.toNanos();
        for(int step=1;step<attempt && delay<maximum;step++) delay=delay>maximum/2?maximum:Math.min(maximum,delay*2);
        return delay;
    }

    private void failStopped(String reason) {
        lastFailureType=safeFailureType(reason);
        startPending=false;
        nextStartNanos=0;
        state=State.STOPPED_UNEXPECTEDLY;
        if(!restartLimitLogged) {
            log.error("Inventory projection listener remains stopped after {} restart attempts ({})",
                    restartAttempts,lastFailureType);
            restartLimitLogged=true;
        }
    }

    private void finishHalt() {
        synchronized(this) {
            if(!shutdownRequested && !desiredRunning) state=State.HALTED;
        }
    }

    private void logWaitingForKafka() {
        if(!waitingLogWritten) {
            log.warn("Inventory messaging is waiting for Kafka; database-backed API remains available");
            waitingLogWritten=true;
        }
    }

    private boolean isInventoryListenerEvent(KafkaEvent event) {
        MessageListenerContainer container=event.getContainer(MessageListenerContainer.class);
        if(container==null) return false;
        MessageListenerContainer registered=listeners.getListenerContainer(LISTENER_ID);
        return container==registered || LISTENER_ID.equals(container.getMainListenerId())
                || LISTENER_ID.equals(container.getListenerId());
    }

    private static boolean isFullyRunning(MessageListenerContainer listener) {
        if(listener==null || !listener.isRunning() || !listener.isInExpectedState()) return false;
        if(listener instanceof ConcurrentMessageListenerContainer<?,?> concurrent) {
            var children=concurrent.getContainers();
            return !children.isEmpty() && children.stream()
                    .allMatch(child->child.isRunning() && child.isInExpectedState());
        }
        return true;
    }

    private static String safeFailureType(String reason) {
        if(reason==null || reason.isBlank()) return "Unknown";
        return reason.matches("[A-Za-z0-9_$.-]{1,80}")?reason:"Unknown";
    }

    private static boolean isKafkaStartupFailure(Throwable failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            if(cause instanceof KafkaException || cause instanceof org.springframework.kafka.KafkaException) return true;
        }
        return false;
    }

    @PreDestroy
    public void stopping() {
        shutdownRequested=true;
        desiredRunning=false;
        state=State.STOPPING;
    }

    public enum State { STARTING, WAITING_FOR_KAFKA, RUNNING, RETRYING, STOPPED_UNEXPECTEDLY, STOPPING, HALTED }
}
