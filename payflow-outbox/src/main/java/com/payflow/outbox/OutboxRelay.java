package com.payflow.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Polling relay: reads unpublished events, sends them to Kafka, waits for the broker acknowledgements and only then
 * marks them as published, all within one database transaction.
 * <p>
 * Delivery is at-least-once: a crash after the send but before the commit publishes the batch again on the next
 * run, so consumers must be idempotent. {@code FOR UPDATE SKIP LOCKED} lets several service instances run a relay
 * without publishing the same row concurrently.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;
    private final Timer publishLag;
    private final Counter published;
    private final Counter failures;

    public OutboxRelay(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
                       ProducerFactory<?, ?> producerFactory, OutboxProperties properties, MeterRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.kafkaTemplate = new KafkaTemplate<>(rawJsonProducerFactory(producerFactory));
        this.properties = properties;
        this.publishLag = Timer.builder("payflow.outbox.lag")
                .description("Time from writing an event to the outbox until the broker acknowledged it")
                .publishPercentileHistogram()
                .register(registry);
        this.published = registry.counter("payflow.outbox.published");
        this.failures = registry.counter("payflow.outbox.relay.failures");
        Gauge.builder("payflow.outbox.pending", this, OutboxRelay::pendingCount)
                .description("Events written but not yet published")
                .register(registry);
    }

    /**
     * Payloads are already JSON, so they are sent as plain strings. The factory is derived from the auto-configured
     * one so that it keeps the broker address and producer settings (acks, idempotence). It is deliberately not a
     * bean: a second KafkaTemplate bean would switch off Spring Boot's auto-configured one.
     */
    @SuppressWarnings("unchecked")
    private static ProducerFactory<String, String> rawJsonProducerFactory(ProducerFactory<?, ?> producerFactory) {
        var base = (DefaultKafkaProducerFactory<String, String>) producerFactory;
        return base.copyWithConfigurationOverride(Map.of(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    }

    @Scheduled(fixedDelayString = "${payflow.outbox.poll-interval:50ms}")
    public void publishPending() {
        try {
            int sent;
            do {
                sent = publishBatch();
            } while (sent == properties.batchSize());
        } catch (RuntimeException e) {
            failures.increment();
            log.warn("Outbox relay run failed, the batch stays unpublished and will be retried: {}", e.toString());
        }
    }

    int publishBatch() {
        Integer sent = transactionTemplate.execute(status -> {
            List<OutboxRow> rows = jdbcTemplate.query("""
                            SELECT id, topic, message_key, payload, created_at
                            FROM outbox_events
                            WHERE published_at IS NULL
                            ORDER BY seq
                            LIMIT ?
                            FOR UPDATE SKIP LOCKED
                            """,
                    (rs, i) -> new OutboxRow(rs.getObject("id", UUID.class), rs.getString("topic"),
                            rs.getString("message_key"), rs.getString("payload"),
                            rs.getTimestamp("created_at").toInstant()),
                    properties.batchSize());
            if (rows.isEmpty()) {
                return 0;
            }
            CompletableFuture<?>[] acks = rows.stream()
                    .map(row -> kafkaTemplate.send(row.topic(), row.key(), row.payload()))
                    .toArray(CompletableFuture[]::new);
            waitForAcks(acks);

            jdbcTemplate.batchUpdate("UPDATE outbox_events SET published_at = now() WHERE id = ?",
                    rows.stream().map(row -> new Object[]{row.id()}).toList());
            Instant now = Instant.now();
            rows.forEach(row -> publishLag.record(Duration.between(row.createdAt(), now)));
            published.increment(rows.size());
            return rows.size();
        });
        return sent == null ? 0 : sent;
    }

    private void waitForAcks(CompletableFuture<?>[] acks) {
        try {
            CompletableFuture.allOf(acks).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Kafka acknowledgements", e);
        } catch (Exception e) {
            throw new IllegalStateException("Kafka did not acknowledge the outbox batch", e);
        }
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void deletePublished() {
        int deleted = jdbcTemplate.update(
                "DELETE FROM outbox_events WHERE published_at < now() - make_interval(secs => ?)",
                properties.retention().toSeconds());
        if (deleted > 0) {
            log.debug("Deleted {} published outbox events", deleted);
        }
    }

    private double pendingCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Long.class);
        return count == null ? 0 : count;
    }

    private record OutboxRow(UUID id, String topic, String key, String payload, Instant createdAt) {
    }
}
