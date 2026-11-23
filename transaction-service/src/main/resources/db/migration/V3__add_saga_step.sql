-- Orchestration (ADR-0008): the orchestrator keeps the saga step next to the transfer status, so after a crash it
-- knows which reply it is waiting for. Stays NULL with choreography, where no service holds the whole saga state.
ALTER TABLE transfers
    ADD COLUMN saga_step VARCHAR(20),
    ADD CONSTRAINT chk_transfers_saga_step
        CHECK (saga_step IS NULL OR saga_step IN ('RESERVING', 'POSTING', 'CAPTURING', 'RELEASING', 'DONE'));
