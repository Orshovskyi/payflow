package com.payflow.events;

import java.time.Instant;
import java.util.UUID;

/** Orchestration command: credit the held amount to the target wallet. */
public record CaptureFunds(UUID eventId, UUID transferId, Instant occurredAt) implements WalletCommand {
}
