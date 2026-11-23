package com.payflow.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Orchestration command: debit the source wallet and hold the amount. */
public record ReserveFunds(
        UUID eventId,
        UUID transferId,
        UUID sourceWalletId,
        UUID targetWalletId,
        BigDecimal amount,
        String currency,
        Instant occurredAt) implements WalletCommand {
}
