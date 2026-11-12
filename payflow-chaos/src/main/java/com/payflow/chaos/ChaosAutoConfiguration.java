package com.payflow.chaos;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * The {@link CrashPoint} bean always exists so that services can call it unconditionally; it does nothing unless
 * {@code payflow.chaos.enabled=true}. The HTTP endpoint is only registered when enabled.
 */
@AutoConfiguration
public class ChaosAutoConfiguration {

    @Bean
    CrashPoint crashPoint(@Value("${payflow.chaos.enabled:false}") boolean enabled) {
        return new CrashPoint(enabled);
    }

    @Bean
    @ConditionalOnProperty(name = "payflow.chaos.enabled", havingValue = "true")
    ChaosController chaosController(CrashPoint crashPoint) {
        return new ChaosController(crashPoint);
    }
}
