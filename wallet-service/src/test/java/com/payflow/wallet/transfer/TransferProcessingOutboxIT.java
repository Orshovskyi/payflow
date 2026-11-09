package com.payflow.wallet.transfer;

import com.payflow.wallet.messaging.OutboxTransferResultPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs every scenario of {@link TransferProcessingIT} with the Transactional Outbox strategy:
 * the business behaviour must be identical, only the delivery mechanism differs.
 */
@TestPropertySource(properties = "payflow.consistency.strategy=outbox")
class TransferProcessingOutboxIT extends TransferProcessingIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransferResultPublisher publisher;

    @Test
    void outboxStrategyIsActive() {
        assertThat(publisher).isInstanceOf(OutboxTransferResultPublisher.class);
    }

    @AfterEach
    void everyOutboxEventIsEventuallyPublished() {
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Long.class) == 0);
    }
}
