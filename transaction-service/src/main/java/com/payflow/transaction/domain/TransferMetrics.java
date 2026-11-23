package com.payflow.transaction.domain;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Experiment metrics. Every meter is tagged with the delivery strategy and the coordination style. */
@Component
public class TransferMetrics {

    /** Final outcome as seen by the experiment; a compensated transfer is a FAILED one that had reserved funds. */
    public enum Outcome { COMPLETED, REJECTED, COMPENSATED }

    private final MeterRegistry registry;
    private final String strategy;
    private final String coordination;

    public TransferMetrics(MeterRegistry registry, @Value("${payflow.consistency.strategy}") String strategy,
                           @Value("${payflow.saga.coordination}") String coordination) {
        this.registry = registry;
        this.strategy = strategy;
        this.coordination = coordination;
    }

    public void transferCreated() {
        registry.counter("payflow.transfers.requested", "strategy", strategy, "coordination", coordination)
                .increment();
    }

    /** End-to-end saga latency: from the transfer being accepted until its outcome is recorded. */
    public void transferFinished(Transfer transfer) {
        Timer.builder("payflow.transfers.e2e")
                .description("Time from transfer creation to a terminal status")
                .tag("strategy", strategy)
                .tag("coordination", coordination)
                .tag("outcome", outcome(transfer).name())
                .publishPercentileHistogram()
                // Default upper bound is 30 s; transfers delayed by a crash take longer and would be clipped.
                .maximumExpectedValue(Duration.ofMinutes(2))
                .register(registry)
                .record(Duration.between(transfer.getCreatedAt(), Instant.now()));
    }

    public void duplicateResultIgnored() {
        registry.counter("payflow.transfers.duplicate.results", "strategy", strategy, "coordination", coordination)
                .increment();
    }

    static Outcome outcome(Transfer transfer) {
        if (transfer.getStatus() == TransferStatus.COMPLETED) {
            return Outcome.COMPLETED;
        }
        return "LIMIT_EXCEEDED".equals(transfer.getFailureReason()) ? Outcome.COMPENSATED : Outcome.REJECTED;
    }
}
