package com.payflow.transaction.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferTest {

    private final UUID source = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @Test
    void newTransferIsPending() {
        assertThat(new Transfer(source, target, BigDecimal.TEN, "UAH").getStatus()).isEqualTo(TransferStatus.PENDING);
    }

    @Test
    void terminalStatusIsFinal() {
        var transfer = new Transfer(source, target, BigDecimal.TEN, "UAH");

        assertThat(transfer.complete()).isTrue();
        assertThat(transfer.fail("INSUFFICIENT_FUNDS")).isFalse();
        assertThat(transfer.complete()).isFalse();
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transfer.getFailureReason()).isNull();
    }

    @Test
    void invalidTransfersAreRejected() {
        assertThatThrownBy(() -> new Transfer(source, source, BigDecimal.TEN, "UAH"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Transfer(source, target, BigDecimal.ZERO, "UAH"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
