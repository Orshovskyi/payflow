package com.payflow.ledger;

import com.payflow.events.LedgerApproved;
import com.payflow.events.LedgerDecision;
import com.payflow.events.LedgerRejected;
import com.payflow.events.RecordTransfer;
import com.payflow.events.Topics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Orchestration: the Ledger Service answers the orchestrator's command on the reply topic. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"payflow.saga.coordination=orchestration", "payflow.consistency.strategy=outbox",
                "payflow.ledger.max-transfer-amount=9"})
@Import({TestcontainersConfiguration.class, LedgerCommandsIT.ReplyCollector.class})
class LedgerCommandsIT {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private ReplyCollector replies;

    @Test
    void commandIsAnsweredWithTheDecision() {
        RecordTransfer small = command("5.00");
        RecordTransfer large = command("50.00");

        send(small);
        send(large);

        await().atMost(Duration.ofSeconds(30)).until(() -> replies.find(small.transferId()) != null
                && replies.find(large.transferId()) != null);
        assertThat(replies.find(small.transferId())).isInstanceOf(LedgerApproved.class);
        assertThat(replies.find(large.transferId())).isInstanceOf(LedgerRejected.class);
    }

    @Test
    void repeatedCommandWithNewIdGetsTheSameDecision() {
        RecordTransfer first = command("50.00");
        RecordTransfer resent = new RecordTransfer(UUID.randomUUID(), first.transferId(), first.sourceWalletId(),
                first.targetWalletId(), first.amount(), first.currency(), Instant.now());

        send(first);
        send(resent);

        await().atMost(Duration.ofSeconds(30)).until(() -> replies.count(first.transferId()) == 2);
        assertThat(replies.received).filteredOn(r -> r.transferId().equals(first.transferId()))
                .allSatisfy(r -> assertThat(r).isInstanceOf(LedgerRejected.class));
    }

    private static RecordTransfer command(String amount) {
        return new RecordTransfer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal(amount), "UAH", Instant.now());
    }

    private void send(RecordTransfer command) {
        kafkaTemplate.send(Topics.LEDGER_COMMANDS, command.transferId().toString(), command).join();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ReplyCollector {

        final Queue<LedgerDecision> received = new ConcurrentLinkedQueue<>();

        @KafkaListener(topics = Topics.LEDGER_REPLIES, groupId = "ledger-it-replies",
                properties = "spring.json.value.default.type=com.payflow.events.LedgerDecision")
        void collect(LedgerDecision reply) {
            received.add(reply);
        }

        LedgerDecision find(UUID transferId) {
            return received.stream().filter(r -> r.transferId().equals(transferId)).findFirst().orElse(null);
        }

        long count(UUID transferId) {
            return received.stream().filter(r -> r.transferId().equals(transferId)).count();
        }
    }
}
