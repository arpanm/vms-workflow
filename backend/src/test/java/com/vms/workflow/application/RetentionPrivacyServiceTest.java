package com.vms.workflow.application;

import com.vms.workflow.api.RetentionDtos.ScheduleInput;
import com.vms.workflow.infrastructure.AuthorizationStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetentionPrivacyServiceTest {
    @Test
    void rejectsRetryConfigurationOutsideBoundedRange() {
        assertThrows(IllegalArgumentException.class,
            () -> service(false, 0, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class,
            () -> service(false, 3, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
            () -> service(false, 3, Duration.ofDays(2)));
    }

    @Test
    void rejectsUnregisteredRecordClassInsteadOfInventingPolicy() {
        AuthorizationStore authorization = mock(AuthorizationStore.class);
        when(authorization.hasActivePrincipal(anyString(), any()))
            .thenReturn(true);
        when(authorization.hasOrganizationPermission(
            anyString(), any(), anyString(), any())).thenReturn(true);
        RetentionPrivacyService service = new RetentionPrivacyService(
            mock(JdbcTemplate.class), authorization,
            mock(FinanceMutationJournal.class),
            mock(FinanceCanonicalJson.class),
            mock(FinanceRetentionWorker.class), Clock.systemUTC(),
            mock(PlatformTransactionManager.class),
            true, 3, Duration.ofMinutes(5), Duration.ofMinutes(30));

        assertThrows(IllegalArgumentException.class, () ->
            service.createSchedule(
                "actor",
                new ScheduleInput(
                    UUID.randomUUID(), "EMPLOYEE_RECORDS", 365,
                    "synthetic-policy-reference", OffsetDateTime.now()),
                "idempotency-key"));
    }

    @Test
    void candidateEffectsUseIndependentAtomicTransactions() {
        RetentionPrivacyService service = service(
            true, 3, Duration.ofMinutes(5));
        TransactionTemplate transactions = (TransactionTemplate)
            ReflectionTestUtils.getField(service, "candidateTransactions");

        org.junit.jupiter.api.Assertions.assertEquals(
            TransactionDefinition.PROPAGATION_REQUIRES_NEW,
            transactions.getPropagationBehavior());
    }

    private RetentionPrivacyService service(
        boolean dualControl, int attempts, Duration delay
    ) {
        return new RetentionPrivacyService(
            mock(JdbcTemplate.class), mock(AuthorizationStore.class),
            mock(FinanceMutationJournal.class),
            mock(FinanceCanonicalJson.class),
            mock(FinanceRetentionWorker.class), Clock.systemUTC(),
            mock(PlatformTransactionManager.class),
            dualControl, attempts, delay, Duration.ofMinutes(30));
    }
}
