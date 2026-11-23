package com.payflow.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Choreography: a new transfer was created; the Wallet Service reacts by reserving the funds.
 *
 * @param eventId unique per event; consumers use it for deduplication (at-least-once delivery)
 */
public record TransferRequested(
        UUID eventId,
        UUID transferId,
        UUID sourceWalletId,
        UUID targetWalletId,
        BigDecimal amount,
        String currency,
        Instant occurredAt) implements SagaMessage {
}
