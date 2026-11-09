package com.payflow.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes an event into the {@code outbox_events} table of the calling service. It must run inside the business
 * transaction: the state change and the event are committed together or not at all. That atomicity is the whole
 * point of the pattern, so a call outside a transaction fails fast.
 */
public class OutboxWriter {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OutboxWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * @param eventId the event's own id; it is also the row id, so the same event cannot be stored twice
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(UUID eventId, String topic, String key, Object event) {
        jdbcTemplate.update("""
                        INSERT INTO outbox_events (id, topic, message_key, event_type, payload)
                        VALUES (?, ?, ?, ?, ?::jsonb)
                        """,
                eventId, topic, key, event.getClass().getSimpleName(), toJson(event));
    }

    private String toJson(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize event " + event.getClass().getName(), e);
        }
    }
}
