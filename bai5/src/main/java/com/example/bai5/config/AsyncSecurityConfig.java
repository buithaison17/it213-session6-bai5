package com.example.bai5.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;

@Configuration
@EnableAsync
public class AsyncSecurityConfig {
    @Bean(name = "auditTaskExecutor")
    public AsyncTaskExecutor auditTaskExecutor() {
        ThreadPoolTaskExecutor delegateExecutor = new ThreadPoolTaskExecutor();
        // Cấu hình kích thước Thread Pool tối ưu
        delegateExecutor.setCorePoolSize(5);
        delegateExecutor.setMaxPoolSize(10);
        delegateExecutor.setQueueCapacity(100);
        delegateExecutor.setThreadNamePrefix("AuditThread-");
        delegateExecutor.initialize();

        // Bọc executor để tự động propagate SecurityContext an toàn sang các Worker Thread
        return new DelegatingSecurityContextAsyncTaskExecutor(delegateExecutor);
    }
}
