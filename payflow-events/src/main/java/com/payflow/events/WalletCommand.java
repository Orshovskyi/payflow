package com.payflow.events;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Orchestration: commands from the orchestrator to the Wallet Service. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ReserveFunds.class, name = "ReserveFunds"),
        @JsonSubTypes.Type(value = CaptureFunds.class, name = "CaptureFunds"),
        @JsonSubTypes.Type(value = ReleaseFunds.class, name = "ReleaseFunds")
})
public sealed interface WalletCommand extends SagaMessage permits ReserveFunds, CaptureFunds, ReleaseFunds {
}
