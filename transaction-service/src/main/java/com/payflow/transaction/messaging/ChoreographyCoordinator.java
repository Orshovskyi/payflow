package com.payflow.transaction.messaging;

import com.payflow.events.Topics;
import com.payflow.events.TransferRequested;
import com.payflow.outbox.EventPublisher;
import com.payflow.transaction.domain.SagaCoordinator;
import com.payflow.transaction.domain.Transfer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Choreography: the Transaction Service only announces the new transfer; the Wallet and Ledger services drive the
 * saga by reacting to each other's events, and the outcome comes back as a {@code TransferResult}.
 */
@Component
@ConditionalOnProperty(name = "payflow.saga.coordination", havingValue = "choreography", matchIfMissing = true)
public class ChoreographyCoordinator implements SagaCoordinator {

    private final EventPublisher publisher;

    public ChoreographyCoordinator(EventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void start(Transfer transfer) {
        publisher.publish(Topics.TRANSFER_REQUESTED, transfer.getSourceWalletId().toString(), new TransferRequested(
                UUID.randomUUID(), transfer.getId(), transfer.getSourceWalletId(), transfer.getTargetWalletId(),
                transfer.getAmount(), transfer.getCurrency(), Instant.now()));
    }
}
