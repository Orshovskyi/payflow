package com.payflow.outbox;

import com.payflow.events.SagaMessage;

/**
 * Publishes a saga message as part of the caller's business transaction. The delivery strategy is the single point
 * where the experiment's first axis differs ({@code payflow.consistency.strategy}): the business code is the same.
 * <ul>
 *     <li>{@code naive}: the message is sent to Kafka after the commit (dual write, can be lost);</li>
 *     <li>{@code outbox}: the message is stored in the outbox table in the same transaction (cannot be lost).</li>
 * </ul>
 * Must be called inside a transaction.
 */
public interface EventPublisher {

    void publish(String topic, String key, SagaMessage message);
}
