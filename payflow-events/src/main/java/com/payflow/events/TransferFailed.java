package com.payflow.events;

import java.time.Instant;
import java.util.UUID;

/**
 * @param reason machine-readable code, see {@link FailureReason}
 */
public record TransferFailed(UUID eventId, UUID transferId, FailureReason reason, Instant occurredAt)
        implements TransferResult {
}
