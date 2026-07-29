package com.vms.workflow.integration.support;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.JdbcDatabaseContainerProvider;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;

/**
 * Testcontainers' PostgreSQL log wait has a fixed one-minute budget. PostgreSQL
 * 18 enables data checksums during initdb and can legitimately need longer on a
 * constrained Docker Desktop VM. Integration data is disposable, so the
 * provider mounts PostgreSQL 18's data root on a bounded tmpfs and retains the
 * image's two-phase ready log plus an explicit startup budget. Production
 * storage and migration semantics are unaffected.
 */
public final class VmsPostgreSqlContainerProvider
    extends JdbcDatabaseContainerProvider {

    private static final String DATABASE_TYPE = "vmspostgresql";
    private static final String DEFAULT_TAG = "18-alpine";
    private static final DockerImageName POSTGRES_IMAGE =
        DockerImageName.parse(
            "cgr.dev/chainguard/postgres@sha256:"
                + "dc2f04037c1044a22af76cee4de70b9111885b17c561b93"
                + "9d7ed70103d100759")
            .asCompatibleSubstituteFor("postgres");
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);
    public static final String POSTGRES_DATA_DIRECTORY =
        "/var/lib/postgresql";
    public static final String POSTGRES_TMPFS_OPTIONS =
        "rw,nosuid,nodev,size=1g";

    @Override
    public boolean supports(String databaseType) {
        return DATABASE_TYPE.equals(databaseType);
    }

    @Override
    public JdbcDatabaseContainer<?> newInstance() {
        return newInstance(DEFAULT_TAG);
    }

    @Override
    public JdbcDatabaseContainer<?> newInstance(String tag) {
        if (!DEFAULT_TAG.equals(tag)) {
            throw new IllegalArgumentException(
                "Unsupported test PostgreSQL image tag: " + tag);
        }
        PostgreSQLContainer<?> container =
            new PostgreSQLContainer<>(POSTGRES_IMAGE);
        // The Chainguard image's immutable entrypoint already includes
        // `postgres`; replace Testcontainers' default `postgres -c ...`
        // command with only the server arguments.
        container.withCommand("-c", "fsync=off");
        container.withTmpFs(Map.of(
            POSTGRES_DATA_DIRECTORY, POSTGRES_TMPFS_OPTIONS));
        container.waitingFor(
            Wait.forLogMessage(
                    ".*database system is ready to accept connections.*\\s", 2)
                .withStartupTimeout(STARTUP_TIMEOUT));
        return container;
    }
}
