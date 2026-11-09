package com.payflow.wallet.transfer;

import com.payflow.events.Topics;
import com.payflow.events.TransferCompleted;
import com.payflow.events.TransferFailed;
import com.payflow.events.TransferFailed.FailureReason;
import com.payflow.events.TransferRequested;
import com.payflow.events.TransferResult;
import com.payflow.wallet.TestcontainersConfiguration;
import com.payflow.wallet.domain.Wallet;
import com.payflow.wallet.domain.WalletRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({TestcontainersConfiguration.class, TransferProcessingIT.ResultCollector.class})
class TransferProcessingIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private ResultCollector results;

    @Test
    void successfulTransferMovesMoneyAndReportsCompletion() {
        UUID source = wallet("100.00");
        UUID target = wallet("0.00");
        TransferRequested request = request(source, target, "30.00");

        send(request);

        TransferResult result = awaitResult(request.transferId());
        assertThat(result).isInstanceOf(TransferCompleted.class);
        assertThat(balance(source)).isEqualByComparingTo("70.00");
        assertThat(balance(target)).isEqualByComparingTo("30.00");
    }

    @Test
    void insufficientFundsFailsWithoutChangingBalances() {
        UUID source = wallet("10.00");
        UUID target = wallet("0.00");
        TransferRequested request = request(source, target, "10.01");

        send(request);

        assertThat(awaitResult(request.transferId()))
                .isInstanceOfSatisfying(TransferFailed.class,
                        failed -> assertThat(failed.reason()).isEqualTo(FailureReason.INSUFFICIENT_FUNDS));
        assertThat(balance(source)).isEqualByComparingTo("10.00");
        assertThat(balance(target)).isEqualByComparingTo("0.00");
    }

    @Test
    void unknownWalletFails() {
        TransferRequested request = request(wallet("10.00"), UUID.randomUUID(), "1.00");

        send(request);

        assertThat(awaitResult(request.transferId()))
                .isInstanceOfSatisfying(TransferFailed.class,
                        failed -> assertThat(failed.reason()).isEqualTo(FailureReason.WALLET_NOT_FOUND));
    }

    @Test
    void redeliveredEventIsAppliedOnlyOnce() {
        UUID source = wallet("100.00");
        UUID target = wallet("0.00");
        TransferRequested request = request(source, target, "40.00");

        send(request);
        send(request); // same eventId, as after a consumer crash before the offset commit

        awaitResult(request.transferId());
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .until(() -> results.forTransfer(request.transferId()).size() == 1);
        assertThat(balance(source)).isEqualByComparingTo("60.00");
        assertThat(balance(target)).isEqualByComparingTo("40.00");
    }

    /**
     * Many concurrent transfers between a few wallets force optimistic lock conflicts and redeliveries.
     * The money-conservation invariant must hold and no balance may go negative.
     */
    @Test
    void concurrentTransfersPreserveTotalBalance() {
        List<UUID> wallets = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            wallets.add(wallet("1000.00"));
        }
        BigDecimal totalBefore = total(wallets);
        Random random = new Random(42);
        List<UUID> transferIds = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int from = random.nextInt(wallets.size());
            int to = (from + 1 + random.nextInt(wallets.size() - 1)) % wallets.size();
            TransferRequested request = request(wallets.get(from), wallets.get(to), String.valueOf(1 + random.nextInt(50)));
            transferIds.add(request.transferId());
            send(request);
        }

        await().atMost(Duration.ofSeconds(60))
                .until(() -> transferIds.stream().allMatch(id -> !results.forTransfer(id).isEmpty()));

        assertThat(total(wallets)).isEqualByComparingTo(totalBefore);
        assertThat(wallets).allSatisfy(id -> assertThat(balance(id)).isNotNegative());
    }

    private UUID wallet(String balance) {
        return walletRepository.save(new Wallet("it-user", "UAH", new BigDecimal(balance))).getId();
    }

    private BigDecimal balance(UUID walletId) {
        return walletRepository.findById(walletId).orElseThrow().getBalance();
    }

    private BigDecimal total(List<UUID> walletIds) {
        return walletIds.stream().map(this::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static TransferRequested request(UUID source, UUID target, String amount) {
        return new TransferRequested(UUID.randomUUID(), UUID.randomUUID(), source, target,
                new BigDecimal(amount), "UAH", Instant.now());
    }

    private void send(TransferRequested request) {
        kafkaTemplate.send(Topics.TRANSFER_REQUESTED, request.sourceWalletId().toString(), request).join();
    }

    private TransferResult awaitResult(UUID transferId) {
        await().atMost(TIMEOUT).until(() -> !results.forTransfer(transferId).isEmpty());
        return results.forTransfer(transferId).getFirst();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ResultCollector {

        private final Queue<TransferResult> received = new ConcurrentLinkedQueue<>();

        @KafkaListener(topics = Topics.TRANSFER_RESULT, groupId = "wallet-it-result-collector",
                properties = "spring.json.value.default.type=com.payflow.events.TransferResult")
        void collect(TransferResult result) {
            received.add(result);
        }

        List<TransferResult> forTransfer(UUID transferId) {
            return received.stream().filter(r -> r.transferId().equals(transferId)).toList();
        }
    }
}
