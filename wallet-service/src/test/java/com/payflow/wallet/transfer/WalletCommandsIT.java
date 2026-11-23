package com.payflow.wallet.transfer;

import com.payflow.events.CaptureFunds;
import com.payflow.events.FailureReason;
import com.payflow.events.FundsCaptured;
import com.payflow.events.FundsReleased;
import com.payflow.events.FundsReserved;
import com.payflow.events.ReleaseFunds;
import com.payflow.events.ReservationFailed;
import com.payflow.events.ReserveFunds;
import com.payflow.events.Topics;
import com.payflow.events.WalletReply;
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
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Orchestration: the Wallet Service executes commands and replies; the test plays the orchestrator. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"payflow.saga.coordination=orchestration", "payflow.consistency.strategy=outbox"})
@Import({TestcontainersConfiguration.class, WalletCommandsIT.ReplyCollector.class})
class WalletCommandsIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private ReplyCollector replies;

    @Test
    void reserveThenCapture() {
        UUID source = wallet("50.00");
        UUID target = wallet("0.00");
        UUID transferId = UUID.randomUUID();

        command(new ReserveFunds(UUID.randomUUID(), transferId, source, target, new BigDecimal("20.00"), "UAH",
                Instant.now()));
        replies.await(transferId, FundsReserved.class);
        assertThat(balance(source)).isEqualByComparingTo("30.00");

        command(new CaptureFunds(UUID.randomUUID(), transferId, Instant.now()));
        replies.await(transferId, FundsCaptured.class);
        assertThat(balance(target)).isEqualByComparingTo("20.00");
    }

    @Test
    void reserveThenReleaseReturnsTheMoney() {
        UUID source = wallet("50.00");
        UUID transferId = UUID.randomUUID();
        command(new ReserveFunds(UUID.randomUUID(), transferId, source, wallet("0.00"), new BigDecimal("20.00"),
                "UAH", Instant.now()));
        replies.await(transferId, FundsReserved.class);

        command(new ReleaseFunds(UUID.randomUUID(), transferId, FailureReason.LIMIT_EXCEEDED, Instant.now()));

        replies.await(transferId, FundsReleased.class);
        assertThat(balance(source)).isEqualByComparingTo("50.00");
    }

    @Test
    void reservationFailureIsReplied() {
        UUID transferId = UUID.randomUUID();
        command(new ReserveFunds(UUID.randomUUID(), transferId, wallet("5.00"), wallet("0.00"), BigDecimal.TEN, "UAH",
                Instant.now()));

        assertThat(replies.await(transferId, ReservationFailed.class).reason())
                .isEqualTo(FailureReason.INSUFFICIENT_FUNDS);
    }

    @Test
    void captureAfterReleaseIsRefused() {
        UUID transferId = UUID.randomUUID();
        UUID target = wallet("0.00");
        command(new ReserveFunds(UUID.randomUUID(), transferId, wallet("50.00"), target, BigDecimal.TEN, "UAH",
                Instant.now()));
        replies.await(transferId, FundsReserved.class);
        command(new ReleaseFunds(UUID.randomUUID(), transferId, FailureReason.LIMIT_EXCEEDED, Instant.now()));
        replies.await(transferId, FundsReleased.class);

        command(new CaptureFunds(UUID.randomUUID(), transferId, Instant.now()));

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .until(() -> replies.find(transferId, FundsCaptured.class).isEmpty());
        assertThat(balance(target)).isEqualByComparingTo("0.00");
    }

    private void command(com.payflow.events.WalletCommand command) {
        kafkaTemplate.send(Topics.WALLET_COMMANDS, command.transferId().toString(), command).join();
    }

    private UUID wallet(String balance) {
        return walletRepository.save(new Wallet("it-user", "UAH", new BigDecimal(balance))).getId();
    }

    private BigDecimal balance(UUID walletId) {
        return walletRepository.findById(walletId).orElseThrow().getBalance();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ReplyCollector {

        private final Queue<WalletReply> received = new ConcurrentLinkedQueue<>();

        @KafkaListener(topics = Topics.WALLET_REPLIES, groupId = "wallet-it-replies",
                properties = "spring.json.value.default.type=com.payflow.events.WalletReply")
        void collect(WalletReply reply) {
            received.add(reply);
        }

        <T extends WalletReply> Optional<T> find(UUID transferId, Class<T> type) {
            return received.stream().filter(type::isInstance).map(type::cast)
                    .filter(r -> r.transferId().equals(transferId)).findFirst();
        }

        <T extends WalletReply> T await(UUID transferId, Class<T> type) {
            org.awaitility.Awaitility.await().atMost(TIMEOUT).until(() -> find(transferId, type).isPresent());
            return find(transferId, type).orElseThrow();
        }
    }
}
