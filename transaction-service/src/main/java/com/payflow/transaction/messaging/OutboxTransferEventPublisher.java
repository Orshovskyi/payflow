package com.payflow.transaction.messaging;

import com.payflow.events.Topics;
import com.payflow.events.TransferRequested;
import com.payflow.outbox.OutboxWriter;
import com.payflow.transaction.domain.TransferEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Transactional Outbox strategy: the event is stored in the same transaction as the PENDING transfer.
 * If that transaction commits, the relay is guaranteed to publish the event eventually, even after a crash.
 */
@Component
@ConditionalOnProperty(name = "payflow.consistency.strategy", havingValue = "outbox")
public class OutboxTransferEventPublisher implements TransferEventPublisher {

    private final OutboxWriter outboxWriter;

    public OutboxTransferEventPublisher(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    @Override
    public void publishTransferRequested(TransferRequested event) {
        outboxWriter.append(event.eventId(), Topics.TRANSFER_REQUESTED, event.sourceWalletId().toString(), event);
    }
}
