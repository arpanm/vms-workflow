package com.vms.workflow.application;

import com.vms.workflow.api.DomainConflictException;
import com.vms.workflow.security.FinanceAuthorizationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class FinancePackageService {
    private static final List<String> VIEW_PERMISSIONS = List.of(
        "EVIDENCE_PACKAGE_VIEW", "EVIDENCE_PACKAGE_DOWNLOAD");

    private final JdbcTemplate jdbc;
    private final FinanceCanonicalJson canonical;
    private final FinanceMutationJournal journal;
    private final FinanceAuthorizationService authorization;
    private final FinanceF04EvidenceResolver f04Evidence;
    private final FinancePrivateStorageAdapter storage;
    private final FinanceMalwareScanner scanner;
    private final FinanceReportRenderer renderer;
    private final FinancePolicyService policies;
    private final FinancePageCursorCodec cursors;

    public FinancePackageService(
        JdbcTemplate jdbc,
        FinanceCanonicalJson canonical,
        FinanceMutationJournal journal,
        FinanceAuthorizationService authorization,
        FinanceF04EvidenceResolver f04Evidence,
        FinancePrivateStorageAdapter storage,
        FinanceMalwareScanner scanner,
        FinanceReportRenderer renderer,
        FinancePolicyService policies,
        FinancePageCursorCodec cursors
    ) {
        this.jdbc = jdbc;
        this.canonical = canonical;
        this.journal = journal;
        this.authorization = authorization;
        this.f04Evidence = f04Evidence;
        this.storage = storage;
        this.scanner = scanner;
        this.renderer = renderer;
        this.policies = policies;
        this.cursors = cursors;
    }

    @Transactional
    public Map<String, Object> generate(
        String subject,
        UUID monthId,
        int expectedMonthVersion,
        UUID readinessRunId,
        String reason,
        String idempotencyKey
    ) {
        var scope = authorization.requireMonth(subject, monthId,
            "evidence.package.generate", FinanceAuthorizationService.Party.VENDOR);
        Map<String, Object> request = Map.of(
            "expectedMonthVersion", expectedMonthVersion,
            "readinessRunId", readinessRunId == null ? "" : readinessRunId.toString(),
            "reason", reason == null ? "" : reason);
        UUID replay = journal.replay(
            subject, "GENERATE_PACKAGE", monthId, idempotencyKey, request);
        if (replay != null) {
            return summary(subject, replay);
        }

        FinanceF04EvidenceResolver.HandoffEvidence source =
            f04Evidence.resolve(monthId);
        if (!source.readinessRunId().equals(readinessRunId)) {
            throw new DomainConflictException(
                "F04_READINESS_RUN_MISMATCH",
                "Package generation must reference the exact current F04 readiness run.");
        }
        FinancePolicyService.Policy policy =
            policies.active(scope.engagementId(), subject);
        InvoiceArtifact invoiceArtifact = currentPrimaryInvoiceArtifact(monthId);
        List<RelatedInvoiceArtifact> relatedInvoices =
            relatedInvoiceArtifacts(monthId, invoiceArtifact.invoiceId());

        Integer currentVersion = jdbc.queryForObject("""
            SELECT COALESCE(MAX(version), 0)
            FROM evidence_package_versions
            WHERE engagement_month_id = ?
            """, Integer.class, monthId);
        int nextVersion = (currentVersion == null ? 0 : currentVersion) + 1;
        if (expectedMonthVersion != nextVersion) {
            throw new DomainConflictException("FINANCE_MONTH_VERSION_MISMATCH",
                "The finance month changed; reload before generating a package.",
                (long) nextVersion);
        }

        List<Map<String, Object>> manifestItems =
            completeManifestItems(source, invoiceArtifact, relatedInvoices);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", "f05-evidence-manifest-v2");
        manifest.put("hashSchemaVersion", 2);
        manifest.put("engagementMonthId", monthId);
        manifest.put("handoffId", source.handoffId());
        manifest.put("handoffHash", source.handoffHash());
        manifest.put("handoffCreatedAt", source.handoffCreatedAt());
        manifest.put("contractVersion",
            FinanceF04EvidenceResolver.CONTRACT_VERSION);
        manifest.put("policyId", policy.id());
        manifest.put("policyVersion", policy.version());
        manifest.put("renderVersion", "manifest-v2");
        manifest.put("invoiceLineage", invoiceLineage(invoiceArtifact));
        manifest.put("relatedInvoiceDisclosures",
            relatedInvoices.stream().map(this::relatedInvoiceDisclosure).toList());
        manifest.put("source", source.handoffManifest());
        manifest.put("f04ReadinessInput", source.readinessManifest());
        manifest.put("items", manifestItems);
        manifest.put("readinessRunId", source.readinessRunId());
        String inputHash = canonical.sha256(manifest);

        UUID existing = jdbc.query("""
            SELECT id FROM evidence_package_versions
            WHERE engagement_month_id = ? AND canonical_input_hash = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            monthId, inputHash);
        if (existing != null) {
            journal.remember(subject, "GENERATE_PACKAGE", monthId,
                idempotencyKey, request, "EVIDENCE_PACKAGE", existing);
            return summary(subject, existing);
        }

        UUID prior = jdbc.query("""
            SELECT id FROM evidence_package_versions
            WHERE engagement_month_id = ? AND status = 'CURRENT'
            FOR UPDATE
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, monthId);
        if (prior != null) {
            jdbc.update("""
                UPDATE evidence_package_versions
                SET status = 'SUPERSEDED',
                    invalidation_reason = 'NEW_CANONICAL_SOURCE_VERSION'
                WHERE id = ?
                """, prior);
        }

        UUID packageId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO evidence_package_versions(
                id, engagement_month_id, handoff_id, policy_version_id,
                invoice_id, invoice_version, invoice_document_artifact_id,
                invoice_document_hash,
                version, status,
                canonical_manifest, canonical_input_hash, hash_schema_version,
                render_version, supersedes_id, generated_by_subject, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'CURRENT', ?::jsonb, ?, 2,
                      'manifest-v2', ?, ?, ?)
            """, packageId, monthId, source.handoffId(), policy.id(),
            invoiceArtifact.invoiceId(), invoiceArtifact.version(),
            invoiceArtifact.artifactId(), invoiceArtifact.hash(),
            nextVersion, canonical.write(manifest), inputHash, prior, subject,
            journal.correlationId());
        persistItems(packageId, manifestItems);

        byte[] outputBytes = canonical.write(manifest)
            .getBytes(StandardCharsets.UTF_8);
        persistPackageOutput(
            scope, packageId, nextVersion, "JSON", outputBytes,
            "application/json", "evidence-package-v" + nextVersion + ".json",
            subject);
        Map<String, Object> renderMetadata = new LinkedHashMap<>();
        renderMetadata.put("schema", "f05-evidence-package-render-v2");
        renderMetadata.put("packageVersion", nextVersion);
        renderMetadata.put("canonicalInputHash", inputHash);
        renderMetadata.put("sourceRecordedAt", source.handoffCreatedAt());
        renderMetadata.put("policyVersion", policy.version());
        renderMetadata.put("invoiceId", invoiceArtifact.invoiceId());
        renderMetadata.put("invoiceVersion", invoiceArtifact.version());
        renderMetadata.put("invoiceDocumentArtifactId",
            invoiceArtifact.artifactId());
        renderMetadata.put("invoiceDocumentHash", invoiceArtifact.hash());
        for (String format : List.of("PDF", "CSV", "XLSX")) {
            FinanceReportRenderer.RenderedReport rendered = renderer.render(
                "evidence-package", "v1", format, renderMetadata, manifestItems);
            persistPackageOutput(
                scope, packageId, nextVersion, format, rendered.content(),
                rendered.mediaType(), rendered.safeName(), subject);
        }
        f04Evidence.rememberConsumption(
            source, subject, journal.correlationId());

        journal.event(monthId, "f05.package.generated.v1", "EVIDENCE_PACKAGE",
            packageId, nextVersion, Map.of(
                "canonicalInputHash", inputHash,
                "handoffId", source.handoffId().toString()), subject);
        journal.audit(monthId, "PACKAGE_GENERATED", "EVIDENCE_PACKAGE",
            packageId, (long) nextVersion, "SUCCESS", reason, subject,
            authority(scope), List.of(Map.of(
                "sourceType", "F04_HANDOFF",
                "sourceId", source.handoffId().toString(),
                "sourceHash", source.handoffHash())));
        journal.remember(subject, "GENERATE_PACKAGE", monthId,
            idempotencyKey, request, "EVIDENCE_PACKAGE", packageId);
        return summary(subject, packageId);
    }

    private void persistPackageOutput(
        FinanceAuthorizationService.Scope scope,
        UUID packageId,
        int packageVersion,
        String format,
        byte[] outputBytes,
        String mediaType,
        String safeName,
        String subject
    ) {
        String outputHash = canonical.sha256Bytes(outputBytes);
        UUID outputArtifactId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO f05_private_artifacts(
                id, engagement_month_id, owner_organization_id, logical_type,
                safe_name, media_type, byte_size, content_hash, object_key,
                object_version, classification, retention_class,
                scan_status, provider_status, source, uploaded_by_subject,
                correlation_id
            ) VALUES (?, ?, ?, 'EVIDENCE_PACKAGE_OUTPUT', ?, ?,
                      ?, ?, ?, ?, 'CONFIDENTIAL', 'FINANCE_EVIDENCE',
                      'PENDING', 'CONFIGURED', 'SERVER_RENDER', ?, ?)
            """, outputArtifactId, scope.monthId(), scope.vendorOrganizationId(),
            safeName, mediaType, outputBytes.length,
            outputHash, "evidence-packages/" + scope.vendorOrganizationId()
                + "/" + scope.monthId() + "/" + packageId + "/"
                + safeName,
            "postgres-" + UUID.randomUUID(), subject, journal.correlationId());
        storage.store(outputArtifactId, outputBytes);
        FinanceMalwareScanner.ScanResult outputScan = scanner.scan(
            outputBytes, mediaType, safeName);
        jdbc.update("""
            UPDATE f05_private_artifacts
            SET scan_status = ?, scan_engine = ?, scan_reason_code = ?,
                scanned_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, outputScan.status(), outputScan.engine(),
            outputScan.reasonCode(), outputArtifactId);
        if (!"PASSED".equals(outputScan.status())) {
            throw new DomainConflictException(
                "PACKAGE_OUTPUT_SCAN_BLOCKED",
                "The generated package output did not pass content scanning.");
        }
        UUID outputId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO evidence_package_outputs(
                id, package_version_id, output_format, artifact_id, output_hash,
                renderer_status
            ) VALUES (?, ?, ?, ?, ?, 'RENDERED')
            """, outputId, packageId, format, outputArtifactId, outputHash);
    }

    public Map<String, Object> packageView(String subject, UUID packageId) {
        PackageRow row = row(packageId);
        authorization.requirePackageView(subject, packageId, row.monthId());
        Map<String, Object> result = new LinkedHashMap<>(summary(subject, packageId));
        result.put("engagementLabel", row.engagementLabel());
        result.put("monthLabel", row.monthStart().toLocalDateTime().toLocalDate().toString());
        result.put("provenanceDisclosure",
            "Generated from an immutable effective F04 certification handoff.");
        result.put("integrityVerified",
            canonical.sha256(readMap(row.manifest())).equals(row.inputHash()));
        List<Map<String, Object>> items = items(packageId);
        result.put("sources", items.stream().map(value ->
            castMap(value.get("source"))).toList());
        result.put("manifestItems", items);
        result.put("artifacts", artifacts(packageId));
        return result;
    }

    public Map<String, Object> diff(
        String subject,
        UUID packageId,
        UUID againstId
    ) {
        PackageRow from = row(againstId);
        PackageRow to = row(packageId);
        if (!from.monthId().equals(to.monthId())) {
            throw new DomainConflictException(
                "Packages must belong to the same finance month.");
        }
        authorization.requireMonth(subject, to.monthId(), "finance.read",
            FinanceAuthorizationService.Party.ANY);
        List<ItemKey> before = itemKeys(againstId);
        List<ItemKey> after = itemKeys(packageId);
        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> changed = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        for (ItemKey value : after) {
            ItemKey prior = before.stream()
                .filter(item -> item.logicalType().equals(value.logicalType())
                    && item.sourceId().equals(value.sourceId()))
                .findFirst().orElse(null);
            if (prior == null) {
                added.add(keyView(value));
            } else if (!prior.version().equals(value.version())) {
                changed.add(Map.of(
                    "logicalType", value.logicalType(),
                    "sourceId", value.sourceId(),
                    "fromVersion", prior.version(),
                    "toVersion", value.version()));
            }
        }
        for (ItemKey value : before) {
            boolean present = after.stream().anyMatch(item ->
                item.logicalType().equals(value.logicalType())
                    && item.sourceId().equals(value.sourceId()));
            if (!present) {
                removed.add(keyView(value));
            }
        }
        return Map.of(
            "fromPackageId", againstId,
            "toPackageId", packageId,
            "fromVersion", from.version(),
            "toVersion", to.version(),
            "added", added,
            "changed", changed,
            "removed", removed);
    }

    public List<Map<String, Object>> accessEvents(String subject, UUID packageId) {
        PackageRow row = row(packageId);
        authorization.requireMonth(subject, row.monthId(), "finance.audit.read",
            FinanceAuthorizationService.Party.ANY);
        return jdbc.query("""
            SELECT id, action, actor_subject, authority_snapshot::text,
                   recorded_at, correlation_id
            FROM f05_audit_events
            WHERE object_type = 'EVIDENCE_PACKAGE' AND object_id = ?
            ORDER BY recorded_at DESC
            """, (rs, index) -> {
                Map<String, Object> authority = readMap(rs.getString(4));
                return Map.of(
                    "accessId", rs.getObject(1, UUID.class),
                    "action", auditAction(rs.getString(2)),
                    "actorDisplay", rs.getString(3),
                    "authorityDisplay", String.valueOf(
                        authority.getOrDefault("permission", "scoped authority")),
                    "recordedAt", offset(rs.getTimestamp(5)),
                    "expiresAt", "",
                    "revokedAt", "",
                    "correlationId", rs.getObject(6, UUID.class));
            }, packageId);
    }

    public Map<String, Object> accessEvents(
        String subject,
        UUID packageId,
        String cursor
    ) {
        PackageRow packageRow = row(packageId);
        var scope = authorization.requireMonth(
            subject, packageRow.monthId(), "finance.audit.read",
            FinanceAuthorizationService.Party.ANY);
        List<UUID> engagements = List.of(scope.engagementId());
        String resource = "package-access-events:" + packageId;
        FinancePageCursorCodec.Cursor decoded = decodeCursor(
            cursor, resource, subject, engagements);
        Instant snapshotAt = databaseSnapshot(cursor, decoded);
        OffsetDateTime lastRecordedAt = decoded == null ? null
            : OffsetDateTime.parse(decoded.lastSortValue());
        Timestamp lastRecorded = lastRecordedAt == null ? null
            : Timestamp.from(lastRecordedAt.toInstant());
        UUID lastId = decoded == null ? null : decoded.lastId();
        List<Map<String, Object>> items = jdbc.query("""
            SELECT id, action, actor_subject, authority_snapshot::text,
                   recorded_at, correlation_id
            FROM f05_audit_events
            WHERE object_type = 'EVIDENCE_PACKAGE' AND object_id = ?
              AND recorded_at <= ?
              AND (
                  ?::timestamptz IS NULL
                  OR (recorded_at, id) < (?::timestamptz, ?::uuid)
              )
            ORDER BY recorded_at DESC, id DESC
            LIMIT 51
            """, (rs, index) -> {
                Map<String, Object> authority = readMap(rs.getString(4));
                return Map.of(
                    "accessId", rs.getObject(1, UUID.class),
                    "action", auditAction(rs.getString(2)),
                    "actorDisplay", rs.getString(3),
                    "authorityDisplay", String.valueOf(
                        authority.getOrDefault("permission", "scoped authority")),
                    "recordedAt", offset(rs.getTimestamp(5)),
                    "expiresAt", "",
                    "revokedAt", "",
                    "correlationId", rs.getObject(6, UUID.class));
            }, packageId, Timestamp.from(snapshotAt),
            lastRecorded, lastRecorded, lastId);
        Long total = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM f05_audit_events
            WHERE object_type = 'EVIDENCE_PACKAGE' AND object_id = ?
              AND recorded_at <= ?
            """, Long.class, packageId, Timestamp.from(snapshotAt));
        return page(items, total == null ? 0 : total,
            resource, subject, engagements, snapshotAt,
            "recordedAt", "accessId");
    }

    @Transactional
    public PackageDownloadResult download(
        String subject,
        UUID packageId,
        UUID artifactId
    ) {
        PackageRow row = row(packageId);
        var scope = authorization.requirePackageDownload(
            subject, packageId, row.monthId());
        Output output = jdbc.query("""
            SELECT output.output_format, output.output_hash,
                   output.renderer_status, artifact.scan_status,
                   artifact.id, artifact.media_type, artifact.safe_name
            FROM evidence_package_outputs output
            LEFT JOIN f05_private_artifacts artifact ON artifact.id = output.artifact_id
            WHERE output.package_version_id = ? AND output.id = ?
            """, rs -> rs.next() ? new Output(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7))
                : null, packageId, artifactId);
        if (output == null) {
            throw new EntityNotFoundException("Package artifact not found.");
        }
        if (!"PASSED".equals(output.scanStatus())
            || output.artifactId() == null) {
            throw new DomainConflictException(
                "ARTIFACT_SCAN_BLOCKED",
                "Artifact download is blocked until its scan passes.");
        }
        byte[] bytes = storage.read(output.artifactId());
        if (!canonical.sha256Bytes(bytes).equals(output.hash())) {
            throw new DomainConflictException("PACKAGE_INTEGRITY_FAILED",
                "Package artifact integrity verification failed.");
        }
        journal.audit(row.monthId(), "PACKAGE_DOWNLOADED", "EVIDENCE_PACKAGE",
            packageId, (long) row.version(), "SUCCESS", "AUTHORIZED_DOWNLOAD",
            subject, authority(scope), List.of());
        return new PackageDownloadResult(
            bytes, output.mediaType(), output.safeName());
    }

    public List<Map<String, Object>> history(String subject, UUID monthId) {
        authorization.requireMonth(subject, monthId, "finance.read",
            FinanceAuthorizationService.Party.ANY);
        return jdbc.query("""
            SELECT id FROM evidence_package_versions
            WHERE engagement_month_id = ?
            ORDER BY version DESC
            """, (rs, index) -> summary(subject, rs.getObject(1, UUID.class)), monthId);
    }

    public Map<String, Object> history(
        String subject,
        UUID monthId,
        String cursor
    ) {
        var scope = authorization.requireMonth(
            subject, monthId, "finance.read",
            FinanceAuthorizationService.Party.ANY);
        List<UUID> engagements = List.of(scope.engagementId());
        String resource = "package-history:" + monthId;
        FinancePageCursorCodec.Cursor decoded = decodeCursor(
            cursor, resource, subject, engagements);
        Instant snapshotAt = databaseSnapshot(cursor, decoded);
        Integer lastVersion = decoded == null ? null
            : Integer.valueOf(decoded.lastSortValue());
        UUID lastId = decoded == null ? null : decoded.lastId();
        List<Map<String, Object>> items = jdbc.query("""
            SELECT package.id, package.engagement_month_id, package.version,
                   package.status, package.canonical_input_hash,
                   package.render_version, package.supersedes_id,
                   package.canonical_manifest::text, package.generated_at,
                   engagement.name, month.month_start_date, policy.version,
                   package.invoice_id, package.invoice_version,
                   package.invoice_document_artifact_id,
                   package.invoice_document_hash
            FROM evidence_package_versions package
            JOIN engagement_months month
              ON month.id = package.engagement_month_id
            JOIN engagements engagement ON engagement.id = month.engagement_id
            JOIN f05_policy_versions policy
              ON policy.id = package.policy_version_id
            WHERE package.engagement_month_id = ?
              AND package.generated_at <= ?
              AND (
                  ?::integer IS NULL
                  OR (package.version, package.id)
                     < (?::integer, ?::uuid)
              )
            ORDER BY package.version DESC, package.id DESC
            LIMIT 51
            """, (rs, index) -> summaryMap(packageRow(rs)),
            monthId, Timestamp.from(snapshotAt),
            lastVersion, lastVersion, lastId);
        Long total = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM evidence_package_versions
            WHERE engagement_month_id = ?
              AND generated_at <= ?
            """, Long.class, monthId, Timestamp.from(snapshotAt));
        return page(items, total == null ? 0 : total,
            resource, subject, engagements, snapshotAt,
            "version", "packageId");
    }

    public Map<String, Object> summary(String subject, UUID packageId) {
        PackageRow row = row(packageId);
        authorization.requirePackageView(subject, packageId, row.monthId());
        return summaryMap(row);
    }

    @Transactional
    public Map<String, Object> createShare(
        String subject,
        UUID packageId,
        String recipientSubject,
        String accessScope,
        OffsetDateTime expiresAt,
        String reason,
        String idempotencyKey
    ) {
        PackageRow row = row(packageId);
        var authorityScope = authorization.requireMonth(
            subject, row.monthId(), "evidence.package.download",
            FinanceAuthorizationService.Party.ANY);
        String normalizedScope = accessScope == null
            ? "" : accessScope.strip().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("VIEW", "DOWNLOAD").contains(normalizedScope)
            || recipientSubject == null || recipientSubject.isBlank()
            || expiresAt == null
            || !expiresAt.isAfter(OffsetDateTime.now())) {
            throw new IllegalArgumentException(
                "Recipient, VIEW/DOWNLOAD scope and a future expiry are required.");
        }
        Boolean activeRecipient = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM user_profiles
                WHERE identity_subject = ? AND status = 'ACTIVE'
            )
            """, Boolean.class, recipientSubject);
        if (!Boolean.TRUE.equals(activeRecipient)) {
            throw new DomainConflictException(
                "ACTIVE_SHARE_RECIPIENT_REQUIRED",
                "The package recipient must be an active authenticated identity.");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("recipientSubject", recipientSubject);
        request.put("accessScope", normalizedScope);
        request.put("expiresAt", expiresAt);
        request.put("reason", reason);
        UUID replay = journal.replay(
            subject, "PACKAGE_SHARE", packageId, idempotencyKey, request);
        if (replay != null) {
            return shareView(replay);
        }
        UUID shareId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO evidence_package_shares(
                id, package_version_id, recipient_subject, access_scope,
                expires_at, created_by_subject, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """, shareId, packageId, recipientSubject, normalizedScope,
            Timestamp.from(expiresAt.toInstant()), subject,
            journal.correlationId());
        journal.audit(row.monthId(), "PACKAGE_SHARED", "EVIDENCE_PACKAGE",
            packageId, (long) row.version(), "SUCCESS",
            reason, subject, authority(authorityScope),
            List.of(Map.of("shareId", shareId,
                "recipientSubject", recipientSubject,
                "accessScope", normalizedScope)));
        journal.event(row.monthId(), "f05.package.shared.v1",
            "PACKAGE_SHARE", shareId, 1,
            Map.of("shareId", shareId, "accessScope", normalizedScope,
                "expiresAt", expiresAt), subject);
        journal.remember(subject, "PACKAGE_SHARE", packageId,
            idempotencyKey, request, "PACKAGE_SHARE", shareId);
        return shareView(shareId);
    }

    public List<Map<String, Object>> shares(String subject, UUID packageId) {
        PackageRow row = row(packageId);
        authorization.requireMonth(subject, row.monthId(), "finance.audit.read",
            FinanceAuthorizationService.Party.ANY);
        return jdbc.query("""
            SELECT id FROM evidence_package_shares
            WHERE package_version_id = ?
            ORDER BY created_at DESC, id
            """, (rs, index) -> shareView(rs.getObject(1, UUID.class)),
            packageId);
    }

    public Map<String, Object> shares(
        String subject,
        UUID packageId,
        String cursor
    ) {
        PackageRow packageRow = row(packageId);
        var scope = authorization.requireMonth(
            subject, packageRow.monthId(), "finance.audit.read",
            FinanceAuthorizationService.Party.ANY);
        List<UUID> engagements = List.of(scope.engagementId());
        String resource = "package-shares:" + packageId;
        FinancePageCursorCodec.Cursor decoded = decodeCursor(
            cursor, resource, subject, engagements);
        Instant snapshotAt = databaseSnapshot(cursor, decoded);
        OffsetDateTime lastCreatedAt = decoded == null ? null
            : OffsetDateTime.parse(decoded.lastSortValue());
        Timestamp lastCreated = lastCreatedAt == null ? null
            : Timestamp.from(lastCreatedAt.toInstant());
        UUID lastId = decoded == null ? null : decoded.lastId();
        List<Map<String, Object>> items = jdbc.query("""
            SELECT id, package_version_id, recipient_subject, access_scope,
                   expires_at, revoked_at, created_by_subject, created_at,
                   correlation_id
            FROM evidence_package_shares
            WHERE package_version_id = ?
              AND created_at <= ?
              AND (
                  ?::timestamptz IS NULL
                  OR (created_at, id) < (?::timestamptz, ?::uuid)
              )
            ORDER BY created_at DESC, id DESC
            LIMIT 51
            """, (rs, index) -> shareMap(rs),
            packageId, Timestamp.from(snapshotAt),
            lastCreated, lastCreated, lastId);
        Long total = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM evidence_package_shares
            WHERE package_version_id = ?
              AND created_at <= ?
            """, Long.class, packageId, Timestamp.from(snapshotAt));
        return page(items, total == null ? 0 : total,
            resource, subject, engagements, snapshotAt,
            "createdAt", "shareId");
    }

    @Transactional
    public Map<String, Object> revokeShare(
        String subject,
        UUID packageId,
        UUID shareId,
        String reason,
        String idempotencyKey
    ) {
        PackageRow row = row(packageId);
        var authorityScope = authorization.requireMonth(
            subject, row.monthId(), "evidence.package.download",
            FinanceAuthorizationService.Party.ANY);
        Map<String, Object> request = Map.of(
            "shareId", shareId, "reason", reason == null ? "" : reason);
        UUID replay = journal.replay(
            subject, "PACKAGE_SHARE_REVOKE", packageId,
            idempotencyKey, request);
        if (replay != null) {
            return shareView(replay);
        }
        int changed = jdbc.update("""
            UPDATE evidence_package_shares
            SET revoked_at = CURRENT_TIMESTAMP, revoked_by_subject = ?
            WHERE id = ? AND package_version_id = ? AND revoked_at IS NULL
            """, subject, shareId, packageId);
        if (changed == 0) {
            Map<String, Object> existing = shareView(shareId);
            if (!packageId.equals(existing.get("packageId"))) {
                throw new EntityNotFoundException("Package share not found.");
            }
        }
        journal.audit(row.monthId(), "PACKAGE_SHARE_REVOKED",
            "EVIDENCE_PACKAGE", packageId, (long) row.version(),
            "SUCCESS", reason, subject, authority(authorityScope),
            List.of(Map.of("shareId", shareId)));
        journal.event(row.monthId(), "f05.package.share.revoked.v1",
            "PACKAGE_SHARE", shareId, 2,
            Map.of("shareId", shareId), subject);
        journal.remember(subject, "PACKAGE_SHARE_REVOKE", packageId,
            idempotencyKey, request, "PACKAGE_SHARE", shareId);
        return shareView(shareId);
    }

    private Map<String, Object> shareView(UUID shareId) {
        Map<String, Object> value = jdbc.query("""
            SELECT id, package_version_id, recipient_subject, access_scope,
                   expires_at, revoked_at, created_by_subject, created_at,
                   correlation_id
            FROM evidence_package_shares WHERE id = ?
            """, rs -> {
                if (!rs.next()) {
                    return null;
                }
                return shareMap(rs);
            }, shareId);
        if (value == null) {
            throw new EntityNotFoundException("Package share not found.");
        }
        return value;
    }

    private Instant databaseSnapshot(
        String cursor,
        FinancePageCursorCodec.Cursor decoded
    ) {
        if (cursor != null && !cursor.isBlank()) {
            return decoded.snapshotAt();
        }
        Timestamp databaseNow = jdbc.queryForObject(
            "SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (databaseNow == null) {
            throw new IllegalStateException(
                "The database did not provide a pagination snapshot.");
        }
        return databaseNow.toInstant();
    }

    private Map<String, Object> shareMap(ResultSet rs) throws SQLException {
        Map<String, Object> share = new LinkedHashMap<>();
        share.put("shareId", rs.getObject(1, UUID.class));
        share.put("packageId", rs.getObject(2, UUID.class));
        share.put("recipientSubject", rs.getString(3));
        share.put("accessScope", rs.getString(4));
        share.put("expiresAt", offset(rs.getTimestamp(5)));
        share.put("revoked", rs.getTimestamp(6) != null);
        share.put("revokedAt", offset(rs.getTimestamp(6)));
        share.put("createdByDisplay", rs.getString(7));
        share.put("createdAt", offset(rs.getTimestamp(8)));
        share.put("correlationId", rs.getObject(9, UUID.class));
        return share;
    }

    private Map<String, Object> summaryMap(PackageRow row) {
        String state = switch (row.status()) {
            case "CURRENT" -> "AVAILABLE";
            case "INVALIDATED" -> "INTEGRITY_FAILED";
            default -> row.status();
        };
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("packageId", row.id());
        value.put("monthId", row.monthId());
        value.put("version", row.version());
        value.put("state", state);
        value.put("progressPercent", "GENERATING".equals(row.status()) ? 25 : 100);
        value.put("canonicalInputHash", row.inputHash());
        value.put("invoiceId", row.invoiceId());
        value.put("invoiceVersion", row.invoiceVersion());
        value.put("invoiceDocumentArtifactId", row.invoiceDocumentArtifactId());
        value.put("invoiceDocumentHash", row.invoiceDocumentHash());
        value.put("policyVersion", "f05-policy-v" + row.policyVersion());
        value.put("templateVersion", row.renderVersion());
        value.put("generatedAt", row.generatedAt());
        value.put("supersedesPackageId", row.supersedesId());
        value.put("current", "CURRENT".equals(row.status()));
        value.put("permissions", VIEW_PERMISSIONS);
        return value;
    }

    private PackageRow row(UUID id) {
        PackageRow row = jdbc.query("""
            SELECT package.id, package.engagement_month_id, package.version,
                   package.status, package.canonical_input_hash,
                   package.render_version, package.supersedes_id,
                   package.canonical_manifest::text, package.generated_at,
                   engagement.name, month.month_start_date, policy.version,
                   package.invoice_id, package.invoice_version,
                   package.invoice_document_artifact_id,
                   package.invoice_document_hash
            FROM evidence_package_versions package
            JOIN engagement_months month ON month.id = package.engagement_month_id
            JOIN engagements engagement ON engagement.id = month.engagement_id
            JOIN f05_policy_versions policy
              ON policy.id = package.policy_version_id
            WHERE package.id = ?
            """, rs -> rs.next() ? packageRow(rs) : null, id);
        if (row == null) {
            throw new EntityNotFoundException("Finance resource not found.");
        }
        return row;
    }

    private PackageRow packageRow(ResultSet rs) throws SQLException {
        return new PackageRow(
            rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
            rs.getInt(3), rs.getString(4), rs.getString(5),
            rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8),
            offset(rs.getTimestamp(9)), rs.getString(10),
            rs.getTimestamp(11), rs.getInt(12),
            rs.getObject(13, UUID.class), rs.getInt(14),
            rs.getObject(15, UUID.class), rs.getString(16));
    }

    private List<Map<String, Object>> items(UUID packageId) {
        return jdbc.query("""
            SELECT item.id, item.item_type, item.source_object_type,
                   item.source_object_id, item.source_version, item.source_hash,
                   item.provenance, item.represented_at, item.recorded_at,
                   item.disclosure,
                   item.safe_name, item.media_type, item.byte_size,
                   item.object_version, item.classification,
                   item.retention_class, item.artifact_availability
            FROM evidence_package_items item
            WHERE item.package_version_id = ?
            ORDER BY item.ordinal
            """, (rs, index) -> {
                Map<String, Object> source = new LinkedHashMap<>();
                source.put("sourceType", rs.getString(3));
                source.put("sourceId", rs.getObject(4, UUID.class));
                source.put("version", rs.getString(5));
                source.put("checksum", rs.getString(6));
                source.put("provenance", rs.getString(7));
                source.put("freshness", "CURRENT");
                source.put("temporalMode", rs.getTimestamp(8) == null
                    ? "LIVE" : "SNAPSHOT");
                source.put("representedAt", offset(rs.getTimestamp(8)));
                source.put("recordedAt", offset(rs.getTimestamp(9)));
                source.put("superseded", false);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("itemId", rs.getObject(1, UUID.class));
                item.put("logicalType", rs.getString(2));
                item.put("safeName", rs.getString(11));
                item.put("source", source);
                item.put("mimeType", rs.getString(12));
                item.put("sizeBytes", rs.getLong(13));
                item.put("sha256", rs.getString(6));
                item.put("objectVersion", rs.getString(14));
                item.put("classification", rs.getString(15));
                item.put("retentionPolicy", rs.getString(16));
                item.put("artifactAvailability", rs.getString(17));
                item.put("disclosure", rs.getString(10));
                return item;
            }, packageId);
    }

    private List<Map<String, Object>> artifacts(UUID packageId) {
        return jdbc.query("""
            SELECT output.id, output.output_format, output.output_hash,
                   output.renderer_status, artifact.byte_size,
                   artifact.scan_status, artifact.classification
            FROM evidence_package_outputs output
            LEFT JOIN f05_private_artifacts artifact ON artifact.id = output.artifact_id
            WHERE output.package_version_id = ?
            ORDER BY output.output_format
            """, (rs, index) -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("artifactId", rs.getObject(1, UUID.class));
                value.put("label", rs.getString(2) + " evidence manifest");
                value.put("format", rs.getString(2));
                value.put("sha256", rs.getString(3));
                value.put("sizeBytes", rs.getObject(5) == null ? 0 : rs.getLong(5));
                value.put("scanStatus", rs.getString(6) == null
                    ? "UNKNOWN" : rs.getString(6));
                value.put("classification", rs.getString(7) == null
                    ? "CONFIDENTIAL" : rs.getString(7));
                value.put("downloadAllowed",
                    "LOCAL_MANIFEST_ONLY".equals(rs.getString(4))
                        || "PASSED".equals(rs.getString(6)));
                return value;
            }, packageId);
    }

    private List<ItemKey> itemKeys(UUID packageId) {
        return jdbc.query("""
            SELECT item_type, source_object_id, source_version
            FROM evidence_package_items WHERE package_version_id = ?
            """, (rs, index) -> new ItemKey(
                rs.getString(1), rs.getObject(2, UUID.class).toString(),
                rs.getString(3)), packageId);
    }

    private InvoiceArtifact currentPrimaryInvoiceArtifact(UUID monthId) {
        InvoiceArtifact value = jdbc.query("""
            SELECT invoice.id, invoice.current_version, invoice.invoice_number,
                   artifact.id, artifact.safe_name, artifact.media_type,
                   artifact.byte_size, artifact.content_hash,
                   artifact.object_version, artifact.classification,
                   artifact.retention_class, artifact.recorded_at
            FROM invoices invoice
            JOIN invoice_versions version
              ON version.invoice_id = invoice.id
             AND version.version = invoice.current_version
            JOIN f05_private_artifacts artifact
              ON artifact.id = version.document_artifact_id
            WHERE invoice.engagement_month_id = ?
              AND invoice.invoice_type = 'PRIMARY'
              AND invoice.status NOT IN ('SUPERSEDED', 'CANCELLED')
              AND artifact.scan_status = 'PASSED'
            FOR SHARE
            """, rs -> rs.next() ? new InvoiceArtifact(
                rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3),
                rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6),
                rs.getLong(7), rs.getString(8), rs.getString(9),
                rs.getString(10), rs.getString(11),
                offset(rs.getTimestamp(12))) : null, monthId);
        if (value == null) {
            throw new DomainConflictException(
                "SCAN_PASSED_INVOICE_REQUIRED",
                "The current primary invoice must have a scan-passed document "
                    + "before an evidence package can be generated.");
        }
        return value;
    }

    private List<RelatedInvoiceArtifact> relatedInvoiceArtifacts(
        UUID monthId,
        UUID primaryInvoiceId
    ) {
        return jdbc.query("""
            SELECT invoice.id, invoice.invoice_type, invoice.current_version,
                   invoice.invoice_number,
                   COALESCE(invoice.corrected_invoice_id,
                            invoice.note_for_invoice_id),
                   version.metadata_hash, version.represented_at,
                   version.recorded_at,
                   artifact.id, artifact.safe_name, artifact.media_type,
                   artifact.byte_size, artifact.content_hash,
                   artifact.object_version, artifact.classification,
                   artifact.retention_class, artifact.scan_status
            FROM invoices invoice
            JOIN invoice_versions version
              ON version.invoice_id = invoice.id
             AND version.version = invoice.current_version
            LEFT JOIN f05_private_artifacts artifact
              ON artifact.id = version.document_artifact_id
            WHERE invoice.engagement_month_id = ?
              AND invoice.invoice_type <> 'PRIMARY'
              AND invoice.status NOT IN ('SUPERSEDED', 'CANCELLED')
              AND (
                  invoice.corrected_invoice_id = ?
                  OR invoice.note_for_invoice_id = ?
              )
            ORDER BY invoice.invoice_type, invoice.id
            FOR SHARE OF invoice, version
            """, (rs, index) -> {
                boolean binaryAvailable = "PASSED".equals(rs.getString(17));
                String sourceVersion = Integer.toString(rs.getInt(3));
                return new RelatedInvoiceArtifact(
                    rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3),
                    rs.getString(4), rs.getObject(5, UUID.class),
                    binaryAvailable ? rs.getObject(9, UUID.class) : null,
                    rs.getString(10) == null
                        ? relatedSafeName(rs.getString(2), rs.getObject(1, UUID.class))
                        : rs.getString(10),
                    rs.getString(11) == null
                        ? "application/json" : rs.getString(11),
                    rs.getObject(12) == null ? 0 : rs.getLong(12),
                    binaryAvailable ? rs.getString(13) : rs.getString(6),
                    rs.getString(14) == null ? sourceVersion : rs.getString(14),
                    rs.getString(15) == null
                        ? "CONFIDENTIAL" : rs.getString(15),
                    rs.getString(16) == null
                        ? "FINANCE_EVIDENCE" : rs.getString(16),
                    offset(rs.getTimestamp(7)), offset(rs.getTimestamp(8)),
                    binaryAvailable);
            }, monthId, primaryInvoiceId, primaryInvoiceId);
    }

    private List<Map<String, Object>> completeManifestItems(
        FinanceF04EvidenceResolver.HandoffEvidence source,
        InvoiceArtifact invoice,
        List<RelatedInvoiceArtifact> relatedInvoices
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(manifestItem(
            "ENGAGEMENT_CONTRACT", "F04_HANDOFF", source.handoffId(),
            source.handoffHash(), source.handoffHash(),
            "AUTHORITATIVE_F04_HANDOFF", source.handoffCreatedAt(),
            source.handoffCreatedAt(), referenceMetadata(
                "f04-handoff.json", source.handoffHash()),
            "The exact immutable F04 handoff object/version/hash is retained; "
                + "an upstream binary is not substituted."));
        addPillarItem(result, "ROSTER_ALLOCATION",
            source.pillar("ROSTER_ALLOCATION"), source);
        addPillarItem(result, "ATTENDANCE",
            source.pillar("ATTENDANCE"), source);
        addPillarItem(result, "APPROVED_PLAN",
            source.pillar("PLAN_LINEAR"), source);
        addPillarItem(result, "LINEAR_SNAPSHOT",
            source.pillar("PLAN_LINEAR"), source);
        addPillarItem(result, "DELIVERY_CERTIFICATION",
            source.pillar("CERTIFICATION"), source);
        addPillarItem(result, "VERIFIED_CONFIRMATION",
            source.pillar("CONFIRMATION_F05"), source);
        result.add(manifestItem(
            "INVOICE_DOCUMENT", "INVOICE", invoice.invoiceId(),
            Integer.toString(invoice.version()), invoice.hash(),
            "SCAN_PASSED_PRIVATE_ARTIFACT", invoice.recordedAt(),
            invoice.recordedAt(), invoiceMetadata(invoice),
            "This is the exact current PRIMARY invoice document represented "
                + "by the package header lineage."));
        for (RelatedInvoiceArtifact related : relatedInvoices) {
            Map<String, Object> item = manifestItem(
                "INVOICE_" + related.invoiceType() + "_DISCLOSURE",
                "RELATED_INVOICE", related.invoiceId(),
                Integer.toString(related.version()), related.hash(),
                "RELATED_INVOICE_DISCLOSURE", related.representedAt(),
                related.recordedAt(), relatedMetadata(related),
                related.binaryAvailable()
                    ? "Related " + related.invoiceType()
                        + " is disclosed with its exact scan-passed artifact; "
                        + "it does not replace the PRIMARY invoice."
                    : "Related " + related.invoiceType()
                        + " is disclosed by immutable metadata hash because no "
                        + "scan-passed binary is available; it does not replace "
                        + "the PRIMARY invoice.");
            item.put("invoiceNumber", related.invoiceNumber());
            item.put("relationshipType", related.invoiceType());
            item.put("relatedToPrimaryInvoiceId", related.relatedToInvoiceId());
            result.add(item);
        }
        return List.copyOf(result);
    }

    private void addPillarItem(
        List<Map<String, Object>> target,
        String logicalType,
        FinanceF04EvidenceResolver.PillarFact fact,
        FinanceF04EvidenceResolver.HandoffEvidence source
    ) {
        Map<String, Object> hashInput = new LinkedHashMap<>();
        hashInput.put("id", fact.id());
        hashInput.put("pillar", fact.pillar());
        hashInput.put("status", fact.status());
        hashInput.put("sourceType", fact.sourceType());
        hashInput.put("sourceId", fact.sourceId());
        hashInput.put("sourceVersion", fact.sourceVersion());
        hashInput.put("freshness", fact.freshness());
        hashInput.put("details", fact.details());
        target.add(manifestItem(
            logicalType, "F04_PILLAR_" + logicalType, fact.id(),
            fact.sourceVersion() == null
                ? source.readinessHash() : fact.sourceVersion(),
            canonical.sha256(hashInput), "F04_READINESS_RESULT",
            source.readinessEvaluatedAt(), source.readinessEvaluatedAt(),
            referenceMetadata(
                logicalType.toLowerCase(java.util.Locale.ROOT) + ".json",
                fact.sourceVersion() == null
                    ? source.readinessHash() : fact.sourceVersion()),
            "The authoritative F04 readiness result is retained by exact "
                + "object/version/hash; no different binary is substituted."));
    }

    private Map<String, Object> manifestItem(
        String logicalType,
        String sourceType,
        UUID sourceId,
        String sourceVersion,
        String sourceHash,
        String provenance,
        OffsetDateTime representedAt,
        OffsetDateTime recordedAt,
        ArtifactMetadata artifact,
        String disclosure
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("logicalType", logicalType);
        value.put("sourceObjectType", sourceType);
        value.put("sourceObjectId", sourceId);
        value.put("sourceVersion", sourceVersion);
        value.put("sourceHash", sourceHash);
        value.put("provenance", provenance);
        value.put("representedAt", representedAt);
        value.put("recordedAt", recordedAt);
        value.put("artifactId", artifact.artifactId());
        value.put("safeName", artifact.safeName());
        value.put("mimeType", artifact.mediaType());
        value.put("sizeBytes", artifact.sizeBytes());
        value.put("sha256", sourceHash);
        value.put("objectVersion", artifact.objectVersion());
        value.put("classification", artifact.classification());
        value.put("retentionPolicy", artifact.retentionClass());
        value.put("artifactAvailability", artifact.availability());
        value.put("disclosure", disclosure);
        return value;
    }

    private ArtifactMetadata referenceMetadata(
        String safeName,
        String objectVersion
    ) {
        return new ArtifactMetadata(
            null, safeName, "application/json", 0, objectVersion,
            "CONFIDENTIAL", "FINANCE_EVIDENCE",
            "IMMUTABLE_SOURCE_REFERENCE_ONLY");
    }

    private ArtifactMetadata invoiceMetadata(InvoiceArtifact invoice) {
        return new ArtifactMetadata(
            invoice.artifactId(), invoice.safeName(), invoice.mediaType(),
            invoice.size(), invoice.objectVersion(), invoice.classification(),
            invoice.retentionClass(), "PRIVATE_SCAN_PASSED_BINARY");
    }

    private ArtifactMetadata relatedMetadata(RelatedInvoiceArtifact invoice) {
        return new ArtifactMetadata(
            invoice.artifactId(), invoice.safeName(), invoice.mediaType(),
            invoice.size(), invoice.objectVersion(), invoice.classification(),
            invoice.retentionClass(), invoice.binaryAvailable()
                ? "PRIVATE_SCAN_PASSED_BINARY"
                : "IMMUTABLE_SOURCE_REFERENCE_ONLY");
    }

    private void persistItems(
        UUID packageId,
        List<Map<String, Object>> manifestItems
    ) {
        for (int index = 0; index < manifestItems.size(); index++) {
            Map<String, Object> item = manifestItems.get(index);
            jdbc.update("""
                INSERT INTO evidence_package_items(
                    id, package_version_id, ordinal, item_type,
                    source_object_type, source_object_id, source_version,
                    source_hash, provenance, represented_at, recorded_at,
                    disclosure, artifact_id, safe_name, media_type, byte_size,
                    object_version, classification, retention_class,
                    artifact_availability
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?)
                """, UUID.randomUUID(), packageId, index,
                item.get("logicalType"), item.get("sourceObjectType"),
                item.get("sourceObjectId"), item.get("sourceVersion"),
                item.get("sourceHash"), item.get("provenance"),
                item.get("representedAt"), item.get("recordedAt"),
                item.get("disclosure"),
                item.get("artifactId"), item.get("safeName"),
                item.get("mimeType"), item.get("sizeBytes"),
                item.get("objectVersion"), item.get("classification"),
                item.get("retentionPolicy"),
                item.get("artifactAvailability"));
        }
    }

    private Map<String, Object> invoiceLineage(InvoiceArtifact invoice) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("invoiceId", invoice.invoiceId());
        value.put("invoiceType", "PRIMARY");
        value.put("invoiceNumber", invoice.invoiceNumber());
        value.put("invoiceVersion", invoice.version());
        value.put("documentArtifactId", invoice.artifactId());
        value.put("documentHash", invoice.hash());
        value.put("safeName", invoice.safeName());
        value.put("mimeType", invoice.mediaType());
        value.put("sizeBytes", invoice.size());
        value.put("objectVersion", invoice.objectVersion());
        value.put("classification", invoice.classification());
        value.put("retentionPolicy", invoice.retentionClass());
        return value;
    }

    private Map<String, Object> relatedInvoiceDisclosure(
        RelatedInvoiceArtifact invoice
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("invoiceId", invoice.invoiceId());
        value.put("invoiceType", invoice.invoiceType());
        value.put("invoiceNumber", invoice.invoiceNumber());
        value.put("invoiceVersion", invoice.version());
        value.put("relatedToPrimaryInvoiceId", invoice.relatedToInvoiceId());
        value.put("documentArtifactId", invoice.artifactId());
        value.put("documentHash", invoice.hash());
        value.put("artifactAvailability", invoice.binaryAvailable()
            ? "PRIVATE_SCAN_PASSED_BINARY"
            : "IMMUTABLE_SOURCE_REFERENCE_ONLY");
        value.put("substitutesPrimaryInvoice", false);
        return value;
    }

    private static String relatedSafeName(String invoiceType, UUID invoiceId) {
        return invoiceType.toLowerCase(java.util.Locale.ROOT)
            .replace('_', '-') + "-" + invoiceId + ".json";
    }

    private FinancePageCursorCodec.Cursor decodeCursor(
        String encoded,
        String resource,
        String subject,
        List<UUID> engagements
    ) {
        return encoded == null || encoded.isBlank() ? null
            : cursors.decode(encoded, resource, subject, engagements);
    }

    private Map<String, Object> page(
        List<Map<String, Object>> queried,
        long totalCount,
        String resource,
        String subject,
        List<UUID> engagements,
        Instant snapshotAt,
        String sortField,
        String idField
    ) {
        boolean hasNext = queried.size() > 50;
        List<Map<String, Object>> items = hasNext
            ? List.copyOf(queried.subList(0, 50)) : List.copyOf(queried);
        String nextCursor = null;
        if (hasNext) {
            Map<String, Object> last = items.getLast();
            nextCursor = cursors.encode(
                resource, subject, engagements, snapshotAt,
                String.valueOf(last.get(sortField)),
                UUID.fromString(String.valueOf(last.get(idField))));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("nextCursor", nextCursor);
        result.put("totalCount", totalCount);
        result.put("membershipSnapshotAt",
            OffsetDateTime.ofInstant(snapshotAt, ZoneOffset.UTC));
        result.put("temporalMode",
            "SNAPSHOT_MEMBERSHIP_VALUES_LIVE_AT_READ");
        return result;
    }

    private Map<String, Object> authority(
        FinanceAuthorizationService.Scope scope
    ) {
        return Map.of(
            "permission", "server-derived",
            "engagementId", scope.engagementId().toString(),
            "vendorOrganizationId", scope.vendorOrganizationId().toString());
    }

    private Map<String, Object> keyView(ItemKey value) {
        return Map.of(
            "logicalType", value.logicalType(),
            "sourceId", value.sourceId(),
            "version", value.version());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private Map<String, Object> readMap(String value) {
        return canonical.readMap(value);
    }

    private static OffsetDateTime offset(Timestamp value) {
        return value == null ? null
            : value.toInstant().atOffset(ZoneOffset.UTC);
    }

    private String auditAction(String action) {
        if (action.contains("DOWNLOAD")) {
            return "DOWNLOADED";
        }
        if (action.contains("SHARE")) {
            return "SHARED";
        }
        if (action.contains("REVOK")) {
            return "REVOKED";
        }
        return "VIEWED";
    }

    private record PackageRow(
        UUID id,
        UUID monthId,
        int version,
        String status,
        String inputHash,
        String renderVersion,
        UUID supersedesId,
        String manifest,
        OffsetDateTime generatedAt,
        String engagementLabel,
        Timestamp monthStart,
        int policyVersion,
        UUID invoiceId,
        int invoiceVersion,
        UUID invoiceDocumentArtifactId,
        String invoiceDocumentHash
    ) {
    }

    private record ItemKey(String logicalType, String sourceId, String version) {
    }

    private record Output(
        String format,
        String hash,
        String rendererStatus,
        String scanStatus,
        UUID artifactId,
        String mediaType,
        String safeName
    ) {
    }

    private record InvoiceArtifact(
        UUID invoiceId,
        int version,
        String invoiceNumber,
        UUID artifactId,
        String safeName,
        String mediaType,
        long size,
        String hash,
        String objectVersion,
        String classification,
        String retentionClass,
        OffsetDateTime recordedAt
    ) {
    }

    private record RelatedInvoiceArtifact(
        UUID invoiceId,
        String invoiceType,
        int version,
        String invoiceNumber,
        UUID relatedToInvoiceId,
        UUID artifactId,
        String safeName,
        String mediaType,
        long size,
        String hash,
        String objectVersion,
        String classification,
        String retentionClass,
        OffsetDateTime representedAt,
        OffsetDateTime recordedAt,
        boolean binaryAvailable
    ) {
    }

    private record ArtifactMetadata(
        UUID artifactId,
        String safeName,
        String mediaType,
        long sizeBytes,
        String objectVersion,
        String classification,
        String retentionClass,
        String availability
    ) {
    }

    public record PackageDownloadResult(
        byte[] content,
        String mediaType,
        String safeName
    ) {
    }
}
