package com.vms.workflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

@Configuration(proxyBeanMethods = false)
public class ApplicationClockConfiguration {
    @Bean
    @Profile("!system-e2e | prod")
    Clock applicationClock() {
        return Clock.systemUTC();
    }

    @Bean
    @Profile("system-e2e & !prod")
    Clock systemE2eClock(
        @Value("${vms.clock.fixed-instant}") String fixedInstant
    ) {
        return Clock.fixed(Instant.parse(fixedInstant), ZoneOffset.UTC);
    }
}
