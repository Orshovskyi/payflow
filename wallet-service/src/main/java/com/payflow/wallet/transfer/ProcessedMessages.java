package com.payflow.wallet.transfer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Repository
public class ProcessedMessages {

    private final JdbcTemplate jdbcTemplate;

    public ProcessedMessages(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Marks a message as processed within the caller's transaction.
     * Uses ON CONFLICT so that two concurrent deliveries cannot both succeed.
     *
     * @return true for the first delivery, false for a duplicate
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(UUID messageId) {
        int inserted = jdbcTemplate.update(
                "INSERT INTO processed_messages (message_id) VALUES (?) ON CONFLICT DO NOTHING", messageId);
        return inserted == 1;
    }
}
