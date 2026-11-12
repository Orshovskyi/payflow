package com.payflow.chaos;

import org.springframework.core.Ordered;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fault injection for the experiments: once armed, the process is killed right after the next business
 * transaction commits, before any other after-commit work runs.
 * <p>
 * This is exactly the window where the naive dual write loses events (state committed, event not yet sent).
 * A random {@code docker kill} almost never hits that window, so without this the comparison would mostly measure
 * luck. The crash point is the same for every strategy: with the outbox the event is already committed together
 * with the state, so it must survive the crash.
 * <p>
 * {@code Runtime.halt} skips shutdown hooks and buffered Kafka sends, which is equivalent to {@code kill -9}.
 */
public class CrashPoint {

    /** Exit code of a process killed by SIGKILL, so the crash looks the same as a real one to Docker. */
    static final int KILLED_EXIT_CODE = 137;

    private final boolean enabled;
    private final AtomicBoolean armed = new AtomicBoolean();
    private final Runnable crash;

    public CrashPoint(boolean enabled) {
        this(enabled, () -> {
            System.err.println("CHAOS: halting the process right after a commit");
            System.err.flush();
            Runtime.getRuntime().halt(KILLED_EXIT_CODE);
        });
    }

    CrashPoint(boolean enabled, Runnable crash) {
        this.enabled = enabled;
        this.crash = crash;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Arms a single crash. */
    public void arm() {
        if (!enabled) {
            throw new IllegalStateException("Fault injection is disabled (payflow.chaos.enabled=false)");
        }
        armed.set(true);
    }

    /**
     * Call inside a business transaction, before registering any other after-commit work.
     * Costs one volatile read when not armed.
     */
    public void afterCommit() {
        if (!armed.get() || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new CrashAfterCommit());
    }

    private final class CrashAfterCommit implements TransactionSynchronization, Ordered {

        @Override
        public void afterCommit() {
            if (armed.compareAndSet(true, false)) {
                crash.run();
            }
        }

        /** Runs before the naive publisher's after-commit send. */
        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }
}
