package com.stockrescue.platform.kafka;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

@Component("kafka")
public class KafkaHealthIndicator implements HealthIndicator, KafkaAvailability, AutoCloseable {

    private final KafkaAdmin admin;
    private volatile Admin client;

    public KafkaHealthIndicator(KafkaAdmin admin) {
        this.admin = admin;
    }

    @Override
    public Health health() {
        return available()?Health.up().withDetail("state","available").build()
                :Health.down().withDetail("state","unavailable").build();
    }

    @Override
    public boolean available() {
        try {
            var nodes = client().describeCluster(new DescribeClusterOptions().timeoutMs(2000))
                    .nodes().get(3, TimeUnit.SECONDS);
            return !nodes.isEmpty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception exception) {
            return false;
        }
    }

    private Admin client() {
        var current = client;
        if (current == null) {
            synchronized (this) {
                current = client;
                if (current == null) {
                    current = Admin.create(admin.getConfigurationProperties());
                    client = current;
                }
            }
        }
        return current;
    }

    @Override
    @PreDestroy
    public void close() {
        var current = client;
        if (current != null) {
            current.close(Duration.ofSeconds(1));
        }
    }
}
