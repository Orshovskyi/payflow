package com.payflow.transaction.api;

import com.payflow.outbox.EventPublisher;
import com.payflow.outbox.OutboxEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every scenario of {@link TransferFlowIT} with the Transactional Outbox strategy.
 */
@TestPropertySource(properties = "payflow.consistency.strategy=outbox")
class TransferFlowOutboxIT extends TransferFlowIT {

    @Autowired
    private EventPublisher publisher;

    @Test
    void outboxStrategyIsActive() {
        assertThat(publisher).isInstanceOf(OutboxEventPublisher.class);
    }
}
