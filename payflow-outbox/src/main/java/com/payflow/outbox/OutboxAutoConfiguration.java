package com.payflow.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Active only with {@code payflow.consistency.strategy=outbox}. Services provide the {@code outbox_events} table
 * through their own Flyway migrations.
 */
@AutoConfiguration(after = {KafkaAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
        TransactionAutoConfiguration.class})
@ConditionalOnProperty(name = "payflow.consistency.strategy", havingValue = "outbox")
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
public class OutboxAutoConfiguration {

    @Bean
    OutboxWriter outboxWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new OutboxWriter(jdbcTemplate, objectMapper);
    }

    @Bean
    OutboxRelay outboxRelay(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
                            ProducerFactory<?, ?> producerFactory, OutboxProperties properties,
                            MeterRegistry meterRegistry) {
        return new OutboxRelay(jdbcTemplate, new TransactionTemplate(transactionManager), producerFactory,
                properties, meterRegistry);
    }
}
