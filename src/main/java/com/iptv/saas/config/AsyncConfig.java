package com.iptv.saas.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {
    @Bean(name = "mailTaskExecutor")
    Executor mailTaskExecutor(
            @Value("${app.mail.async.core-pool-size:2}") int corePoolSize,
            @Value("${app.mail.async.max-pool-size:4}") int maxPoolSize,
            @Value("${app.mail.async.queue-capacity:100}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("mail-");
        executor.initialize();
        return executor;
    }
}
