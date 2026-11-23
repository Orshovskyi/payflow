package com.payflow.transaction.domain;

/**
 * Starts the saga for a newly created transfer. The two implementations are the second axis of the experiment
 * ({@code payflow.saga.coordination}): choreography publishes an event, orchestration sends the first command.
 * Called inside the transaction that creates the transfer.
 */
public interface SagaCoordinator {

    void start(Transfer transfer);
}
