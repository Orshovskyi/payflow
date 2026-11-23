package com.payflow.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The Wallet Service debited the source wallet and holds the amount until the Ledger Service decides.
 * Choreography: an event the Ledger Service reacts to. Orchestration: the reply to {@link ReserveFunds}.
 * It carries the transfer details so that the Ledger Service needs no other source of truth.
 */
public record FundsReserved(
        UUID eventId,
        UUID transferId,
        UUID sourceWalletId,
        UUID targetWalletId,
        BigDecimal amount,
        String currency,
        Instant occurredAt) implements WalletReply {
}
