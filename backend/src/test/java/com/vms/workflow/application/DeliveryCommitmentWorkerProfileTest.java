package com.vms.workflow.application;

import com.vms.workflow.infrastructure.OperationalReadinessMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DeliveryCommitmentWorkerProfileTest {
    @Test
    void dedicatedWorkerIsNonWebSinglePurposeAndLeastPrivilegeVerified()
        throws Exception {
        PropertySource<?> profile = load(
            "application-worker-delivery-commitment.yml");
        assertThat(profile.getProperty("spring.main.web-application-type"))
            .isEqualTo("none");
        assertThat(profile.getProperty("spring.flyway.enabled"))
            .isEqualTo(false);
        assertThat(profile.getProperty(
            "vms.delivery.commitment.worker-enabled")).isEqualTo(true);
        assertThat(profile.getProperty("vms.certification.worker-enabled"))
            .isEqualTo(false);
        assertThat(profile.getProperty("vms.finance.worker-enabled"))
            .isEqualTo(false);
        assertThat(profile.getProperty("vms.migration.worker-enabled"))
            .isEqualTo(false);
        assertThat(profile.getProperty(
            "vms.database.verify-runtime-least-privilege")).isEqualTo(true);
        assertThat(profile.getProperty(
            "vms.database.expected-capability-role"))
            .isEqualTo("vms_job_worker");
    }

    @Test
    void productionApiCannotClaimCommitmentsAndDeploymentUsesDistinctSecret()
        throws Exception {
        PropertySource<?> production = load("application-prod.yml");
        assertThat(production.getProperty(
            "vms.delivery.commitment.worker-enabled")).isEqualTo(false);

        Path deployment = Path.of("deploy/f07-workers.yaml");
        if (!Files.exists(deployment)) {
            deployment = Path.of("backend/deploy/f07-workers.yaml");
        }
        String manifest = Files.readString(deployment);
        assertThat(manifest)
            .contains("name: vms-delivery-commitment-worker")
            .contains("--spring.profiles.active=worker-delivery-commitment")
            .contains("name: vms-delivery-commitment-worker-database")
            .contains("automountServiceAccountToken: false")
            .contains("readOnlyRootFilesystem: true");
    }

    @Test
    void providerNeutralBoundaryNeverAdvertisesConfiguredTransport() {
        ProviderNeutralDeliveryCommitmentEmailAdapter configured =
            new ProviderNeutralDeliveryCommitmentEmailAdapter(
                new DeliveryCommitmentConfiguration(
                    "CONFIGURED", Duration.ofSeconds(1)));
        assertThat(configured.configurationStatus())
            .isEqualTo("NOT_CONFIGURED");
        assertThat(configured.send(null)).extracting(
            DeliveryCommitmentEmailAdapter.SendResult::status,
            DeliveryCommitmentEmailAdapter.SendResult::errorCode)
            .containsExactly(
                "NOT_CONFIGURED", "COMMITMENT_PROVIDER_NOT_CONFIGURED");

        ProviderNeutralDeliveryCommitmentEmailAdapter actionRequired =
            new ProviderNeutralDeliveryCommitmentEmailAdapter(
                new DeliveryCommitmentConfiguration(
                    "ACTION_REQUIRED", Duration.ofSeconds(1)));
        assertThat(actionRequired.configurationStatus())
            .isEqualTo("ACTION_REQUIRED");
    }

    @Test
    void nonWebWorkerDoesNotRegisterServletOperationalSqlGauges() {
        new ApplicationContextRunner()
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(OperationalReadinessMetrics.class)
            .run(context -> assertThat(context)
                .doesNotHaveBean(OperationalReadinessMetrics.class));
    }

    private PropertySource<?> load(String name) throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
            .load(name, new ClassPathResource(name));
        assertThat(sources).hasSize(1);
        return sources.getFirst();
    }
}
