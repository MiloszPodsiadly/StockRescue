package com.stockrescue.platform.kafka;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.stockrescue.support.InfrastructureTestBase;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KafkaFoundationTest extends InfrastructureTestBase {

    @Autowired
    private KafkaSmoke smoke;

    @Test
    void realConsumerReceivesTheMatchingRecordAndBrokerDoesNotAutoCreateUnknownTopics() throws Exception {
        var receipt = smoke.run(Duration.ofSeconds(10));
        assertThat(receipt.correlationId()).matches("[a-f0-9-]{36}");
        assertThat(receipt.payload()).isEqualTo(KafkaSmoke.PAYLOAD);
        try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            var resource = new ConfigResource(ConfigResource.Type.BROKER,
                    admin.describeCluster().controller().get(3, TimeUnit.SECONDS).idString());
            var config = admin.describeConfigs(List.of(resource)).all().get(3, TimeUnit.SECONDS).get(resource);
            assertThat(config.get("auto.create.topics.enable").value()).isEqualTo("false");
        }
    }

    @Test
    void unreachableBrokerFailsTheSmokeWithinABoundedDeadline() {
        long started = System.nanoTime();
        assertThatThrownBy(() -> new KafkaSmoke("127.0.0.1:1").run(Duration.ofSeconds(5)))
                .isInstanceOf(Exception.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
    }
}
