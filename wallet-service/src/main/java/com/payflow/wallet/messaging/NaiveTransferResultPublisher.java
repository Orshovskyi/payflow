package com.payflow.wallet.messaging;

import com.payflow.events.Topics;
import com.payflow.events.TransferResult;
import com.payflow.wallet.transfer.TransferResultPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Baseline strategy: a naive dual write. Balances are committed first and the result is sent afterwards.
 * A crash in between leaves the money moved while the Transaction Service still sees the transfer as PENDING,
 * and the redelivered request is skipped as a duplicate, so the result is never sent.
 */
@Component
@ConditionalOnProperty(name = "payflow.consistency.strategy", havingValue = "naive")
public class NaiveTransferResultPublisher implements TransferResultPublisher {

    private static final Logger log = LoggerFactory.getLogger(NaiveTransferResultPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    public NaiveTransferResultPublisher(KafkaTemplate<String, Object> kafkaTemplate, MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void publish(TransferResult result) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("publish must be called inside a transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                send(result);
            }
        });
    }

    private void send(TransferResult result) {
        kafkaTemplate.send(Topics.TRANSFER_RESULT, result.transferId().toString(), result)
                .whenComplete((sendResult, ex) -> {
                    if (ex != null) {
                        meterRegistry.counter("payflow.events.publish.failed", "strategy", "naive").increment();
                        log.error("Lost {} for transfer {}", result.getClass().getSimpleName(), result.transferId(), ex);
                    }
                });
    }
}
