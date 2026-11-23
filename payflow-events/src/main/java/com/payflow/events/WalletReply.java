package com.payflow.events;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Orchestration: replies of the Wallet Service to {@link WalletCommand}s, on one topic keyed by transfer id. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = FundsReserved.class, name = "FundsReserved"),
        @JsonSubTypes.Type(value = ReservationFailed.class, name = "ReservationFailed"),
        @JsonSubTypes.Type(value = FundsCaptured.class, name = "FundsCaptured"),
        @JsonSubTypes.Type(value = FundsReleased.class, name = "FundsReleased")
})
public sealed interface WalletReply extends SagaMessage
        permits FundsReserved, ReservationFailed, FundsCaptured, FundsReleased {
}
