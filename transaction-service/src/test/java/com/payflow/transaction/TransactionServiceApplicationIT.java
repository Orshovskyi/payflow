package com.payflow.transaction;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransactionServiceApplicationIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayCreatesTransfersTable() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'transfers'", Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
