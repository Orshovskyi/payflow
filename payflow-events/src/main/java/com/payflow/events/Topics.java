package com.payflow.events;

/** Kafka topic names. Topic auto-creation is disabled on the broker, so services declare them explicitly. */
public final class Topics {

    /** Transaction Service -> Wallet Service. Key: source wallet id (orders all debits of one wallet). */
    public static final String TRANSFER_REQUESTED = "payflow.transfer.requested";

    /** Wallet Service -> Transaction Service. Key: transfer id. Carries {@link TransferResult} subtypes. */
    public static final String TRANSFER_RESULT = "payflow.transfer.result";

    public static final int PARTITIONS = 3;

    private Topics() {
    }
}
