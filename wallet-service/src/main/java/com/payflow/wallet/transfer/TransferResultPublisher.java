package com.payflow.wallet.transfer;

import com.payflow.events.TransferResult;

/**
 * The single point where the consistency strategy differs (see {@code payflow.consistency.strategy}).
 * Must be called inside the transaction that changes the balances.
 */
public interface TransferResultPublisher {

    void publish(TransferResult result);
}
