package com.vms.workflow.infrastructure;

import com.vms.workflow.application.BusinessConfirmationService;
import com.vms.workflow.application.CertificationEmailAdapter;
import com.vms.workflow.application.CertificationOperationsWorker;
import com.vms.workflow.application.CertificationSecurityEventService;
import com.vms.workflow.application.ConfirmationTokenHandoffVault;
import com.vms.workflow.application.F05CertificationReadinessPublisher;
import com.vms.workflow.application.FinanceCanonicalJson;
import com.vms.workflow.application.FinanceMalwareScanner;
import com.vms.workflow.application.FinanceMutationJournal;
import com.vms.workflow.application.FinanceOperationsWorker;
import com.vms.workflow.application.FinancePrivateStorageAdapter;
import com.vms.workflow.application.FinanceReportDataService;
import com.vms.workflow.application.FinanceReportRenderer;
import com.vms.workflow.application.MigrationMetrics;
import com.vms.workflow.application.MigrationRecoveryWorker;
import com.vms.workflow.application.MigrationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WorkerProfileConfigurationTest {
    private final YamlPropertySourceLoader yaml =
        new YamlPropertySourceLoader();

    @Test
    void eachWorkerProfileIsNonWebFlywayOffAndSinglePurpose()
        throws Exception {
        assertProfile(
            "application-worker-certification.yml",
            "vms_job_worker", true, false, false);
        assertProfile(
            "application-worker-finance.yml",
            "vms_job_worker", false, true, false);
        assertProfile(
            "application-worker-migration.yml",
            "vms_migration_processor", false, false, true);
    }

    @Test
    void nonWebContextDoesNotCreateServletSecurity() {
        new ApplicationContextRunner()
            .withUserConfiguration(
                com.vms.workflow.security.SecurityConfig.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(
                    com.vms.workflow.security.SecurityConfig.class);
                assertThat(context).doesNotHaveBean(SecurityFilterChain.class);
            });
    }

    @Test
    void enablingEachProfileCreatesExactlyOneWorkerBean() {
        workerContext()
            .withPropertyValues(
                "vms.certification.worker-enabled=true",
                "vms.finance.worker-enabled=false",
                "vms.migration.worker-enabled=false")
            .run(context -> assertWorkers(context, 1, 0, 0));
        workerContext()
            .withPropertyValues(
                "vms.certification.worker-enabled=false",
                "vms.finance.worker-enabled=true",
                "vms.migration.worker-enabled=false")
            .run(context -> assertWorkers(context, 0, 1, 0));
        workerContext()
            .withPropertyValues(
                "vms.certification.worker-enabled=false",
                "vms.finance.worker-enabled=false",
                "vms.migration.worker-enabled=true")
            .run(context -> assertWorkers(context, 0, 0, 1));
    }

    private void assertProfile(
        String resource,
        String capability,
        boolean certification,
        boolean finance,
        boolean migration
    ) throws Exception {
        PropertySource<?> source = load(resource);
        assertEquals("none",
            source.getProperty("spring.main.web-application-type"));
        assertEquals(false, source.getProperty("spring.flyway.enabled"));
        assertEquals(certification,
            source.getProperty("vms.certification.worker-enabled"));
        assertEquals(finance,
            source.getProperty("vms.finance.worker-enabled"));
        assertEquals(migration,
            source.getProperty("vms.migration.worker-enabled"));
        assertEquals(capability,
            source.getProperty("vms.database.expected-capability-role"));
    }

    private PropertySource<?> load(String resource) throws Exception {
        List<PropertySource<?>> sources = yaml.load(
            resource, new ClassPathResource(resource));
        return sources.getFirst();
    }

    private ApplicationContextRunner workerContext() {
        return new ApplicationContextRunner()
            .withUserConfiguration(WorkerComponents.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(
                CertificationEmailAdapter.class,
                () -> mock(CertificationEmailAdapter.class))
            .withBean(
                ConfirmationTokenHandoffVault.class,
                () -> mock(ConfirmationTokenHandoffVault.class))
            .withBean(
                F05CertificationReadinessPublisher.class,
                () -> mock(F05CertificationReadinessPublisher.class))
            .withBean(
                BusinessConfirmationService.class,
                () -> mock(BusinessConfirmationService.class))
            .withBean(
                CertificationSecurityEventService.class,
                () -> mock(CertificationSecurityEventService.class))
            .withBean(
                TransactionTemplate.class,
                () -> mock(TransactionTemplate.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(
                FinanceCanonicalJson.class,
                () -> mock(FinanceCanonicalJson.class))
            .withBean(
                FinanceReportDataService.class,
                () -> mock(FinanceReportDataService.class))
            .withBean(
                FinanceReportRenderer.class,
                () -> mock(FinanceReportRenderer.class))
            .withBean(
                FinancePrivateStorageAdapter.class,
                () -> mock(FinancePrivateStorageAdapter.class))
            .withBean(
                FinanceMalwareScanner.class,
                () -> mock(FinanceMalwareScanner.class))
            .withBean(
                FinanceMutationJournal.class,
                () -> mock(FinanceMutationJournal.class))
            .withBean(MigrationService.class, () -> mock(MigrationService.class))
            .withBean(MigrationMetrics.class, () -> mock(MigrationMetrics.class));
    }

    private void assertWorkers(
        org.springframework.context.ApplicationContext context,
        int certification,
        int finance,
        int migration
    ) {
        assertThat(context.getBeansOfType(CertificationOperationsWorker.class))
            .hasSize(certification);
        assertThat(context.getBeansOfType(FinanceOperationsWorker.class))
            .hasSize(finance);
        assertThat(context.getBeansOfType(MigrationRecoveryWorker.class))
            .hasSize(migration);
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
        CertificationOperationsWorker.class,
        FinanceOperationsWorker.class,
        MigrationRecoveryWorker.class
    })
    static class WorkerComponents {
    }
}
