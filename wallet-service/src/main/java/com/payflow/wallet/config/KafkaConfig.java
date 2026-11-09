package com.payflow.wallet.config;

import com.payflow.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    @Bean
    NewTopic transferRequestedTopic() {
        return TopicBuilder.name(Topics.TRANSFER_REQUESTED).partitions(Topics.PARTITIONS).replicas(1).build();
    }

    @Bean
    NewTopic transferResultTopic() {
        return TopicBuilder.name(Topics.TRANSFER_RESULT).partitions(Topics.PARTITIONS).replicas(1).build();
    }

    /** Transient failures (e.g. optimistic lock conflicts) are retried by re-delivering the record. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        var backOff = new ExponentialBackOff(20, 2.0);
        backOff.setMaxInterval(1_000);
        // When retries are exhausted the record is logged and skipped: in the experiment that counts as a lost transfer.
        backOff.setMaxElapsedTime(30_000);
        return new DefaultErrorHandler(backOff);
    }
}
