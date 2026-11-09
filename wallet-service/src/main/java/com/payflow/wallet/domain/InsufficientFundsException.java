package com.payflow.wallet.domain;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(UUID walletId, BigDecimal balance, BigDecimal requested) {
        super("Wallet %s has insufficient funds: balance=%s, requested=%s".formatted(walletId, balance, requested));
    }
}
