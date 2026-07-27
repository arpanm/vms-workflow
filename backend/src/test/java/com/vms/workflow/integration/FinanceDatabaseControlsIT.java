package com.vms.workflow.integration;

import com.vms.workflow.application.FinanceCanonicalJson;
import com.vms.workflow.application.PostgresFinancePrivateStorageAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.vms.workflow.integration.F04TestSupport.MONTH;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:postgresql:18-alpine:///vms_workflow",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api",
    "vms.finance.worker-initial-delay=PT1H"
})
@Transactional
class FinanceDatabaseControlsIT {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PostgresFinancePrivateStorageAdapter storage;

    @Autowired
    private FinanceCanonicalJson canonical;

    @Test
    void privateBytesRoundTripWithPersistedHash() {
        UUID artifactId = UUID.randomUUID();
        byte[] content = "%PDF-1.7\nprivate deterministic bytes\n%%EOF"
            .getBytes(StandardCharsets.UTF_8);
        String hash = canonical.sha256Bytes(content);
        insertArtifact(artifactId, MONTH, hash, content.length);
        storage.store(artifactId, content);

        assertArrayEquals(content, storage.read(artifactId));
        assertEquals(hash, jdbc.queryForObject("""
            SELECT content_hash FROM f05_private_artifacts WHERE id = ?
            """, String.class, artifactId));
        assertEquals(hash, canonical.sha256Bytes(storage.read(artifactId)));
    }

    @Test
    void privateArtifactMetadataIsImmutable() {
        UUID artifactId = UUID.randomUUID();
        byte[] content = "immutable metadata".getBytes(StandardCharsets.UTF_8);
        insertArtifact(
            artifactId, MONTH, canonical.sha256Bytes(content), content.length);

        assertThrows(DataAccessException.class, () -> jdbc.update("""
            UPDATE f05_private_artifacts
            SET content_hash = repeat('f', 64)
            WHERE id = ?
            """, artifactId));
    }

    @Test
    void privateArtifactBlobIsImmutable() {
        UUID artifactId = UUID.randomUUID();
        byte[] content = "immutable private blob".getBytes(StandardCharsets.UTF_8);
        insertArtifact(
            artifactId, MONTH, canonical.sha256Bytes(content), content.length);
        storage.store(artifactId, content);

        assertThrows(DataAccessException.class, () -> jdbc.update("""
            UPDATE f05_private_artifact_blobs
            SET content = decode('00', 'hex')
            WHERE artifact_id = ?
            """, artifactId));
    }

    @Test
    void directSqlCannotChangeLegalHoldWithoutAuthorizedTransitionLedger() {
        UUID artifactId = UUID.randomUUID();
        byte[] content = "legal hold protected".getBytes(StandardCharsets.UTF_8);
        insertArtifact(
            artifactId, MONTH, canonical.sha256Bytes(content), content.length);

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
            UPDATE f05_private_artifacts
            SET legal_hold = TRUE
            WHERE id = ?
            """, artifactId));
    }

    @Test
    void directSqlCannotRewriteTerminalScannerStateOrForensics() {
        UUID artifactId = UUID.randomUUID();
        byte[] content = "scanner state protected".getBytes(StandardCharsets.UTF_8);
        insertArtifact(
            artifactId, MONTH, canonical.sha256Bytes(content), content.length);

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
            UPDATE f05_private_artifacts
            SET scan_status = 'FAILED',
                scan_engine = 'UNAUTHORIZED',
                scan_reason_code = 'REWRITE',
                scanned_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, artifactId));
    }

    @Test
    void directSqlCannotRewriteScannerForensicsWithoutAStateTransition() {
        UUID artifactId = UUID.randomUUID();
        byte[] content = "scanner forensics protected"
            .getBytes(StandardCharsets.UTF_8);
        insertArtifact(
            artifactId, MONTH, canonical.sha256Bytes(content), content.length);

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
            UPDATE f05_private_artifacts
            SET scan_engine = 'UNAUTHORIZED'
            WHERE id = ?
            """, artifactId));
    }

    @Test
    void crossMonthInvoiceArtifactLinkIsRejectedAtomically() {
        UUID artifactId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        insertArtifact(
            artifactId, "00000000-0000-0000-0000-000000000603",
            "a".repeat(64), 12);
        jdbc.update("""
            INSERT INTO invoices(
                id, engagement_month_id, vendor_organization_id,
                invoice_type, invoice_number, normalized_invoice_number,
                invoice_date, billing_period_start, billing_period_end,
                currency, status, created_by_subject, correlation_id
            ) VALUES (?, ?::uuid,
                      '00000000-0000-0000-0000-000000000101',
                      'PRIMARY', ?, ?, '2026-07-31', '2026-07-01',
                      '2026-07-31', 'INR', 'DRAFT', 'user-arrow', ?)
            """, invoiceId, MONTH, "X-" + invoiceId,
            "X-" + invoiceId, UUID.randomUUID());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
            INSERT INTO invoice_versions(
                id, invoice_id, version, document_artifact_id,
                metadata_manifest, metadata_hash, source,
                created_by_subject, correlation_id
            ) VALUES (?, ?, 1, ?, '{}'::jsonb, repeat('b', 64),
                      'TEST', 'user-arrow', ?)
            """, UUID.randomUUID(), invoiceId, artifactId,
            UUID.randomUUID()));
    }

    private void insertArtifact(
        UUID artifactId, String monthId, String hash, int byteSize
    ) {
        jdbc.update("""
            INSERT INTO f05_private_artifacts(
                id, engagement_month_id, owner_organization_id, logical_type,
                safe_name, media_type, byte_size, content_hash, object_key,
                object_version, classification, retention_class, scan_status,
                scan_engine, scanned_at, provider_status, source,
                uploaded_by_subject, correlation_id
            ) VALUES (?, ?::uuid,
                      '00000000-0000-0000-0000-000000000101',
                      'INVOICE_DOCUMENT', 'fixture.pdf', 'application/pdf',
                      ?, ?, ?, 'fixture-v1', 'CONFIDENTIAL',
                      'FINANCE_STANDARD', 'PASSED', 'TEST_SCANNER',
                      CURRENT_TIMESTAMP, 'CONFIGURED', 'TEST',
                      'user-arrow', ?)
            """, artifactId, monthId, byteSize, hash,
            "test/" + artifactId, UUID.randomUUID());
    }
}
