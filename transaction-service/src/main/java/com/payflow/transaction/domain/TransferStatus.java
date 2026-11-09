package com.payflow.transaction.domain;

public enum TransferStatus {
    PENDING,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this != PENDING;
    }
}
