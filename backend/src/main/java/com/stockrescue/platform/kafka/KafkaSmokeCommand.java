package com.stockrescue.platform.kafka;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = KafkaSmokeCommand.ENABLED_PROPERTY, havingValue = "true")
public class KafkaSmokeCommand implements ApplicationRunner {

    public static final String ENABLED_PROPERTY = "stockrescue.kafka.smoke";
    private static final Logger LOG = LoggerFactory.getLogger(KafkaSmokeCommand.class);
    private final KafkaSmoke smoke;

    public KafkaSmokeCommand(KafkaSmoke smoke) {
        this.smoke = smoke;
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        var receipt = smoke.run(Duration.ofSeconds(10));
        LOG.info("Kafka infrastructure smoke consumed its record; correlationId={}", receipt.correlationId());
    }
}
