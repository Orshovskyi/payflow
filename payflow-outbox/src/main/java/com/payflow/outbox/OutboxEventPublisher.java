package com.payflow.outbox;

import com.payflow.events.SagaMessage;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Transactional Outbox: the message is committed together with the state change, and {@link OutboxRelay} publishes
 * it afterwards. If the business transaction commits, the message is delivered at least once, even after a crash.
 */
public class OutboxEventPublisher implements EventPublisher {

    private final OutboxWriter outboxWriter;
    private final PublishedCounter counter;

    public OutboxEventPublisher(OutboxWriter outboxWriter, MeterRegistry meterRegistry) {
        this.outboxWriter = outboxWriter;
        this.counter = new PublishedCounter(meterRegistry, "outbox");
    }

    @Override
    public void publish(String topic, String key, SagaMessage message) {
        outboxWriter.append(message.eventId(), topic, key, message);
        counter.countAfterCommit(topic);
    }
}
