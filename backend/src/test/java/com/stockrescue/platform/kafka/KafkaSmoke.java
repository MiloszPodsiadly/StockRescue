package com.stockrescue.platform.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CloseOptions;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

final class KafkaSmoke {

    public static final String CORRELATION_HEADER = "correlation-id";
    public static final String PAYLOAD = "stockrescue-infrastructure-check";
    private final String bootstrapServers;
    private final String topic;

    KafkaSmoke(String bootstrapServers, String topic) {
        this.bootstrapServers = bootstrapServers;
        this.topic = topic;
    }

    public Receipt run(Duration timeout) throws Exception {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(15)) > 0) {
            throw new IllegalArgumentException("Smoke timeout must be positive and at most 15 seconds");
        }
        var correlationId = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + timeout.toNanos();
        var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000,
                ProducerConfig.LINGER_MS_CONFIG, 0));
        var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false,
                ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000));
        try {
            var partition = new TopicPartition(topic, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            var message = new ProducerRecord<>(topic, correlationId, PAYLOAD);
            message.headers().add(CORRELATION_HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
            producer.send(message).get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            while (System.nanoTime() < deadline) {
                var records = consumer.poll(Duration.ofMillis(Math.min(250,
                        Math.max(1, Duration.ofNanos(deadline - System.nanoTime()).toMillis()))));
                for (var record : records) {
                    var header = record.headers().lastHeader(CORRELATION_HEADER);
                    if (correlationId.equals(record.key()) && PAYLOAD.equals(record.value())
                            && header != null && correlationId.equals(new String(header.value(), StandardCharsets.UTF_8))) {
                        return new Receipt(correlationId, record.value());
                    }
                }
            }
            throw new TimeoutException("Kafka infrastructure smoke did not receive its record before the deadline");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } finally {
            try {
                consumer.close(CloseOptions.timeout(Duration.ofMillis(100)));
            } finally {
                producer.close(Duration.ofMillis(100));
            }
        }
    }

    public record Receipt(String correlationId, String payload) { }
}
