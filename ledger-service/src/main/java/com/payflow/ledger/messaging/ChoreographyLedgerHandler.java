package com.payflow.ledger.messaging;

import com.payflow.chaos.CrashPoint;
import com.payflow.events.FundsReserved;
import com.payflow.events.Topics;
import com.payflow.ledger.domain.LedgerService;
import com.payflow.outbox.EventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Choreography: reacts to reserved funds and announces its decision as an event. */
@Component
@ConditionalOnProperty(name = "payflow.saga.coordination", havingValue = "choreography", matchIfMissing = true)
public class ChoreographyLedgerHandler {

    private final LedgerService ledger;
    private final EventPublisher publisher;
    private final CrashPoint crashPoint;

    public ChoreographyLedgerHandler(LedgerService ledger, EventPublisher publisher, CrashPoint crashPoint) {
        this.ledger = ledger;
        this.publisher = publisher;
        this.crashPoint = crashPoint;
    }

    @KafkaListener(topics = Topics.FUNDS_RESERVED,
            properties = "spring.json.value.default.type=com.payflow.events.FundsReserved")
    @Transactional
    public void onFundsReserved(FundsReserved event) {
        ledger.record(event.eventId(), event.transferId(), event.sourceWalletId(), event.targetWalletId(),
                event.amount(), event.currency()).ifPresent(decision -> {
            crashPoint.afterCommit(); // experiments only: no-op unless armed
            publisher.publish(Topics.LEDGER_DECISION, event.transferId().toString(),
                    DecisionMessages.toMessage(decision));
        });
    }
}
