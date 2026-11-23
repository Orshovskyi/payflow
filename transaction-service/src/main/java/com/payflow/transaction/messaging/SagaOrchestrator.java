package com.payflow.transaction.messaging;

import com.payflow.chaos.CrashPoint;
import com.payflow.events.CaptureFunds;
import com.payflow.events.FailureReason;
import com.payflow.events.FundsCaptured;
import com.payflow.events.FundsReleased;
import com.payflow.events.FundsReserved;
import com.payflow.events.LedgerApproved;
import com.payflow.events.LedgerDecision;
import com.payflow.events.LedgerRejected;
import com.payflow.events.RecordTransfer;
import com.payflow.events.ReleaseFunds;
import com.payflow.events.ReservationFailed;
import com.payflow.events.ReserveFunds;
import com.payflow.events.SagaMessage;
import com.payflow.events.Topics;
import com.payflow.events.WalletReply;
import com.payflow.outbox.EventPublisher;
import com.payflow.transaction.domain.SagaCoordinator;
import com.payflow.transaction.domain.SagaStep;
import com.payflow.transaction.domain.Transfer;
import com.payflow.transaction.domain.TransferMetrics;
import com.payflow.transaction.domain.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Orchestration: the Transaction Service owns the whole saga. It sends a command to one participant at a time and
 * decides the next step from the reply:
 * <pre>
 * RESERVING --FundsReserved-->     POSTING   --LedgerApproved--> CAPTURING --FundsCaptured--> COMPLETED
 *     \--ReservationFailed--> FAILED   \--LedgerRejected--> RELEASING --FundsReleased--> FAILED (compensated)
 * </pre>
 * Every reply is handled in one local transaction: the step change and the next command (through the outbox) are
 * committed together, so a crash of the orchestrator cannot lose the saga. A reply that does not match the current
 * step is a duplicate or arrives late, and is ignored.
 */
@Component
@ConditionalOnProperty(name = "payflow.saga.coordination", havingValue = "orchestration")
public class SagaOrchestrator implements SagaCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

    private final TransferRepository transfers;
    private final EventPublisher publisher;
    private final TransferMetrics metrics;
    private final CrashPoint crashPoint;

    public SagaOrchestrator(TransferRepository transfers, EventPublisher publisher, TransferMetrics metrics,
                            CrashPoint crashPoint) {
        this.transfers = transfers;
        this.publisher = publisher;
        this.metrics = metrics;
        this.crashPoint = crashPoint;
    }

    @Override
    public void start(Transfer transfer) {
        transfer.startOrchestration();
        // Keyed by source wallet, like TransferRequested: all debits of one wallet are processed in order.
        publisher.publish(Topics.WALLET_COMMANDS, transfer.getSourceWalletId().toString(), new ReserveFunds(
                UUID.randomUUID(), transfer.getId(), transfer.getSourceWalletId(), transfer.getTargetWalletId(),
                transfer.getAmount(), transfer.getCurrency(), Instant.now()));
    }

    @KafkaListener(topics = Topics.WALLET_REPLIES,
            properties = "spring.json.value.default.type=com.payflow.events.WalletReply")
    @Transactional
    public void onWalletReply(WalletReply reply) {
        Transfer transfer = find(reply);
        if (transfer == null) {
            return;
        }
        boolean handled = switch (reply) {
            case FundsReserved reserved -> {
                if (!transfer.advance(SagaStep.RESERVING, SagaStep.POSTING)) {
                    yield false;
                }
                send(Topics.LEDGER_COMMANDS, new RecordTransfer(UUID.randomUUID(), transfer.getId(),
                        transfer.getSourceWalletId(), transfer.getTargetWalletId(), transfer.getAmount(),
                        transfer.getCurrency(), Instant.now()));
                yield true;
            }
            case ReservationFailed failed -> transfer.advance(SagaStep.RESERVING, SagaStep.DONE)
                    && finish(transfer, transfer.fail(failed.reason().name()));
            case FundsCaptured captured -> transfer.advance(SagaStep.CAPTURING, SagaStep.DONE)
                    && finish(transfer, transfer.complete());
            case FundsReleased released -> transfer.advance(SagaStep.RELEASING, SagaStep.DONE)
                    && finish(transfer, transfer.fail(transfer.getFailureReason()));
        };
        ignoreIfNot(handled, reply, transfer);
    }

    @KafkaListener(topics = Topics.LEDGER_REPLIES,
            properties = "spring.json.value.default.type=com.payflow.events.LedgerDecision")
    @Transactional
    public void onLedgerDecision(LedgerDecision decision) {
        Transfer transfer = find(decision);
        if (transfer == null) {
            return;
        }
        boolean handled = switch (decision) {
            case LedgerApproved approved -> {
                if (!transfer.advance(SagaStep.POSTING, SagaStep.CAPTURING)) {
                    yield false;
                }
                send(Topics.WALLET_COMMANDS, new CaptureFunds(UUID.randomUUID(), transfer.getId(), Instant.now()));
                yield true;
            }
            case LedgerRejected rejected -> {
                if (!transfer.startCompensation(rejected.reason().name())) {
                    yield false;
                }
                send(Topics.WALLET_COMMANDS, new ReleaseFunds(UUID.randomUUID(), transfer.getId(),
                        rejected.reason(), Instant.now()));
                yield true;
            }
        };
        ignoreIfNot(handled, decision, transfer);
    }

    private void send(String topic, SagaMessage command) {
        crashPoint.afterCommit(); // experiments only: no-op unless armed
        publisher.publish(topic, command.transferId().toString(), command);
    }

    private boolean finish(Transfer transfer, boolean changed) {
        if (changed) {
            crashPoint.afterCommit();
            metrics.transferFinished(transfer);
        }
        return changed;
    }

    private Transfer find(SagaMessage message) {
        Transfer transfer = transfers.findById(message.transferId()).orElse(null);
        if (transfer == null) {
            log.error("{} for unknown transfer {}", message.getClass().getSimpleName(), message.transferId());
        }
        return transfer;
    }

    private void ignoreIfNot(boolean handled, SagaMessage message, Transfer transfer) {
        if (!handled) {
            metrics.duplicateResultIgnored();
            log.info("Ignored {} {} for transfer {} at step {}", message.getClass().getSimpleName(),
                    message.eventId(), transfer.getId(), transfer.getSagaStep());
        }
    }
}
