package com.payflow.events;

import java.time.Instant;
import java.util.UUID;

/** Orchestration command (compensation): return the held amount to the source wallet. */
public record ReleaseFunds(UUID eventId, UUID transferId, FailureReason reason, Instant occurredAt)
        implements WalletCommand {
}
