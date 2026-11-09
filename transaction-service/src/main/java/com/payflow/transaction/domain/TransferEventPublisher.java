package com.payflow.transaction.domain;

import com.payflow.events.TransferRequested;

/**
 * The single point where the consistency strategy differs. Implementations are chosen by
 * {@code payflow.consistency.strategy}; the business logic in {@link TransferService} does not change.
 * Must be called inside the transaction that changes the transfer state.
 */
public interface TransferEventPublisher {

    void publishTransferRequested(TransferRequested event);
}
