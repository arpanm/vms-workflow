package com.vms.workflow.application;

import com.vms.workflow.api.DomainConflictException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AttendanceServiceClockTest {
    private static final OffsetDateTime CHECK_IN =
        OffsetDateTime.parse("2026-07-29T10:00:00Z");

    @Test
    void equalResolutionCheckoutBecomesOneMillisecondLater() {
        assertEquals(
            CHECK_IN.plusNanos(1_000_000),
            AttendanceService.normalizeCheckoutInstant(CHECK_IN, CHECK_IN));
    }

    @Test
    void laterCheckoutPreservesObservedEvidenceInstant() {
        OffsetDateTime observed = CHECK_IN.plusMinutes(1);
        assertEquals(
            observed,
            AttendanceService.normalizeCheckoutInstant(CHECK_IN, observed));
    }

    @Test
    void genuinelyRegressedClockIsRejected() {
        assertThrows(
            DomainConflictException.class,
            () -> AttendanceService.normalizeCheckoutInstant(
                CHECK_IN, CHECK_IN.minusNanos(1)));
    }
}
