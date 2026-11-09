package com.payflow.transaction.domain;

import com.payflow.outbox.OutboxWriter;
import com.payflow.transaction.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The defining property of the Transactional Outbox: the state change and its event are committed atomically.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "payflow.consistency.strategy=outbox")
@Import(TestcontainersConfiguration.class)
class OutboxAtomicityIT {

    @Autowired
    private TransferService transferService;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void committedTransferHasItsEventStoredAndThenPublished() {
        Transfer transfer = transferService.create(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "UAH");

        assertThat(outboxRows(transfer.getId())).isEqualTo(1);
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbcTemplate.queryForObject(
                "SELECT published_at IS NOT NULL FROM outbox_events WHERE payload ->> 'transferId' = ?",
                Boolean.class, transfer.getId().toString()));
    }

    @Test
    void rolledBackTransferLeavesNeitherStateNorEvent() {
        UUID transferId = transactionTemplate.execute(status -> {
            Transfer transfer = transferService.create(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "UAH");
            status.setRollbackOnly();
            return transfer.getId();
        });

        assertThat(transferRepository.findById(transferId)).isEmpty();
        assertThat(outboxRows(transferId)).isZero();
    }

    @Test
    void writingOutsideATransactionIsRejected() {
        assertThatThrownBy(() -> outboxWriter.append(UUID.randomUUID(), "topic", "key", "event"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private long outboxRows(UUID transferId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE payload ->> 'transferId' = ?", Long.class,
                transferId.toString());
    }
}
