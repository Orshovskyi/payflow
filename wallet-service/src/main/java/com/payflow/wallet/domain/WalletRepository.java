package com.payflow.wallet.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    /** Sum of all balances; used by reconciliation to check the money-conservation invariant. */
    @Query("select coalesce(sum(w.balance), 0) from Wallet w")
    BigDecimal totalBalance();
}
