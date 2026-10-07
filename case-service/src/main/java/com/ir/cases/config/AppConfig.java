package com.ir.cases.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.time.Clock;

@Configuration
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Disabled in slice tests via {@code ir.scheduling.enabled=false}. */
    @Configuration
    @EnableScheduling
    @EnableSchedulerLock(defaultLockAtMostFor = "PT1M")
    @ConditionalOnProperty(name = "ir.scheduling.enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
        @Bean
        LockProvider lockProvider(DataSource dataSource) {
            return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                    .withJdbcTemplate(new JdbcTemplate(dataSource))
                    .usingDbTime()
                    .build());
        }
    }
}
