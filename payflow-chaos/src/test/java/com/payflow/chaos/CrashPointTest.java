package com.payflow.chaos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrashPointTest {

    private final AtomicInteger crashes = new AtomicInteger();
    private final List<String> afterCommitOrder = new ArrayList<>();

    @BeforeEach
    void startTransaction() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void endTransaction() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void notArmedDoesNothing() {
        new CrashPoint(true, crashes::incrementAndGet).afterCommit();

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void armedCrashHappensOnceAndBeforeOtherAfterCommitWork() {
        var crashPoint = new CrashPoint(true, () -> {
            crashes.incrementAndGet();
            afterCommitOrder.add("crash");
        });
        crashPoint.arm();

        // The publisher registers its send first, as the services do; the crash must still run before it.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                afterCommitOrder.add("send");
            }
        });
        crashPoint.afterCommit();
        crashPoint.afterCommit();
        commit();

        assertThat(crashes).hasValue(1);
        assertThat(afterCommitOrder).containsExactly("crash", "send");
    }

    @Test
    void armingIsRejectedWhenDisabled() {
        assertThatThrownBy(() -> new CrashPoint(false, crashes::incrementAndGet).arm())
                .isInstanceOf(IllegalStateException.class);
    }

    private static void commit() {
        // Same call the transaction manager makes; getSynchronizations() returns them sorted by Ordered.
        TransactionSynchronizationUtils.invokeAfterCommit(TransactionSynchronizationManager.getSynchronizations());
    }
}
