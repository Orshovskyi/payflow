package com.payflow.wallet.messaging;

import com.payflow.events.Topics;
import com.payflow.events.TransferResult;
import com.payflow.outbox.OutboxWriter;
import com.payflow.wallet.transfer.TransferResultPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Transactional Outbox strategy: the result is stored in the same transaction as the balance changes and the
 * deduplication marker, so "money moved" and "result will be reported" can no longer diverge.
 */
@Component
@ConditionalOnProperty(name = "payflow.consistency.strategy", havingValue = "outbox")
public class OutboxTransferResultPublisher implements TransferResultPublisher {

    private final OutboxWriter outboxWriter;

    public OutboxTransferResultPublisher(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    @Override
    public void publish(TransferResult result) {
        outboxWriter.append(result.eventId(), Topics.TRANSFER_RESULT, result.transferId().toString(), result);
    }
}
