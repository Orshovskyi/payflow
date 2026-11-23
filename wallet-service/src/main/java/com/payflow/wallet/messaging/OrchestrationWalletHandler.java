package com.payflow.wallet.messaging;

import com.payflow.chaos.CrashPoint;
import com.payflow.events.CaptureFunds;
import com.payflow.events.FundsCaptured;
import com.payflow.events.FundsReleased;
import com.payflow.events.FundsReserved;
import com.payflow.events.ReleaseFunds;
import com.payflow.events.ReservationFailed;
import com.payflow.events.ReserveFunds;
import com.payflow.events.Topics;
import com.payflow.events.WalletCommand;
import com.payflow.events.WalletReply;
import com.payflow.outbox.EventPublisher;
import com.payflow.wallet.transfer.FundsService;
import com.payflow.wallet.transfer.FundsService.Reservation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Orchestration: the Wallet Service only executes commands and replies to the orchestrator. It knows nothing about
 * the Ledger Service or about what comes next in the saga.
 */
@Component
@ConditionalOnProperty(name = "payflow.saga.coordination", havingValue = "orchestration")
public class OrchestrationWalletHandler {

    private final FundsService funds;
    private final EventPublisher publisher;
    private final CrashPoint crashPoint;

    public OrchestrationWalletHandler(FundsService funds, EventPublisher publisher, CrashPoint crashPoint) {
        this.funds = funds;
        this.publisher = publisher;
        this.crashPoint = crashPoint;
    }

    @KafkaListener(topics = Topics.WALLET_COMMANDS,
            properties = "spring.json.value.default.type=com.payflow.events.WalletCommand")
    @Transactional
    public void onCommand(WalletCommand command) {
        UUID transferId = command.transferId();
        WalletReply reply = switch (command) {
            case ReserveFunds reserve -> switch (funds.reserve(reserve.eventId(), transferId,
                    reserve.sourceWalletId(), reserve.targetWalletId(), reserve.amount(), reserve.currency())) {
                case Reservation.Reserved reserved -> new FundsReserved(UUID.randomUUID(), transferId,
                        reserve.sourceWalletId(), reserve.targetWalletId(), reserve.amount(), reserve.currency(),
                        Instant.now());
                case Reservation.Rejected rejected ->
                        new ReservationFailed(UUID.randomUUID(), transferId, rejected.reason(), Instant.now());
                case Reservation.Duplicate duplicate -> null;
            };
            case CaptureFunds capture -> ChoreographyWalletHandler.replies(funds.capture(capture.eventId(), transferId))
                    ? new FundsCaptured(UUID.randomUUID(), transferId, Instant.now()) : null;
            case ReleaseFunds release ->
                    ChoreographyWalletHandler.replies(funds.release(release.eventId(), transferId, release.reason()))
                            ? new FundsReleased(UUID.randomUUID(), transferId, Instant.now()) : null;
        };
        if (reply != null) {
            crashPoint.afterCommit(); // experiments only: no-op unless armed
            publisher.publish(Topics.WALLET_REPLIES, transferId.toString(), reply);
        }
    }
}
