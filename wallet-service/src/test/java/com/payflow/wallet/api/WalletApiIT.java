package com.payflow.wallet.api;

import com.payflow.wallet.TestcontainersConfiguration;
import com.payflow.wallet.api.WalletDtos.CreateWalletRequest;
import com.payflow.wallet.api.WalletDtos.TotalBalanceResponse;
import com.payflow.wallet.api.WalletDtos.WalletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class WalletApiIT {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void createdWalletCanBeFetched() {
        var created = rest.postForEntity("/api/v1/wallets",
                new CreateWalletRequest("alice", "UAH", new BigDecimal("250.00")), WalletResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).isNotNull();

        var fetched = rest.getForEntity(created.getHeaders().getLocation(), WalletResponse.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().ownerId()).isEqualTo("alice");
        assertThat(fetched.getBody().balance()).isEqualByComparingTo("250.00");
        assertThat(fetched.getBody().version()).isZero();
    }

    @Test
    void invalidRequestIsRejected() {
        var response = rest.postForEntity("/api/v1/wallets",
                new CreateWalletRequest("alice", "uah", new BigDecimal("-1")), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownWalletReturns404() {
        var response = rest.getForEntity("/api/v1/wallets/{id}", ProblemDetail.class, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void totalBalanceGrowsByInitialBalanceOfNewWallet() {
        BigDecimal before = rest.getForObject("/api/v1/wallets/total-balance", TotalBalanceResponse.class).totalBalance();

        rest.postForEntity("/api/v1/wallets",
                new CreateWalletRequest("carol", "UAH", new BigDecimal("10.00")), WalletResponse.class);

        BigDecimal after = rest.getForObject("/api/v1/wallets/total-balance", TotalBalanceResponse.class).totalBalance();
        assertThat(after.subtract(before)).isEqualByComparingTo("10.00");
    }
}
