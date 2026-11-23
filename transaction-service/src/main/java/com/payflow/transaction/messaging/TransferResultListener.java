package com.payflow.transaction.messaging;

import com.payflow.events.Topics;
import com.payflow.events.TransferResult;
import com.payflow.transaction.domain.TransferService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Choreography: the final outcome of a transfer arrives from the Wallet Service. */
@Component
@ConditionalOnProperty(name = "payflow.saga.coordination", havingValue = "choreography", matchIfMissing = true)
public class TransferResultListener {

    private final TransferService transferService;

    public TransferResultListener(TransferService transferService) {
        this.transferService = transferService;
    }

    @KafkaListener(topics = Topics.TRANSFER_RESULT,
            properties = "spring.json.value.default.type=com.payflow.events.TransferResult")
    public void onResult(TransferResult result) {
        transferService.handleResult(result);
    }
}
