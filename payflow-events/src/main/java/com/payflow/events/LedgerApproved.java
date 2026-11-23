package com.payflow.events;

import java.time.Instant;
import java.util.UUID;

/** The Ledger Service posted the transfer (debit source, credit target). */
public record LedgerApproved(UUID eventId, UUID transferId, Instant occurredAt) implements LedgerDecision {
}
