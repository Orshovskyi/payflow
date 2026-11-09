package com.payflow.wallet.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletTest {

    @Test
    void debitAndCreditChangeBalance() {
        var wallet = new Wallet("alice", "UAH", new BigDecimal("100.00"));

        wallet.debit(new BigDecimal("30.00"));
        wallet.credit(new BigDecimal("5.50"));

        assertThat(wallet.getBalance()).isEqualByComparingTo("75.50");
    }

    @Test
    void debitBeyondBalanceIsRejectedAndBalanceUnchanged() {
        var wallet = new Wallet("alice", "UAH", new BigDecimal("10.00"));

        assertThatThrownBy(() -> wallet.debit(new BigDecimal("10.01")))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(wallet.getBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    void nonPositiveAmountsAreRejected() {
        var wallet = new Wallet("alice", "UAH", BigDecimal.TEN);

        assertThatThrownBy(() -> wallet.credit(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> wallet.debit(new BigDecimal("-1"))).isInstanceOf(IllegalArgumentException.class);
    }
}
