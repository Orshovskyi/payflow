package com.payflow.transaction.domain;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Experiment metrics. Every meter is tagged with the active consistency strategy. */
@Component
public class TransferMetrics {

    private final MeterRegistry registry;
    private final String strategy;

    public TransferMetrics(MeterRegistry registry, @Value("${payflow.consistency.strategy}") String strategy) {
        this.registry = registry;
        this.strategy = strategy;
    }

    public void transferCreated() {
        registry.counter("payflow.transfers.requested", "strategy", strategy).increment();
    }

    /** End-to-end saga latency: from the transfer being accepted until its outcome is recorded. */
    public void transferFinished(String outcome, Instant createdAt) {
        Timer.builder("payflow.transfers.e2e")
                .description("Time from transfer creation to a terminal status")
                .tag("strategy", strategy)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                // Default upper bound is 30 s; transfers delayed by a crash take longer and would be clipped.
                .maximumExpectedValue(Duration.ofMinutes(2))
                .register(registry)
                .record(Duration.between(createdAt, Instant.now()));
    }

    public void duplicateResultIgnored() {
        registry.counter("payflow.transfers.duplicate.results", "strategy", strategy).increment();
    }
}
