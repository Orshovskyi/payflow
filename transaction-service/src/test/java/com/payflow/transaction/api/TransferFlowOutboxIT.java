package com.payflow.transaction.api;

import com.payflow.transaction.domain.TransferEventPublisher;
import com.payflow.transaction.messaging.OutboxTransferEventPublisher;
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
    private TransferEventPublisher publisher;

    @Test
    void outboxStrategyIsActive() {
        assertThat(publisher).isInstanceOf(OutboxTransferEventPublisher.class);
    }
}
