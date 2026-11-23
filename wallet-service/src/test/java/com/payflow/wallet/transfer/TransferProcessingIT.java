package com.payflow.wallet.transfer;

import com.payflow.events.FailureReason;
import com.payflow.events.FundsReserved;
import com.payflow.events.LedgerApproved;
import com.payflow.events.LedgerRejected;
import com.payflow.events.SagaMessage;
import com.payflow.events.Topics;
import com.payflow.events.TransferCompleted;
import com.payflow.events.TransferFailed;
import com.payflow.events.TransferRequested;
import com.payflow.events.TransferResult;
import com.payflow.wallet.TestcontainersConfiguration;
import com.payflow.wallet.domain.Wallet;
import com.payflow.wallet.domain.WalletRepository;
import com.payflow.wallet.domain.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The Wallet Service's part of the choreography saga against a real PostgreSQL and Kafka. The test plays the
 * Transaction and Ledger services: it sends their events and reads what the Wallet Service publishes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({TestcontainersConfiguration.class, TransferProcessingIT.Collector.class})
class TransferProcessingIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private WalletService walletService;

    @Autowired
    private Collector messages;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void approvedTransferIsHeldThenCapturedToTheTarget() {
        UUID source = wallet("100.00");
        UUID target = wallet("0.00");
        TransferRequested request = request(source, target, "30.00");

        send(Topics.TRANSFER_REQUESTED, request.sourceWalletId(), request);

        FundsReserved reserved = messages.await(request.transferId(), FundsReserved.class);
        assertThat(reserved.amount()).isEqualByComparingTo("30.00");
        assertThat(balance(source)).isEqualByComparingTo("70.00");
        assertThat(balance(target)).isEqualByComparingTo("0.00");
        assertThat(holdStatus(request.transferId())).isEqualTo("HELD");

        send(Topics.LEDGER_DECISION, request.transferId(),
                new LedgerApproved(UUID.randomUUID(), request.transferId(), Instant.now()));

        messages.await(request.transferId(), TransferCompleted.class);
        assertThat(balance(target)).isEqualByComparingTo("30.00");
        assertThat(holdStatus(request.transferId())).isEqualTo("CAPTURED");
    }

    @Test
    void ledgerRejectionReleasesTheHeldFundsToTheSource() {
        UUID source = wallet("100.00");
        UUID target = wallet("0.00");
        TransferRequested request = request(source, target, "30.00");
        send(Topics.TRANSFER_REQUESTED, request.sourceWalletId(), request);
        messages.await(request.transferId(), FundsReserved.class);

        send(Topics.LEDGER_DECISION, request.transferId(), new LedgerRejected(UUID.randomUUID(),
                request.transferId(), FailureReason.LIMIT_EXCEEDED, Instant.now()));

        TransferFailed failed = messages.await(request.transferId(), TransferFailed.class);
        assertThat(failed.reason()).isEqualTo(FailureReason.LIMIT_EXCEEDED);
        assertThat(balance(source)).isEqualByComparingTo("100.00");
        assertThat(balance(target)).isEqualByComparingTo("0.00");
        assertThat(holdStatus(request.transferId())).isEqualTo("RELEASED");
    }

    @Test
    void insufficientFundsIsRejectedWithoutReservation() {
        UUID source = wallet("10.00");
        TransferRequested request = request(source, wallet("0.00"), "10.01");

        send(Topics.TRANSFER_REQUESTED, request.sourceWalletId(), request);

        TransferFailed failed = messages.await(request.transferId(), TransferFailed.class);
        assertThat(failed.reason()).isEqualTo(FailureReason.INSUFFICIENT_FUNDS);
        assertThat(balance(source)).isEqualByComparingTo("10.00");
        assertThat(holdStatus(request.transferId())).isEqualTo("REJECTED");
        assertThat(messages.find(request.transferId(), FundsReserved.class)).isEmpty();
    }

    @Test
    void unknownWalletIsRejected() {
        TransferRequested request = request(wallet("10.00"), UUID.randomUUID(), "1.00");

        send(Topics.TRANSFER_REQUESTED, request.sourceWalletId(), request);

        assertThat(messages.await(request.transferId(), TransferFailed.class).reason())
                .isEqualTo(FailureReason.WALLET_NOT_FOUND);
    }

    @Test
    void redeliveredEventsAreAppliedOnlyOnce() {
        UUID source = wallet("100.00");
        UUID target = wallet("0.00");
        TransferRequested request = request(source, target, "40.00");
        LedgerApproved approved = new LedgerApproved(UUID.randomUUID(), request.transferId(), Instant.now());

        send(Topics.TRANSFER_REQUESTED, request.sourceWalletId(), request);
        send(Topics.TRANSFER_REQUESTED, request.sourceWalletId(), request); // same eventId, as after a consumer crash
        messages.await(request.transferId(), FundsReserved.class);
        send(Topics.LEDGER_DECISION, request.transferId(), approved);
        send(Topics.LEDGER_DECISION, request.transferId(), approved);

        messages.await(request.transferId(), TransferCompleted.class);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .until(() -> messages.forTransfer(request.transferId()).size() == 2);
        assertThat(balance(source)).isEqualByComparingTo("60.00");
        assertThat(balance(target)).isEqualByComparingTo("40.00");
    }

    /**
     * Many concurrent transfers between a few wallets force optimistic lock conflicts and redeliveries. Every other
     * one is rejected by the "ledger" and compensated. Money is conserved and no balance goes negative.
     */
    @Test
    void concurrentTransfersAndCompensationsPreserveTotalMoney() {
        List<UUID> wallets = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            wallets.add(wallet("1000.00"));
        }
        BigDecimal totalBefore = walletService.totalBalance();
        Random random = new Random(42);
        List<TransferRequested> requests = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int from = random.nextInt(wallets.size());
            int to = (from + 1 + random.nextInt(wallets.size() - 1)) % wallets.size();
            TransferRequested request = request(wallets.get(from), wallets.get(to), String.valueOf(1 + random.nextInt(50)));
            requests.add(request);
            send(Topics.TRANSFER_REQUESTED, request.sourceWalletId(), request);
        }
        for (int i = 0; i < requests.size(); i++) {
            UUID transferId = requests.get(i).transferId();
            messages.await(transferId, SagaMessage.class);
            if (messages.find(transferId, FundsReserved.class).isPresent()) {
                send(Topics.LEDGER_DECISION, transferId, i % 2 == 0
                        ? new LedgerApproved(UUID.randomUUID(), transferId, Instant.now())
                        : new LedgerRejected(UUID.randomUUID(), transferId, FailureReason.LIMIT_EXCEEDED, Instant.now()));
            }
        }

        await().atMost(Duration.ofSeconds(60)).until(() -> requests.stream()
                .allMatch(r -> messages.find(r.transferId(), TransferResult.class).isPresent()));
        assertThat(walletService.totalBalance()).isEqualByComparingTo(totalBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transfer_holds WHERE status = 'HELD'",
                Long.class)).isZero();
        assertThat(wallets).allSatisfy(id -> assertThat(balance(id)).isNotNegative());
    }

    private String holdStatus(UUID transferId) {
        return jdbcTemplate.queryForObject("SELECT status FROM transfer_holds WHERE transfer_id = ?", String.class,
                transferId);
    }

    private UUID wallet(String balance) {
        return walletRepository.save(new Wallet("it-user", "UAH", new BigDecimal(balance))).getId();
    }

    private BigDecimal balance(UUID walletId) {
        return walletRepository.findById(walletId).orElseThrow().getBalance();
    }

    private static TransferRequested request(UUID source, UUID target, String amount) {
        return new TransferRequested(UUID.randomUUID(), UUID.randomUUID(), source, target,
                new BigDecimal(amount), "UAH", Instant.now());
    }

    private void send(String topic, UUID key, Object message) {
        kafkaTemplate.send(topic, key.toString(), message).join();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Collector {

        private final Queue<SagaMessage> received = new ConcurrentLinkedQueue<>();

        @KafkaListener(topics = Topics.FUNDS_RESERVED, groupId = "wallet-it-reserved",
                properties = "spring.json.value.default.type=com.payflow.events.FundsReserved")
        void reserved(FundsReserved event) {
            received.add(event);
        }

        @KafkaListener(topics = Topics.TRANSFER_RESULT, groupId = "wallet-it-results",
                properties = "spring.json.value.default.type=com.payflow.events.TransferResult")
        void result(TransferResult result) {
            received.add(result);
        }

        List<SagaMessage> forTransfer(UUID transferId) {
            return received.stream().filter(m -> m.transferId().equals(transferId)).toList();
        }

        <T extends SagaMessage> java.util.Optional<T> find(UUID transferId, Class<T> type) {
            return forTransfer(transferId).stream().filter(type::isInstance).map(type::cast).findFirst();
        }

        <T extends SagaMessage> T await(UUID transferId, Class<T> type) {
            org.awaitility.Awaitility.await().atMost(TIMEOUT).until(() -> find(transferId, type).isPresent());
            return find(transferId, type).orElseThrow();
        }
    }
}
