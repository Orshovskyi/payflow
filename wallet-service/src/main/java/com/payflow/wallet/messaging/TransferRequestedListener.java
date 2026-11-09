package com.payflow.wallet.messaging;

import com.payflow.events.Topics;
import com.payflow.events.TransferRequested;
import com.payflow.wallet.transfer.TransferProcessor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransferRequestedListener {

    private final TransferProcessor transferProcessor;

    public TransferRequestedListener(TransferProcessor transferProcessor) {
        this.transferProcessor = transferProcessor;
    }

    @KafkaListener(topics = Topics.TRANSFER_REQUESTED,
            properties = "spring.json.value.default.type=com.payflow.events.TransferRequested")
    public void onTransferRequested(TransferRequested request) {
        transferProcessor.process(request);
    }
}
