package com.payflow.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Orchestration command: check the transfer against the ledger rules and post it. */
public record RecordTransfer(
        UUID eventId,
        UUID transferId,
        UUID sourceWalletId,
        UUID targetWalletId,
        BigDecimal amount,
        String currency,
        Instant occurredAt) implements SagaMessage {
}
