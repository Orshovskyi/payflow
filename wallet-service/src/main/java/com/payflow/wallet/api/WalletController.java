package com.payflow.wallet.api;

import com.payflow.wallet.api.WalletDtos.CreateWalletRequest;
import com.payflow.wallet.api.WalletDtos.TotalBalanceResponse;
import com.payflow.wallet.api.WalletDtos.WalletResponse;
import com.payflow.wallet.domain.Wallet;
import com.payflow.wallet.domain.WalletService;
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
@RequestMapping("/api/v1/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> create(@Valid @RequestBody CreateWalletRequest request) {
        Wallet wallet = walletService.create(request.ownerId(), request.currency(), request.initialBalance());
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(wallet.getId()).toUri();
        return ResponseEntity.created(location).body(WalletResponse.from(wallet));
    }

    @GetMapping("/{id}")
    public WalletResponse get(@PathVariable UUID id) {
        return WalletResponse.from(walletService.get(id));
    }

    /** Reconciliation endpoint: total money in the system must stay constant across transfers. */
    @GetMapping("/total-balance")
    public TotalBalanceResponse totalBalance() {
        return new TotalBalanceResponse(walletService.totalBalance());
    }
}
