package com.payflow.events;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Decision of the Ledger Service. Choreography: an event the Wallet Service reacts to.
 * Orchestration: the reply to {@link RecordTransfer}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = LedgerApproved.class, name = "LedgerApproved"),
        @JsonSubTypes.Type(value = LedgerRejected.class, name = "LedgerRejected")
})
public sealed interface LedgerDecision extends SagaMessage permits LedgerApproved, LedgerRejected {
}
