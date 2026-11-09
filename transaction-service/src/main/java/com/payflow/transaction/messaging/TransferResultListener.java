package com.payflow.transaction.messaging;

import com.payflow.events.Topics;
import com.payflow.events.TransferResult;
import com.payflow.transaction.domain.TransferService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
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
