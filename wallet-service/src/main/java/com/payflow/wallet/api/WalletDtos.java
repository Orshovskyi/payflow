package com.payflow.wallet.api;

import com.payflow.wallet.domain.Wallet;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class WalletDtos {

    private WalletDtos() {
    }

    public record CreateWalletRequest(
            @NotBlank @Size(max = 64) String ownerId,
            @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code") String currency,
            @NotNull @DecimalMin("0.00") @Digits(integer = 15, fraction = 4) BigDecimal initialBalance) {
    }

    public record WalletResponse(
            UUID id, String ownerId, String currency, BigDecimal balance, long version, Instant createdAt) {

        static WalletResponse from(Wallet wallet) {
            return new WalletResponse(wallet.getId(), wallet.getOwnerId(), wallet.getCurrency(),
                    wallet.getBalance(), wallet.getVersion(), wallet.getCreatedAt());
        }
    }

    public record TotalBalanceResponse(BigDecimal totalBalance) {
    }
}
