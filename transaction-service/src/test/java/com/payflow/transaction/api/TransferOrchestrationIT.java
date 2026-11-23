package com.payflow.transaction.api;

import com.payflow.events.CaptureFunds;
import com.payflow.events.FailureReason;
import com.payflow.events.FundsCaptured;
import com.payflow.events.FundsReleased;
import com.payflow.events.FundsReserved;
import com.payflow.events.LedgerApproved;
import com.payflow.events.LedgerRejected;
import com.payflow.events.RecordTransfer;
import com.payflow.events.ReleaseFunds;
import com.payflow.events.ReservationFailed;
import com.payflow.events.ReserveFunds;
import com.payflow.events.SagaMessage;
import com.payflow.events.Topics;
import com.payflow.events.WalletCommand;
import com.payflow.transaction.TestcontainersConfiguration;
import com.payflow.transaction.api.TransferDtos.CreateTransferRequest;
import com.payflow.transaction.api.TransferDtos.TransferResponse;
import com.payflow.transaction.domain.SagaStep;
import com.payflow.transaction.domain.TransferRepository;
import com.payflow.transaction.domain.TransferStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The orchestrator against a real PostgreSQL and Kafka. The test plays the Wallet and Ledger services: it reads the
 * orchestrator's commands and sends back the replies.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"payflow.saga.coordination=orchestration", "payflow.consistency.strategy=outbox"})
@Import({TestcontainersConfiguration.class, TransferOrchestrationIT.CommandCollector.class})
class TransferOrchestrationIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private CommandCollector commands;

    @Autowired
    private TransferRepository transfers;

    @Test
    void approvedTransferGoesThroughReserveRecordAndCapture() {
        UUID transferId = createTransfer(new BigDecimal("5.00"));

        ReserveFunds reserve = commands.await(transferId, ReserveFunds.class);
        assertThat(reserve.amount()).isEqualByComparingTo("5.00");
        reply(Topics.WALLET_REPLIES, new FundsReserved(UUID.randomUUID(), transferId, reserve.sourceWalletId(),
                reserve.targetWalletId(), reserve.amount(), reserve.currency(), Instant.now()));

        commands.await(transferId, RecordTransfer.class);
        reply(Topics.LEDGER_REPLIES, new LedgerApproved(UUID.randomUUID(), transferId, Instant.now()));

        commands.await(transferId, CaptureFunds.class);
        reply(Topics.WALLET_REPLIES, new FundsCaptured(UUID.randomUUID(), transferId, Instant.now()));

        await().atMost(TIMEOUT).until(() -> status(transferId) == TransferStatus.COMPLETED);
        assertThat(transfers.findById(transferId).orElseThrow().getSagaStep()).isEqualTo(SagaStep.DONE);
    }

    @Test
    void ledgerRejectionIsCompensatedByReleasingTheFunds() {
        UUID transferId = createTransfer(new BigDecimal("10.00"));
        ReserveFunds reserve = commands.await(transferId, ReserveFunds.class);
        reply(Topics.WALLET_REPLIES, new FundsReserved(UUID.randomUUID(), transferId, reserve.sourceWalletId(),
                reserve.targetWalletId(), reserve.amount(), reserve.currency(), Instant.now()));
        commands.await(transferId, RecordTransfer.class);

        reply(Topics.LEDGER_REPLIES,
                new LedgerRejected(UUID.randomUUID(), transferId, FailureReason.LIMIT_EXCEEDED, Instant.now()));

        ReleaseFunds release = commands.await(transferId, ReleaseFunds.class);
        assertThat(release.reason()).isEqualTo(FailureReason.LIMIT_EXCEEDED);
        assertThat(status(transferId)).isEqualTo(TransferStatus.PENDING);

        reply(Topics.WALLET_REPLIES, new FundsReleased(UUID.randomUUID(), transferId, Instant.now()));
        await().atMost(TIMEOUT).until(() -> status(transferId) == TransferStatus.FAILED);
        assertThat(rest.getForObject("/api/v1/transfers/{id}", TransferResponse.class, transferId).failureReason())
                .isEqualTo("LIMIT_EXCEEDED");
    }

    @Test
    void failedReservationEndsTheSagaWithoutFurtherCommands() {
        UUID transferId = createTransfer(BigDecimal.ONE);
        commands.await(transferId, ReserveFunds.class);

        reply(Topics.WALLET_REPLIES,
                new ReservationFailed(UUID.randomUUID(), transferId, FailureReason.INSUFFICIENT_FUNDS, Instant.now()));

        await().atMost(TIMEOUT).until(() -> status(transferId) == TransferStatus.FAILED);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .until(() -> commands.find(transferId, RecordTransfer.class).isEmpty());
    }

    @Test
    void duplicateReplyDoesNotAdvanceTheSagaTwice() {
        UUID transferId = createTransfer(BigDecimal.ONE);
        ReserveFunds reserve = commands.await(transferId, ReserveFunds.class);
        FundsReserved reserved = new FundsReserved(UUID.randomUUID(), transferId, reserve.sourceWalletId(),
                reserve.targetWalletId(), reserve.amount(), reserve.currency(), Instant.now());

        reply(Topics.WALLET_REPLIES, reserved);
        reply(Topics.WALLET_REPLIES, reserved);

        commands.await(transferId, RecordTransfer.class);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .until(() -> commands.count(transferId, RecordTransfer.class) == 1);
        assertThat(transfers.findById(transferId).orElseThrow().getSagaStep()).isEqualTo(SagaStep.POSTING);
    }

    private UUID createTransfer(BigDecimal amount) {
        return rest.postForEntity("/api/v1/transfers",
                new CreateTransferRequest(UUID.randomUUID(), UUID.randomUUID(), amount, "UAH"),
                TransferResponse.class).getBody().id();
    }

    private TransferStatus status(UUID transferId) {
        return rest.getForObject("/api/v1/transfers/{id}", TransferResponse.class, transferId).status();
    }

    private void reply(String topic, SagaMessage message) {
        kafkaTemplate.send(topic, message.transferId().toString(), message).join();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CommandCollector {

        private final Queue<SagaMessage> received = new ConcurrentLinkedQueue<>();

        @KafkaListener(topics = Topics.WALLET_COMMANDS, groupId = "orchestration-it-wallet",
                properties = "spring.json.value.default.type=com.payflow.events.WalletCommand")
        void walletCommand(WalletCommand command) {
            received.add(command);
        }

        @KafkaListener(topics = Topics.LEDGER_COMMANDS, groupId = "orchestration-it-ledger",
                properties = "spring.json.value.default.type=com.payflow.events.RecordTransfer")
        void ledgerCommand(RecordTransfer command) {
            received.add(command);
        }

        <T extends SagaMessage> Optional<T> find(UUID transferId, Class<T> type) {
            return received.stream().filter(type::isInstance).map(type::cast)
                    .filter(m -> m.transferId().equals(transferId)).findFirst();
        }

        long count(UUID transferId, Class<? extends SagaMessage> type) {
            return received.stream().filter(type::isInstance).filter(m -> m.transferId().equals(transferId)).count();
        }

        <T extends SagaMessage> T await(UUID transferId, Class<T> type) {
            org.awaitility.Awaitility.await().atMost(TIMEOUT).until(() -> find(transferId, type).isPresent());
            return find(transferId, type).orElseThrow();
        }
    }
}
