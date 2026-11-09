package com.payflow.wallet.domain;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class WalletService {

    private final WalletRepository walletRepository;

    public WalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
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

    @Transactional(readOnly = true)
    public BigDecimal totalBalance() {
        return walletRepository.totalBalance();
    }
}
