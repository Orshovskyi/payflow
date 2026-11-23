package com.payflow.transaction.domain;

/**
 * Orchestration only: which reply the orchestrator is waiting for. A reply that does not match the current step
 * (a duplicate or a late one) is ignored, which makes the orchestrator idempotent.
 */
public enum SagaStep {
    /** Waiting for the Wallet Service to reserve the funds. */
    RESERVING,
    /** Waiting for the Ledger Service decision. */
    POSTING,
    /** Waiting for the Wallet Service to credit the target. */
    CAPTURING,
    /** Compensation: waiting for the Wallet Service to return the funds to the source. */
    RELEASING,
    DONE
}
