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
public class KafkaHealthIndicator implements HealthIndicator, AutoCloseable {

    private final Admin client;

    public KafkaHealthIndicator(KafkaAdmin admin) {
        this.client = Admin.create(admin.getConfigurationProperties());
    }

    @Override
    public Health health() {
        try {
            var nodes = client.describeCluster(new DescribeClusterOptions().timeoutMs(2000))
                    .nodes().get(3, TimeUnit.SECONDS);
            return nodes.isEmpty() ? Health.down().build() : Health.up().build();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Health.down().build();
        } catch (Exception exception) {
            return Health.down().build();
        }
    }

    @Override
    @PreDestroy
    public void close() {
        client.close(Duration.ofSeconds(1));
    }
}
