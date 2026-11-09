package com.payflow.wallet.domain;

import com.payflow.wallet.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the lost-update protection: two writers read the same version, the second commit must fail.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class WalletOptimisticLockingIT {

    @Autowired
    private WalletRepository walletRepository;

    @Test
    void staleWriteIsRejected() {
        var id = walletRepository.save(new Wallet("bob", "UAH", new BigDecimal("100.00"))).getId();

        Wallet first = walletRepository.findById(id).orElseThrow();
        Wallet second = walletRepository.findById(id).orElseThrow();

        first.debit(new BigDecimal("60.00"));
        walletRepository.save(first);

        second.debit(new BigDecimal("60.00"));
        assertThatThrownBy(() -> walletRepository.save(second))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        Wallet stored = walletRepository.findById(id).orElseThrow();
        assertThat(stored.getBalance()).isEqualByComparingTo("40.00");
        assertThat(stored.getVersion()).isEqualTo(1L);
    }
}
