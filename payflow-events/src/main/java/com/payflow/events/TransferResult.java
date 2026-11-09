package com.payflow.events;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.UUID;

/**
 * Outcome of a transfer, published by the Wallet Service. Both subtypes share one topic so that the
 * outcome of a single transfer is always read in order; the JSON "type" field selects the subtype.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TransferCompleted.class, name = "TransferCompleted"),
        @JsonSubTypes.Type(value = TransferFailed.class, name = "TransferFailed")
})
public sealed interface TransferResult permits TransferCompleted, TransferFailed {

    UUID eventId();

    UUID transferId();

    Instant occurredAt();
}
