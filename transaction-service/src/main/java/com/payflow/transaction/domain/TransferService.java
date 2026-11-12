package com.payflow.transaction.domain;

import com.payflow.chaos.CrashPoint;
import com.payflow.events.TransferCompleted;
import com.payflow.events.TransferFailed;
import com.payflow.events.TransferRequested;
import com.payflow.events.TransferResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferRepository transferRepository;
    private final TransferEventPublisher eventPublisher;
    private final TransferMetrics metrics;
    private final CrashPoint crashPoint;

    public TransferService(TransferRepository transferRepository, TransferEventPublisher eventPublisher,
                           TransferMetrics metrics, CrashPoint crashPoint) {
        this.transferRepository = transferRepository;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
        this.crashPoint = crashPoint;
    }

    /** Saga step 1: record the transfer as PENDING and ask the Wallet Service to execute it. */
    @Transactional
    public Transfer create(UUID sourceWalletId, UUID targetWalletId, BigDecimal amount, String currency) {
        Transfer transfer = transferRepository.save(new Transfer(sourceWalletId, targetWalletId, amount, currency));
        crashPoint.afterCommit(); // experiments only: no-op unless armed
        eventPublisher.publishTransferRequested(new TransferRequested(
                UUID.randomUUID(), transfer.getId(), sourceWalletId, targetWalletId, amount, currency, Instant.now()));
        metrics.transferCreated();
        return transfer;
    }

    @Transactional(readOnly = true)
    public Transfer get(UUID transferId) {
        return transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));
    }

    /** Saga step 3: record the outcome reported by the Wallet Service. Safe to call more than once. */
    @Transactional
    public void handleResult(TransferResult result) {
        Transfer transfer = transferRepository.findById(result.transferId()).orElse(null);
        if (transfer == null) {
            // Cannot happen while both services share the same history; logged so experiments can spot it.
            log.error("Result for unknown transfer {}", result.transferId());
            return;
        }
        boolean changed = switch (result) {
            case TransferCompleted completed -> transfer.complete();
            case TransferFailed failed -> transfer.fail(failed.reason().name());
        };
        if (changed) {
            metrics.transferFinished(transfer.getStatus().name(), transfer.getCreatedAt());
        } else {
            metrics.duplicateResultIgnored();
            log.info("Ignored repeated result {} for transfer {} in status {}",
                    result.eventId(), transfer.getId(), transfer.getStatus());
        }
    }
}
