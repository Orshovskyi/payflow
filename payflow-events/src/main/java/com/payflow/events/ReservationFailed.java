package com.payflow.events;

import java.time.Instant;
import java.util.UUID;

/** Orchestration reply: the funds could not be reserved, nothing was debited. */
public record ReservationFailed(UUID eventId, UUID transferId, FailureReason reason, Instant occurredAt)
        implements WalletReply {
}
