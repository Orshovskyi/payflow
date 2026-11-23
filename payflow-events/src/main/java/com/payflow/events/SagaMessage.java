package com.payflow.events;

import java.util.UUID;

/**
 * Common shape of every event, command and reply in the transfer saga.
 * {@code eventId} is unique per message and is what consumers deduplicate on (delivery is at-least-once);
 * {@code transferId} identifies the saga instance.
 */
public interface SagaMessage {

    UUID eventId();

    UUID transferId();
}
