package com.payflow.events;

import java.time.Instant;
import java.util.UUID;

/** Orchestration reply: the held amount was credited to the target wallet. */
public record FundsCaptured(UUID eventId, UUID transferId, Instant occurredAt) implements WalletReply {
}
