package com.payflow.wallet.transfer;

import com.payflow.events.TransferCompleted;
import com.payflow.events.TransferFailed;
import com.payflow.events.TransferFailed.FailureReason;
import com.payflow.events.TransferRequested;
import com.payflow.events.TransferResult;
import com.payflow.wallet.domain.InsufficientFundsException;
import com.payflow.wallet.domain.Wallet;
import com.payflow.wallet.domain.WalletRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Saga step 2: executes a requested transfer. Debit, credit, the deduplication marker and the journal entry are
 * committed in one local transaction, so the transfer is applied either completely or not at all, and at most once.
 */
@Service
public class TransferProcessor {

    private static final Logger log = LoggerFactory.getLogger(TransferProcessor.class);

    private final WalletRepository walletRepository;
    private final ProcessedMessages processedMessages;
    private final AppliedTransfers appliedTransfers;
    private final TransferResultPublisher resultPublisher;
    private final MeterRegistry meterRegistry;
    private final String strategy;

    public TransferProcessor(WalletRepository walletRepository, ProcessedMessages processedMessages,
                             AppliedTransfers appliedTransfers, TransferResultPublisher resultPublisher,
                             MeterRegistry meterRegistry,
                             @Value("${payflow.consistency.strategy}") String strategy) {
        this.walletRepository = walletRepository;
        this.processedMessages = processedMessages;
        this.appliedTransfers = appliedTransfers;
        this.resultPublisher = resultPublisher;
        this.meterRegistry = meterRegistry;
        this.strategy = strategy;
    }

    /**
     * An optimistic lock conflict escapes as an exception, rolls back the whole transaction (including the
     * deduplication marker) and the Kafka error handler redelivers the event.
     */
    @Transactional
    public void process(TransferRequested request) {
        if (!processedMessages.markProcessed(request.eventId())) {
            meterRegistry.counter("payflow.wallet.duplicates.skipped", "strategy", strategy).increment();
            log.info("Skipping duplicate TransferRequested {} for transfer {}", request.eventId(), request.transferId());
            return;
        }
        TransferResult result = execute(request);
        appliedTransfers.record(request, result);
        meterRegistry.counter("payflow.wallet.transfers.processed",
                "strategy", strategy, "outcome", result.getClass().getSimpleName()).increment();
        resultPublisher.publish(result);
    }

    private TransferResult execute(TransferRequested request) {
        Optional<Wallet> source = walletRepository.findById(request.sourceWalletId());
        Optional<Wallet> target = walletRepository.findById(request.targetWalletId());
        if (source.isEmpty() || target.isEmpty()) {
            return failed(request, FailureReason.WALLET_NOT_FOUND);
        }
        if (!source.get().getCurrency().equals(request.currency())
                || !target.get().getCurrency().equals(request.currency())) {
            return failed(request, FailureReason.CURRENCY_MISMATCH);
        }
        try {
            source.get().debit(request.amount());
        } catch (InsufficientFundsException e) {
            return failed(request, FailureReason.INSUFFICIENT_FUNDS);
        }
        target.get().credit(request.amount());
        return new TransferCompleted(UUID.randomUUID(), request.transferId(), Instant.now());
    }

    private static TransferFailed failed(TransferRequested request, FailureReason reason) {
        return new TransferFailed(UUID.randomUUID(), request.transferId(), reason, Instant.now());
    }
}
