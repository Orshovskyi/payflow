package com.payflow.events;

/** Machine-readable reason why a transfer was rejected. */
public enum FailureReason {
    INSUFFICIENT_FUNDS,
    WALLET_NOT_FOUND,
    CURRENCY_MISMATCH,
    /** Rejected by the Ledger Service; the reserved funds are returned to the source wallet (compensation). */
    LIMIT_EXCEEDED
}
