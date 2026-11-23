package com.payflow.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Counts messages per topic once their transaction has committed ({@code payflow.events.published}).
 * Divided by the number of finished transfers it gives "messages per transfer", which differs between
 * choreography and orchestration.
 */
final class PublishedCounter {

    private final MeterRegistry meterRegistry;
    private final String strategy;

    PublishedCounter(MeterRegistry meterRegistry, String strategy) {
        this.meterRegistry = meterRegistry;
        this.strategy = strategy;
    }

    void countAfterCommit(String topic) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                meterRegistry.counter("payflow.events.published", "strategy", strategy, "topic", topic).increment();
            }
        });
    }
}
