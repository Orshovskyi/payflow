package com.payflow.events;

import java.time.Instant;
import java.util.UUID;

/** Orchestration reply: the held amount was returned to the source wallet (the compensation step). */
public record FundsReleased(UUID eventId, UUID transferId, Instant occurredAt) implements WalletReply {
}
