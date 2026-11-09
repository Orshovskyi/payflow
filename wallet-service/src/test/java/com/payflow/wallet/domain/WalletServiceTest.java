package com.payflow.wallet.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock
    private WalletRepository walletRepository;

    @InjectMocks
    private WalletService walletService;

    @Test
    void getThrowsWhenWalletIsMissing() {
        // given
        var id = UUID.randomUUID();
        given(walletRepository.findById(id)).willReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> walletService.get(id))
                .isInstanceOf(WalletNotFoundException.class)
                .hasMessageContaining(id.toString());
    }
}
