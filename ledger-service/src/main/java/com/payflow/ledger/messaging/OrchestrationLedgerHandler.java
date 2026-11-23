package com.payflow.ledger.messaging;

import com.payflow.chaos.CrashPoint;
import com.payflow.events.RecordTransfer;
import com.payflow.events.Topics;
import com.payflow.ledger.domain.LedgerService;
import com.payflow.outbox.EventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Orchestration: executes the orchestrator's command and replies with the decision. */
@Component
@ConditionalOnProperty(name = "payflow.saga.coordination", havingValue = "orchestration")
public class OrchestrationLedgerHandler {

    private final LedgerService ledger;
    private final EventPublisher publisher;
    private final CrashPoint crashPoint;

    public OrchestrationLedgerHandler(LedgerService ledger, EventPublisher publisher, CrashPoint crashPoint) {
        this.ledger = ledger;
        this.publisher = publisher;
        this.crashPoint = crashPoint;
    }

    @KafkaListener(topics = Topics.LEDGER_COMMANDS,
            properties = "spring.json.value.default.type=com.payflow.events.RecordTransfer")
    @Transactional
    public void onRecordTransfer(RecordTransfer command) {
        ledger.record(command.eventId(), command.transferId(), command.sourceWalletId(), command.targetWalletId(),
                command.amount(), command.currency()).ifPresent(decision -> {
            crashPoint.afterCommit(); // experiments only: no-op unless armed
            publisher.publish(Topics.LEDGER_REPLIES, command.transferId().toString(),
                    DecisionMessages.toMessage(decision));
        });
    }
}
