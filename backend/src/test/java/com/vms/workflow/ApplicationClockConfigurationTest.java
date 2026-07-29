package com.vms.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationClockConfigurationTest {
    private static final Instant FIXED =
        Instant.parse("2026-07-29T10:00:00Z");

    private final ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withUserConfiguration(ApplicationClockConfiguration.class)
            .withPropertyValues("vms.clock.fixed-instant=" + FIXED);

    @Test
    void prodIgnoresFixedClockPropertyAndUsesLiveUtcClock() {
        runner.withPropertyValues("spring.profiles.active=prod")
            .run(context -> {
                assertThat(context).hasSingleBean(Clock.class);
                assertThat(context.getBean(Clock.class).instant())
                    .isNotEqualTo(FIXED)
                    .isBetween(
                        Instant.now().minusSeconds(5),
                        Instant.now().plusSeconds(5));
            });
    }

    @Test
    void explicitSystemE2eProfileUsesRequiredFixedInstant() {
        runner.withPropertyValues("spring.profiles.active=system-e2e")
            .run(context -> {
                assertThat(context).hasSingleBean(Clock.class);
                assertThat(context.getBean(Clock.class).instant())
                    .isEqualTo(FIXED);
            });
    }

    @Test
    void prodWinsWhenSystemE2eIsAccidentallyAlsoActive() {
        runner.withPropertyValues("spring.profiles.active=prod,system-e2e")
            .run(context -> {
                assertThat(context).hasSingleBean(Clock.class);
                assertThat(context.getBean(Clock.class).instant())
                    .isNotEqualTo(FIXED)
                    .isBetween(
                        Instant.now().minusSeconds(5),
                        Instant.now().plusSeconds(5));
            });
    }
}
