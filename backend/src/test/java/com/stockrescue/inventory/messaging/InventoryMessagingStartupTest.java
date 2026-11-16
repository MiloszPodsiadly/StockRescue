package com.stockrescue.inventory.messaging;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.stockrescue.platform.kafka.KafkaAvailability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.MessageListenerContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryMessagingStartupTest {
    private final KafkaAdmin admin=mock(KafkaAdmin.class);
    private final KafkaAvailability brokerAvailability=mock(KafkaAvailability.class);
    private final KafkaListenerEndpointRegistry listeners=mock(KafkaListenerEndpointRegistry.class);
    private final InventoryMessagingMetrics metrics=mock(InventoryMessagingMetrics.class);
    private final MessageListenerContainer listener=mock(MessageListenerContainer.class);
    private final AtomicBoolean running=new AtomicBoolean();
    private final AtomicBoolean expected=new AtomicBoolean();
    private InventoryMessagingStartup startup;

    @BeforeEach
    void prepare() {
        when(listeners.getListenerContainer("inventory-change-projection")).thenReturn(listener);
        when(admin.initialize()).thenReturn(true);
        when(listener.isRunning()).thenAnswer(ignored->running.get());
        when(listener.isInExpectedState()).thenAnswer(ignored->expected.get());
        doAnswer(ignored->{running.set(true);expected.set(true);return null;}).when(listener).start();
        doAnswer(invocation->{
            running.set(false);expected.set(true);
            ((Runnable)invocation.getArgument(0)).run();
            return null;
        }).when(listener).stop(any(Runnable.class));
        startup=new InventoryMessagingStartup(admin,brokerAvailability,listeners,metrics,
                Duration.ofMillis(10),Duration.ofSeconds(1),3,true);
    }

    @Test
    void restoresAColdListenerWhenKafkaReturnsAndDoesNotRestartAConnectedContainerDuringAnOutage() {
        when(brokerAvailability.available()).thenReturn(false,true,false,true);

        startup.startAfterApplicationReady(null);
        assertThat(startup.state()).isEqualTo(InventoryMessagingStartup.State.WAITING_FOR_KAFKA);
        verify(listener,never()).start();

        startup.retryUnavailableListener();
        assertThat(startup.state()).isEqualTo(InventoryMessagingStartup.State.RUNNING);
        assertThat(running).isTrue();

        expected.set(false);
        startup.retryUnavailableListener();
        assertThat(startup.state()).isEqualTo(InventoryMessagingStartup.State.RETRYING);
        assertThat(running).isTrue();

        expected.set(true);
        startup.retryUnavailableListener();
        assertThat(startup.state()).isEqualTo(InventoryMessagingStartup.State.RUNNING);
        verify(listener,times(1)).start();
        verify(listener,never()).stop(any(Runnable.class));
        verify(listener,never()).stopAbnormally(any(Runnable.class));
    }

    @Test
    void operatorHaltPreventsSupervisedRestartUntilExplicitResume() {
        when(brokerAvailability.available()).thenReturn(true,true,true);
        startup.startAfterApplicationReady(null);
        assertThat(startup.state()).isEqualTo(InventoryMessagingStartup.State.RUNNING);

        startup.halt();
        assertThat(startup.state()).isEqualTo(InventoryMessagingStartup.State.HALTED);
        startup.retryUnavailableListener();
        assertThat(startup.state()).isEqualTo(InventoryMessagingStartup.State.HALTED);
        verify(listener,times(1)).start();

        startup.resume();
        assertThat(startup.state()).isEqualTo(InventoryMessagingStartup.State.RUNNING);
        verify(listener,times(2)).start();
        verify(metrics).consumerListenerRestartAttempted();
    }

    @Test
    void supervisorRestartsAListenerThatStopsAfterItWasRunning() {
        when(brokerAvailability.available()).thenReturn(true,true);
        startup.startAfterApplicationReady(null);
        assertThat(startup.state()).isEqualTo(InventoryMessagingStartup.State.RUNNING);

        running.set(false);
        expected.set(true);
        startup.retryUnavailableListener();

        assertThat(startup.state()).isEqualTo(InventoryMessagingStartup.State.RUNNING);
        assertThat(running).isTrue();
        verify(listener,times(2)).start();
        verify(metrics).consumerListenerRestartAttempted();
    }

    @Test
    void shutdownDuringBrokerInitializationPreventsListenerStart() throws Exception {
        var initializationEntered=new CountDownLatch(1);
        var finishInitialization=new CountDownLatch(1);
        when(brokerAvailability.available()).thenReturn(true);
        doAnswer(ignored->{
            initializationEntered.countDown();
            if(!finishInitialization.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("test initialization timed out");
            return true;
        }).when(admin).initialize();

        var starting=CompletableFuture.runAsync(()->startup.startAfterApplicationReady(null));
        assertThat(initializationEntered.await(5,TimeUnit.SECONDS)).isTrue();
        startup.applicationClosing(null);
        finishInitialization.countDown();
        starting.join();

        assertThat(startup.state()).isEqualTo(InventoryMessagingStartup.State.STOPPING);
        verify(listener,never()).start();
    }

    @Test
    void repeatedUnexpectedStopsAreBoundedAndExposeAHealthAlert() {
        when(brokerAvailability.available()).thenAnswer(ignored->true);
        startup.startAfterApplicationReady(null);

        for(int attempt=0;attempt<startup.restartMaximumAttempts();attempt++) {
            running.set(false);
            expected.set(true);
            startup.retryUnavailableListener();
            assertThat(startup.state()).isEqualTo(InventoryMessagingStartup.State.RUNNING);
        }
        running.set(false);
        startup.retryUnavailableListener();
        startup.retryUnavailableListener();

        assertThat(startup.state()).isEqualTo(InventoryMessagingStartup.State.STOPPED_UNEXPECTEDLY);
        verify(listener,times(startup.restartMaximumAttempts()+1)).start();
        var health=new InventoryMessagingHealthIndicator(listeners,metrics,startup).health();
        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("alert",true)
                .containsEntry("lastFailureType","restart_limit_reached");
    }
}
