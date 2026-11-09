package com.payflow.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransferResultJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void polymorphicResultSurvivesRoundTrip() throws Exception {
        TransferResult failed = new TransferFailed(UUID.randomUUID(), UUID.randomUUID(),
                TransferFailed.FailureReason.INSUFFICIENT_FUNDS, Instant.parse("2026-11-09T10:00:00Z"));

        String json = objectMapper.writeValueAsString(failed);

        assertThat(json).contains("\"type\":\"TransferFailed\"");
        assertThat(objectMapper.readValue(json, TransferResult.class)).isEqualTo(failed);
    }
}
