package com.payflow.wallet.messaging;

import com.payflow.chaos.CrashPoint;
import com.payflow.events.FundsReserved;
import com.payflow.events.LedgerApproved;
import com.payflow.events.LedgerDecision;
import com.payflow.events.LedgerRejected;
import com.payflow.events.Topics;
import com.payflow.events.TransferCompleted;
import com.payflow.events.TransferFailed;
import com.payflow.events.TransferRequested;
import com.payflow.outbox.EventPublisher;
import com.payflow.wallet.transfer.FundsService;
import com.payflow.wallet.transfer.FundsService.Reservation;
import com.payflow.wallet.transfer.FundsService.StepOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Choreography: the Wallet Service reacts to events and decides itself what to publish next.
 * It has to know the Ledger Service's decision events, which is the coupling orchestration avoids.
 */
@Component
@ConditionalOnProperty(name = "payflow.saga.coordination", havingValue = "choreography", matchIfMissing = true)
public class ChoreographyWalletHandler {

    private final FundsService funds;
    private final EventPublisher publisher;
    private final CrashPoint crashPoint;

    public ChoreographyWalletHandler(FundsService funds, EventPublisher publisher, CrashPoint crashPoint) {
        this.funds = funds;
        this.publisher = publisher;
        this.crashPoint = crashPoint;
    }

    @KafkaListener(topics = Topics.TRANSFER_REQUESTED,
            properties = "spring.json.value.default.type=com.payflow.events.TransferRequested")
    @Transactional
    public void onTransferRequested(TransferRequested request) {
        Reservation reservation = funds.reserve(request.eventId(), request.transferId(), request.sourceWalletId(),
                request.targetWalletId(), request.amount(), request.currency());
        switch (reservation) {
            case Reservation.Reserved reserved -> {
                crashPoint.afterCommit(); // experiments only: no-op unless armed
                publisher.publish(Topics.FUNDS_RESERVED, key(request.transferId()), new FundsReserved(
                        UUID.randomUUID(), request.transferId(), request.sourceWalletId(), request.targetWalletId(),
                        request.amount(), request.currency(), Instant.now()));
            }
            case Reservation.Rejected rejected -> {
                crashPoint.afterCommit();
                publisher.publish(Topics.TRANSFER_RESULT, key(request.transferId()), new TransferFailed(
                        UUID.randomUUID(), request.transferId(), rejected.reason(), Instant.now()));
            }
            case Reservation.Duplicate duplicate -> {
            }
        }
    }

    @KafkaListener(topics = Topics.LEDGER_DECISION,
            properties = "spring.json.value.default.type=com.payflow.events.LedgerDecision")
    @Transactional
    public void onLedgerDecision(LedgerDecision decision) {
        switch (decision) {
            case LedgerApproved approved -> {
                if (replies(funds.capture(approved.eventId(), approved.transferId()))) {
                    crashPoint.afterCommit();
                    publisher.publish(Topics.TRANSFER_RESULT, key(approved.transferId()),
                            new TransferCompleted(UUID.randomUUID(), approved.transferId(), Instant.now()));
                }
            }
            case LedgerRejected rejected -> {
                if (replies(funds.release(rejected.eventId(), rejected.transferId(), rejected.reason()))) {
                    crashPoint.afterCommit();
                    publisher.publish(Topics.TRANSFER_RESULT, key(rejected.transferId()), new TransferFailed(
                            UUID.randomUUID(), rejected.transferId(), rejected.reason(), Instant.now()));
                }
            }
        }
    }

    static boolean replies(StepOutcome outcome) {
        return outcome == StepOutcome.APPLIED || outcome == StepOutcome.ALREADY_DONE;
    }

    private static String key(UUID transferId) {
        return transferId.toString();
    }
}
