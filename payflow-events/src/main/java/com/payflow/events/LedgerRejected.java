package com.payflow.events;

import java.time.Instant;
import java.util.UUID;

/** The Ledger Service refused the transfer; the reserved funds must be released. */
public record LedgerRejected(UUID eventId, UUID transferId, FailureReason reason, Instant occurredAt)
        implements LedgerDecision {
}
