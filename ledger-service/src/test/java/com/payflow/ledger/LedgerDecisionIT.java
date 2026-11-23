package com.payflow.ledger;

import com.payflow.events.FailureReason;
import com.payflow.events.FundsReserved;
import com.payflow.events.LedgerApproved;
import com.payflow.events.LedgerDecision;
import com.payflow.events.LedgerRejected;
import com.payflow.events.Topics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Choreography: the Ledger Service reacts to reserved funds; the test plays the Wallet Service. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "payflow.ledger.max-transfer-amount=9")
@Import({TestcontainersConfiguration.class, LedgerDecisionIT.DecisionCollector.class})
class LedgerDecisionIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private DecisionCollector decisions;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void transferWithinTheLimitIsApprovedAndPostedInDoubleEntry() {
        FundsReserved reserved = reserved("9.00");

        send(reserved);

        assertThat(decisions.await(reserved.transferId())).isInstanceOf(LedgerApproved.class);
        List<Map<String, Object>> entries = jdbcTemplate.queryForList(
                "SELECT wallet_id, direction, amount FROM ledger_entries WHERE transfer_id = ? ORDER BY id",
                reserved.transferId());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).containsEntry("wallet_id", reserved.sourceWalletId()).containsEntry("direction", "DEBIT");
        assertThat(entries.get(1)).containsEntry("wallet_id", reserved.targetWalletId()).containsEntry("direction", "CREDIT");
        assertThat(rest.getForObject("/api/v1/ledger/transfers/{id}", Map.class, reserved.transferId()))
                .containsEntry("decision", "APPROVED");
    }

    @Test
    void transferAboveTheLimitIsRejectedWithoutPosting() {
        FundsReserved reserved = reserved("9.01");

        send(reserved);

        assertThat(decisions.await(reserved.transferId())).isInstanceOfSatisfying(LedgerRejected.class,
                rejected -> assertThat(rejected.reason()).isEqualTo(FailureReason.LIMIT_EXCEEDED));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entries WHERE transfer_id = ?",
                Long.class, reserved.transferId())).isZero();
    }

    @Test
    void redeliveredEventIsDecidedOnce() {
        FundsReserved reserved = reserved("1.00");

        send(reserved);
        send(reserved);

        decisions.await(reserved.transferId());
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .until(() -> decisions.forTransfer(reserved.transferId()).size() == 1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entries WHERE transfer_id = ?",
                Long.class, reserved.transferId())).isEqualTo(2);
    }

    @Test
    void ledgerIsAlwaysBalanced() {
        for (int i = 1; i <= 12; i++) {
            send(reserved(i + ".00"));
        }
        await().atMost(TIMEOUT).until(() -> decisions.size() >= 12);

        BigDecimal balance = jdbcTemplate.queryForObject("""
                SELECT coalesce(sum(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0) FROM ledger_entries
                """, BigDecimal.class);
        assertThat(balance).isEqualByComparingTo("0");
    }

    private static FundsReserved reserved(String amount) {
        return new FundsReserved(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal(amount), "UAH", Instant.now());
    }

    private void send(FundsReserved event) {
        kafkaTemplate.send(Topics.FUNDS_RESERVED, event.transferId().toString(), event).join();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DecisionCollector {

        private final Queue<LedgerDecision> received = new ConcurrentLinkedQueue<>();

        @KafkaListener(topics = Topics.LEDGER_DECISION, groupId = "ledger-it-decisions",
                properties = "spring.json.value.default.type=com.payflow.events.LedgerDecision")
        void collect(LedgerDecision decision) {
            received.add(decision);
        }

        List<LedgerDecision> forTransfer(UUID transferId) {
            return received.stream().filter(d -> d.transferId().equals(transferId)).toList();
        }

        int size() {
            return received.size();
        }

        LedgerDecision await(UUID transferId) {
            org.awaitility.Awaitility.await().atMost(TIMEOUT).until(() -> !forTransfer(transferId).isEmpty());
            return forTransfer(transferId).getFirst();
        }
    }
}
