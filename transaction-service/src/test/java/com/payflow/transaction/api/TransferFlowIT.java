package com.payflow.transaction.api;

import com.payflow.events.Topics;
import com.payflow.events.TransferCompleted;
import com.payflow.events.TransferFailed;
import com.payflow.events.TransferFailed.FailureReason;
import com.payflow.events.TransferRequested;
import com.payflow.transaction.TestcontainersConfiguration;
import com.payflow.transaction.api.TransferDtos.CreateTransferRequest;
import com.payflow.transaction.api.TransferDtos.TransferResponse;
import com.payflow.transaction.domain.TransferStatus;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TransferFlowIT.RequestCollector.class})
class TransferFlowIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private RequestCollector requests;

    @Test
    void transferIsPublishedAndCompletedByResultEvent() {
        UUID source = UUID.randomUUID();
        var created = rest.postForEntity("/api/v1/transfers",
                new CreateTransferRequest(source, UUID.randomUUID(), new BigDecimal("25.00"), "UAH"),
                TransferResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID transferId = created.getBody().id();
        assertThat(created.getBody().status()).isEqualTo(TransferStatus.PENDING);

        await().atMost(TIMEOUT).until(() -> requests.find(transferId).isPresent());
        ConsumerRecord<String, TransferRequested> record = requests.find(transferId).orElseThrow();
        assertThat(record.key()).isEqualTo(source.toString());
        assertThat(record.value().amount()).isEqualByComparingTo("25.00");

        sendResult(new TransferCompleted(UUID.randomUUID(), transferId, Instant.now()));

        await().atMost(TIMEOUT).until(() -> status(transferId) == TransferStatus.COMPLETED);
    }

    @Test
    void lateConflictingResultDoesNotChangeFinishedTransfer() {
        var created = rest.postForEntity("/api/v1/transfers",
                new CreateTransferRequest(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ONE, "UAH"),
                TransferResponse.class);
        UUID transferId = created.getBody().id();

        sendResult(new TransferFailed(UUID.randomUUID(), transferId, FailureReason.INSUFFICIENT_FUNDS, Instant.now()));
        await().atMost(TIMEOUT).until(() -> status(transferId) == TransferStatus.FAILED);

        sendResult(new TransferCompleted(UUID.randomUUID(), transferId, Instant.now()));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .until(() -> status(transferId) == TransferStatus.FAILED);
        assertThat(rest.getForObject("/api/v1/transfers/{id}", TransferResponse.class, transferId).failureReason())
                .isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void invalidTransfersAreRejected() {
        UUID wallet = UUID.randomUUID();
        var sameWallet = rest.postForEntity("/api/v1/transfers",
                new CreateTransferRequest(wallet, wallet, BigDecimal.ONE, "UAH"), ProblemDetail.class);
        var zeroAmount = rest.postForEntity("/api/v1/transfers",
                new CreateTransferRequest(wallet, UUID.randomUUID(), BigDecimal.ZERO, "UAH"), ProblemDetail.class);

        assertThat(sameWallet.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(zeroAmount.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private TransferStatus status(UUID transferId) {
        return rest.getForObject("/api/v1/transfers/{id}", TransferResponse.class, transferId).status();
    }

    private void sendResult(Object result) {
        kafkaTemplate.send(Topics.TRANSFER_RESULT, UUID.randomUUID().toString(), result).join();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RequestCollector {

        private final Queue<ConsumerRecord<String, TransferRequested>> received = new ConcurrentLinkedQueue<>();

        @KafkaListener(topics = Topics.TRANSFER_REQUESTED, groupId = "transaction-it-request-collector",
                properties = "spring.json.value.default.type=com.payflow.events.TransferRequested")
        void collect(ConsumerRecord<String, TransferRequested> record) {
            received.add(record);
        }

        Optional<ConsumerRecord<String, TransferRequested>> find(UUID transferId) {
            return received.stream().filter(r -> r.value().transferId().equals(transferId)).findFirst();
        }
    }
}
