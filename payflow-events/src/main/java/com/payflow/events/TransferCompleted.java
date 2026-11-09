package com.payflow.events;

import java.time.Instant;
import java.util.UUID;

public record TransferCompleted(UUID eventId, UUID transferId, Instant occurredAt) implements TransferResult {
}
