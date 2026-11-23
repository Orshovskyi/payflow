package com.payflow.events;

import java.util.List;

/**
 * Kafka topic names. Topic auto-creation is disabled on the broker; every service declares {@link #ALL}.
 * Messages of one transfer are keyed by transfer id, so they are read in order. The exception is the first
 * message, keyed by source wallet id, so that all debits of one wallet are processed in order.
 */
public final class Topics {

    // --- choreography: events
    /** Transaction -> Wallet: {@link TransferRequested}. */
    public static final String TRANSFER_REQUESTED = "payflow.transfer.requested";
    /** Wallet -> Ledger: {@link FundsReserved}. */
    public static final String FUNDS_RESERVED = "payflow.funds.reserved";
    /** Ledger -> Wallet: {@link LedgerDecision}. */
    public static final String LEDGER_DECISION = "payflow.ledger.decision";
    /** Wallet -> Transaction: {@link TransferResult}. */
    public static final String TRANSFER_RESULT = "payflow.transfer.result";

    // --- orchestration: commands and replies
    /** Orchestrator -> Wallet: {@link WalletCommand}. */
    public static final String WALLET_COMMANDS = "payflow.wallet.commands";
    /** Wallet -> orchestrator: {@link WalletReply}. */
    public static final String WALLET_REPLIES = "payflow.wallet.replies";
    /** Orchestrator -> Ledger: {@link RecordTransfer}. */
    public static final String LEDGER_COMMANDS = "payflow.ledger.commands";
    /** Ledger -> orchestrator: {@link LedgerDecision}. */
    public static final String LEDGER_REPLIES = "payflow.ledger.replies";

    public static final List<String> ALL = List.of(TRANSFER_REQUESTED, FUNDS_RESERVED, LEDGER_DECISION,
            TRANSFER_RESULT, WALLET_COMMANDS, WALLET_REPLIES, LEDGER_COMMANDS, LEDGER_REPLIES);

    public static final int PARTITIONS = 3;

    private Topics() {
    }
}
