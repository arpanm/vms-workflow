package com.vms.workflow.integration.support;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.jdbc.ConnectionUrl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class VmsPostgreSqlContainerProviderTest {

    private final VmsPostgreSqlContainerProvider provider =
        new VmsPostgreSqlContainerProvider();

    @Test
    void customJdbcTypeParsesAndSelectsOnlyTheVmsProvider() {
        ConnectionUrl url = ConnectionUrl.newInstance(
            "jdbc:tc:vmspostgresql:18-alpine:///workflow_test");

        assertThat(url.getDatabaseType()).isEqualTo("vmspostgresql");
        assertThat(provider.supports(url.getDatabaseType())).isTrue();
    }

    @Test
    void unapprovedDatabaseTypesAndTagsFailClosed() {
        assertThat(provider.supports("postgresql")).isFalse();
        assertThatThrownBy(() -> provider.newInstance("latest"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported test PostgreSQL image tag");
    }

    @Test
    void postgresDataUsesBoundedEphemeralTmpfs() {
        JdbcDatabaseContainer<?> container = provider.newInstance();

        assertThat(container.getTmpFsMapping()).containsExactly(
            entry(
                VmsPostgreSqlContainerProvider.POSTGRES_DATA_DIRECTORY,
                VmsPostgreSqlContainerProvider.POSTGRES_TMPFS_OPTIONS));
    }
}
