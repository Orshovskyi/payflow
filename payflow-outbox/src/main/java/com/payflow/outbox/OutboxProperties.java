package com.payflow.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param pollInterval delay between relay runs; directly adds to end-to-end latency, so it is an experiment parameter
 * @param batchSize    maximum number of events published per relay transaction
 * @param sendTimeout  how long the relay waits for broker acknowledgements before rolling back and retrying
 * @param retention    how long published rows are kept before cleanup
 */
@ConfigurationProperties(prefix = "payflow.outbox")
public record OutboxProperties(Duration pollInterval, Integer batchSize, Duration sendTimeout, Duration retention) {

    public OutboxProperties {
        pollInterval = pollInterval != null ? pollInterval : Duration.ofMillis(50);
        batchSize = batchSize != null ? batchSize : 100;
        sendTimeout = sendTimeout != null ? sendTimeout : Duration.ofSeconds(10);
        retention = retention != null ? retention : Duration.ofHours(1);
    }
}
