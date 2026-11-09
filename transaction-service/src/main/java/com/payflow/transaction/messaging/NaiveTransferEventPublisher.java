package com.payflow.transaction.messaging;

import com.payflow.events.Topics;
import com.payflow.events.TransferRequested;
import com.payflow.transaction.domain.TransferEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Baseline strategy: a naive dual write. The database commit and the Kafka send are two separate operations.
 * The send happens after the commit, so a crash (or a broker failure) in between loses the event while the
 * transfer stays PENDING forever. This is exactly the failure mode the experiment measures.
 */
@Component
@ConditionalOnProperty(name = "payflow.consistency.strategy", havingValue = "naive")
public class NaiveTransferEventPublisher implements TransferEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(NaiveTransferEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    public NaiveTransferEventPublisher(KafkaTemplate<String, Object> kafkaTemplate, MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void publishTransferRequested(TransferRequested event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("publishTransferRequested must be called inside a transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                send(event);
            }
        });
    }

    private void send(TransferRequested event) {
        kafkaTemplate.send(Topics.TRANSFER_REQUESTED, event.sourceWalletId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        meterRegistry.counter("payflow.events.publish.failed", "strategy", "naive").increment();
                        log.error("Lost TransferRequested {} for transfer {}", event.eventId(), event.transferId(), ex);
                    }
                });
    }
}
