package com.payflow.outbox;

import com.payflow.events.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Declares every saga topic (broker auto-creation is off) and the {@link EventPublisher} of the naive strategy.
 * The outbox publisher comes from {@link OutboxAutoConfiguration}.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
public class MessagingAutoConfiguration {

    @Bean
    KafkaAdmin.NewTopics payflowTopics() {
        return new KafkaAdmin.NewTopics(Topics.ALL.stream()
                .map(name -> TopicBuilder.name(name).partitions(Topics.PARTITIONS).replicas(1).build())
                .toArray(NewTopic[]::new));
    }

    @Bean
    @ConditionalOnProperty(name = "payflow.consistency.strategy", havingValue = "naive")
    EventPublisher naiveEventPublisher(KafkaTemplate<Object, Object> kafkaTemplate, MeterRegistry meterRegistry) {
        return new NaiveEventPublisher(kafkaTemplate, meterRegistry);
    }
}
