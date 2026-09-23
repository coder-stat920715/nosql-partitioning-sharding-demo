package com.publicissapient.partitioning.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Dedicated thread pool for scatter-gather (cross-shard) query execution.
 *
 * Scatter-gather queries fan a single logical request out into N parallel
 * sub-queries (one per shard/collection). Using a bounded, named executor
 * keeps this workload isolated from the default Tomcat / WebFlux worker
 * pool and makes the fan-out behaviour observable in thread dumps.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "scatterGatherExecutor")
    public Executor scatterGatherExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("scatter-gather-");
        executor.initialize();
        return executor;
    }
}
