package com.payflow.ledger.api;

import com.payflow.ledger.domain.LedgerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    public record LedgerTransferResponse(UUID transferId, String decision, String failureReason,
                                         List<LedgerService.Entry> entries) {
    }

    private final LedgerService ledger;

    public LedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    /** The ledger's decision for a transfer and its double-entry posting. */
    @GetMapping("/transfers/{transferId}")
    public ResponseEntity<LedgerTransferResponse> transfer(@PathVariable UUID transferId) {
        return ledger.find(transferId)
                .map(d -> ResponseEntity.ok(new LedgerTransferResponse(transferId, d.approved() ? "APPROVED" : "REJECTED",
                        d.reason() == null ? null : d.reason().name(), ledger.entries(transferId))))
                .orElse(ResponseEntity.notFound().build());
    }
}
