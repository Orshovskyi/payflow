package com.payflow.wallet.transfer;

import com.payflow.events.FailureReason;
import com.payflow.wallet.domain.InsufficientFundsException;
import com.payflow.wallet.domain.Wallet;
import com.payflow.wallet.domain.WalletRepository;
import com.payflow.wallet.transfer.TransferHolds.Hold;
import com.payflow.wallet.transfer.TransferHolds.Status;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * The Wallet Service's three saga steps. They are the same for choreography and orchestration; only the messaging
 * adapters that call them differ. Each runs inside the caller's transaction together with the deduplication marker
 * and the outgoing message, so a step is applied completely or not at all, and at most once.
 * <p>
 * An optimistic lock conflict on a wallet escapes as an exception, rolls back the whole transaction (including the
 * deduplication marker), and the Kafka error handler redelivers the message.
 */
@Service
public class FundsService {

    private static final Logger log = LoggerFactory.getLogger(FundsService.class);

    /** Result of {@link #reserve}. */
    public sealed interface Reservation {
        record Reserved() implements Reservation {
        }

        record Rejected(FailureReason reason) implements Reservation {
        }

        /** The message was already processed; its reply was sent back then. */
        record Duplicate() implements Reservation {
        }
    }

    /** Result of {@link #capture} and {@link #release}. */
    public enum StepOutcome {
        /** The step changed the balances now. */
        APPLIED,
        /** The hold was already in the target state (e.g. a command re-sent with a new id); reply again. */
        ALREADY_DONE,
        /** The message was already processed; its reply was sent back then. */
        DUPLICATE,
        /** The hold is in a state that contradicts the request; nothing is changed and nothing is replied. */
        CONFLICT
    }

    private final WalletRepository walletRepository;
    private final TransferHolds holds;
    private final ProcessedMessages processedMessages;
    private final MeterRegistry meterRegistry;
    private final String strategy;

    public FundsService(WalletRepository walletRepository, TransferHolds holds, ProcessedMessages processedMessages,
                        MeterRegistry meterRegistry, @Value("${payflow.consistency.strategy}") String strategy) {
        this.walletRepository = walletRepository;
        this.holds = holds;
        this.processedMessages = processedMessages;
        this.meterRegistry = meterRegistry;
        this.strategy = strategy;
    }

    /** Step 2 of the saga: debit the source wallet and hold the amount. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Reservation reserve(UUID messageId, UUID transferId, UUID sourceWalletId, UUID targetWalletId,
                               BigDecimal amount, String currency) {
        if (!firstDelivery(messageId)) {
            return new Reservation.Duplicate();
        }
        Optional<FailureReason> rejection = check(sourceWalletId, targetWalletId, amount, currency);
        if (rejection.isPresent()) {
            holds.insert(transferId, sourceWalletId, targetWalletId, amount, Status.REJECTED, rejection.get().name());
            count("reserve", "rejected");
            return new Reservation.Rejected(rejection.get());
        }
        holds.insert(transferId, sourceWalletId, targetWalletId, amount, Status.HELD, null);
        count("reserve", "held");
        return new Reservation.Reserved();
    }

    /** Step 4a: the Ledger Service approved, credit the target wallet. */
    @Transactional(propagation = Propagation.MANDATORY)
    public StepOutcome capture(UUID messageId, UUID transferId) {
        return finish(messageId, transferId, Status.CAPTURED, null);
    }

    /** Step 4b (compensation): the Ledger Service rejected, return the money to the source wallet. */
    @Transactional(propagation = Propagation.MANDATORY)
    public StepOutcome release(UUID messageId, UUID transferId, FailureReason reason) {
        return finish(messageId, transferId, Status.RELEASED, reason.name());
    }

    private StepOutcome finish(UUID messageId, UUID transferId, Status target, String reason) {
        String action = target == Status.CAPTURED ? "capture" : "release";
        if (!firstDelivery(messageId)) {
            return StepOutcome.DUPLICATE;
        }
        Hold hold = holds.lock(transferId).orElse(null);
        if (hold == null) {
            log.error("Cannot {} transfer {}: no hold", action, transferId);
            count(action, "conflict");
            return StepOutcome.CONFLICT;
        }
        if (hold.status() == target) {
            count(action, "already_done");
            return StepOutcome.ALREADY_DONE;
        }
        if (hold.status() != Status.HELD) {
            log.error("Cannot {} transfer {}: hold is {}", action, transferId, hold.status());
            count(action, "conflict");
            return StepOutcome.CONFLICT;
        }
        UUID walletId = target == Status.CAPTURED ? hold.targetWalletId() : hold.sourceWalletId();
        wallet(walletId).credit(hold.amount());
        holds.updateStatus(transferId, target, reason);
        count(action, "applied");
        return StepOutcome.APPLIED;
    }

    private Optional<FailureReason> check(UUID sourceWalletId, UUID targetWalletId, BigDecimal amount,
                                          String currency) {
        Optional<Wallet> source = walletRepository.findById(sourceWalletId);
        Optional<Wallet> target = walletRepository.findById(targetWalletId);
        if (source.isEmpty() || target.isEmpty()) {
            return Optional.of(FailureReason.WALLET_NOT_FOUND);
        }
        if (!source.get().getCurrency().equals(currency) || !target.get().getCurrency().equals(currency)) {
            return Optional.of(FailureReason.CURRENCY_MISMATCH);
        }
        try {
            source.get().debit(amount);
        } catch (InsufficientFundsException e) {
            return Optional.of(FailureReason.INSUFFICIENT_FUNDS);
        }
        return Optional.empty();
    }

    private Wallet wallet(UUID walletId) {
        return walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalStateException("Wallet " + walletId + " of a held transfer is gone"));
    }

    private boolean firstDelivery(UUID messageId) {
        if (processedMessages.markProcessed(messageId)) {
            return true;
        }
        meterRegistry.counter("payflow.wallet.duplicates.skipped", "strategy", strategy).increment();
        log.info("Skipping duplicate message {}", messageId);
        return false;
    }

    private void count(String action, String outcome) {
        meterRegistry.counter("payflow.wallet.saga.steps", "strategy", strategy, "action", action,
                "outcome", outcome).increment();
    }
}
