package com.payflow.outbox;

import com.payflow.events.SagaMessage;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Baseline strategy: a naive dual write. The database commit and the Kafka send are two separate operations.
 * The send happens after the commit and does not wait for the broker, so a crash in between loses the message
 * while the state is already committed. This is the failure mode the experiment measures.
 */
public class NaiveEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(NaiveEventPublisher.class);

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final MeterRegistry meterRegistry;
    private final PublishedCounter counter;

    public NaiveEventPublisher(KafkaTemplate<Object, Object> kafkaTemplate, MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
        this.counter = new PublishedCounter(meterRegistry, "naive");
    }

    @Override
    public void publish(String topic, String key, SagaMessage message) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("publish must be called inside a transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                send(topic, key, message);
            }
        });
        counter.countAfterCommit(topic);
    }

    private void send(String topic, String key, SagaMessage message) {
        kafkaTemplate.send(topic, key, message).whenComplete((result, ex) -> {
            if (ex != null) {
                meterRegistry.counter("payflow.events.publish.failed", "strategy", "naive").increment();
                log.error("Lost {} {} of transfer {}", message.getClass().getSimpleName(), message.eventId(),
                        message.transferId(), ex);
            }
        });
    }
}
