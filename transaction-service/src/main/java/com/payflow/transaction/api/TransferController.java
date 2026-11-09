package com.payflow.transaction.api;

import com.payflow.transaction.api.TransferDtos.CreateTransferRequest;
import com.payflow.transaction.api.TransferDtos.TransferResponse;
import com.payflow.transaction.domain.Transfer;
import com.payflow.transaction.domain.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /** Returns 202: the transfer is accepted and runs asynchronously; poll GET for the outcome. */
    @PostMapping
    public ResponseEntity<TransferResponse> create(@Valid @RequestBody CreateTransferRequest request) {
        Transfer transfer = transferService.create(
                request.sourceWalletId(), request.targetWalletId(), request.amount(), request.currency());
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(transfer.getId()).toUri();
        return ResponseEntity.accepted().location(location).body(TransferResponse.from(transfer));
    }

    @GetMapping("/{id}")
    public TransferResponse get(@PathVariable UUID id) {
        return TransferResponse.from(transferService.get(id));
    }
}
