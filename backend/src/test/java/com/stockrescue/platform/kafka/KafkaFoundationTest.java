package com.stockrescue.platform.kafka;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.stockrescue.support.InfrastructureTestBase;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaAdmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KafkaFoundationTest extends InfrastructureTestBase {

    @Autowired
    private KafkaHealthIndicator health;

    @Autowired
    private ApplicationContext application;

    @Test
    void defaultRuntimeNeedsNoSmokeTopicAndDiagnosticsDoNotProvisionTopics() throws Exception {
        assertThat(application.getBeansOfType(NewTopic.class)).isEmpty();
        try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            var before = admin.listTopics().names().get(3, TimeUnit.SECONDS);
            assertThat(before).doesNotContain("stockrescue.infrastructure.smoke.v1");
            assertThat(health.health().getStatus()).isEqualTo(Status.UP);
            assertThat(admin.listTopics().names().get(3, TimeUnit.SECONDS)).isEqualTo(before);
        }
    }

    @Test
    void realConsumerReceivesTheMatchingRecordAndBrokerDoesNotAutoCreateUnknownTopics() throws Exception {
        try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            var topic = "stockrescue-test-" + UUID.randomUUID();
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1)
                    .configs(Map.of("retention.ms", "60000")))).all().get(3, TimeUnit.SECONDS);
            try {
                var smoke = new KafkaSmoke(KAFKA.getBootstrapServers(), topic);
                var first = smoke.run(Duration.ofSeconds(10));
                var second = smoke.run(Duration.ofSeconds(10));
                assertThat(first.correlationId()).matches("[a-f0-9-]{36}");
                assertThat(second.correlationId()).isNotEqualTo(first.correlationId());
                assertThat(first.payload()).isEqualTo(KafkaSmoke.PAYLOAD);
                assertThat(second.payload()).isEqualTo(KafkaSmoke.PAYLOAD);
            } finally {
                admin.deleteTopics(List.of(topic)).all().get(3, TimeUnit.SECONDS);
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                        assertThat(admin.listTopics().names().get(3, TimeUnit.SECONDS)).doesNotContain(topic));
            }
            var resource = new ConfigResource(ConfigResource.Type.BROKER,
                    admin.describeCluster().controller().get(3, TimeUnit.SECONDS).idString());
            var config = admin.describeConfigs(List.of(resource)).all().get(3, TimeUnit.SECONDS).get(resource);
            assertThat(config.get("auto.create.topics.enable").value()).isEqualTo("false");
        }
    }

    @Test
    void unreachableBrokerFailsTheSmokeWithinABoundedDeadline() {
        long started = System.nanoTime();
        assertThatThrownBy(() -> new KafkaSmoke("127.0.0.1:1", "stockrescue-test-unavailable").run(Duration.ofSeconds(5)))
                .isInstanceOf(Exception.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
    }

    @Test
    void unresolvedBrokerDoesNotBreakDiagnosticConstruction() {
        var configuration = new KafkaAdmin(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka-cold-start.invalid:9092",
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 3000));
        long started = System.nanoTime();
        try (var unavailable = new KafkaHealthIndicator(configuration)) {
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        }
    }

    @Test
    void unreachableBrokerReportsDownWithinABoundedDeadline() {
        var configuration = new KafkaAdmin(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1",
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 3000));
        try (var unavailable = new KafkaHealthIndicator(configuration)) {
            long started = System.nanoTime();
            assertThat(unavailable.health().getStatus()).isEqualTo(Status.DOWN);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
        }
    }
}
