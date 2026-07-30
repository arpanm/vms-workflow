package com.vms.workflow.application;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Transactional private-storage implementation for local and test operation.
 *
 * <p>Production can replace this bean with an approved object-store adapter
 * without changing finance services, authorization, hashes or metadata.</p>
 */
@Component
public class PostgresFinancePrivateStorageAdapter
    implements FinancePrivateStorageAdapter {

    private final JdbcTemplate jdbc;

    public PostgresFinancePrivateStorageAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String configurationStatus() {
        return "CONFIGURED";
    }

    @Override
    public void store(UUID artifactId, byte[] content) {
        jdbc.update("""
            INSERT INTO f05_private_artifact_blobs(artifact_id, content)
            VALUES (?, ?)
            """, artifactId, content.clone());
    }

    @Override
    public byte[] read(UUID artifactId) {
        byte[] value = jdbc.query("""
            SELECT content FROM f05_private_artifact_blobs
            WHERE artifact_id = ?
            """, rs -> rs.next() ? rs.getBytes(1) : null, artifactId);
        if (value == null) {
            throw new EntityNotFoundException("Private artifact not found.");
        }
        return value;
    }

    @Override
    public void delete(UUID artifactId) {
        if (jdbc.update("""
            DELETE FROM f05_private_artifact_blobs
            WHERE artifact_id = ?
            """, artifactId) != 1) {
            throw new EntityNotFoundException("Private artifact not found.");
        }
    }

    @Override
    public boolean transactionalDeleteSupported() {
        return true;
    }
}
