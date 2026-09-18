package com.diversive.school.platform.time;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The school's clock. "Overdue" means past its due date in the school's own time zone, so every "today"
 * comes from this bean, never from {@code LocalDate.now()}. The agent gateway uses it for token expiry too.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    @Bean
    Clock clock(@Value("${school.time-zone:Asia/Karachi}") ZoneId timeZone) {
        return Clock.system(timeZone);
    }
}
