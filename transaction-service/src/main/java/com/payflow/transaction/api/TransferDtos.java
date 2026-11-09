package com.payflow.transaction.api;

import com.payflow.transaction.domain.Transfer;
import com.payflow.transaction.domain.TransferStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class TransferDtos {

    private TransferDtos() {
    }

    public record CreateTransferRequest(
            @NotNull UUID sourceWalletId,
            @NotNull UUID targetWalletId,
            @NotNull @DecimalMin(value = "0.00", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal amount,
            @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code") String currency) {
    }

    public record TransferResponse(
            UUID id, UUID sourceWalletId, UUID targetWalletId, BigDecimal amount, String currency,
            TransferStatus status, String failureReason, Instant createdAt, Instant updatedAt) {

        static TransferResponse from(Transfer t) {
            return new TransferResponse(t.getId(), t.getSourceWalletId(), t.getTargetWalletId(), t.getAmount(),
                    t.getCurrency(), t.getStatus(), t.getFailureReason(), t.getCreatedAt(), t.getUpdatedAt());
        }
    }
}
