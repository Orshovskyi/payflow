package com.payflow.ledger.messaging;

import com.payflow.events.LedgerApproved;
import com.payflow.events.LedgerDecision;
import com.payflow.events.LedgerRejected;
import com.payflow.ledger.domain.LedgerService.Decision;

import java.time.Instant;
import java.util.UUID;

final class DecisionMessages {

    private DecisionMessages() {
    }

    static LedgerDecision toMessage(Decision decision) {
        return decision.approved()
                ? new LedgerApproved(UUID.randomUUID(), decision.transferId(), Instant.now())
                : new LedgerRejected(UUID.randomUUID(), decision.transferId(), decision.reason(), Instant.now());
    }
}
