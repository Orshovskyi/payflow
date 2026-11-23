package com.payflow.wallet.domain;

import com.payflow.wallet.transfer.TransferHolds;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class WalletService {

    private final WalletRepository walletRepository;
    private final TransferHolds holds;

    public WalletService(WalletRepository walletRepository, TransferHolds holds) {
        this.walletRepository = walletRepository;
        this.holds = holds;
    }

    @Transactional
    public Wallet create(String ownerId, String currency, BigDecimal initialBalance) {
        return walletRepository.save(new Wallet(ownerId, currency, initialBalance));
    }

    @Transactional(readOnly = true)
    public Wallet get(UUID walletId) {
        return walletRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));
    }

    /** All money in the system: wallet balances plus amounts held by unfinished transfers (ADR-0008). */
    @Transactional(readOnly = true)
    public BigDecimal totalBalance() {
        return walletRepository.totalBalance().add(holds.heldTotal());
    }
}
