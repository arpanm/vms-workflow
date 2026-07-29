package com.vms.workflow.application;

import com.vms.workflow.api.DomainConflictException;
import com.vms.workflow.api.FinanceController.CreateInvoiceInput;
import com.vms.workflow.api.FinanceController.ReadinessInput;
import com.vms.workflow.api.FinanceController.SubmitInvoiceInput;
import com.vms.workflow.api.FinanceController.UploadDocumentMetadata;
import com.vms.workflow.security.FinanceAuthorizationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Versioned vendor invoice and deterministic readiness application boundary.
 *
 * <p>The service stores represented invoice metadata and immutable evidence
 * lineage only. It deliberately contains no employee rate, payroll, margin or
 * derived commercial calculation.</p>
 */
@Service
public class FinanceInvoiceService {
    private final JdbcTemplate jdbc;
    private final FinanceAuthorizationService authorization;
    private final FinanceMutationJournal journal;
    private final FinanceCanonicalJson canonical;
    private final FinancePackageService packages;
    private final FinancePrivateStorageAdapter storage;
    private final FinanceMalwareScanner scanner;
    private final FinanceF04EvidenceResolver f04Evidence;
    private final FinancePolicyService policies;
    private final FinanceExceptionValidityService exceptionValidity;
    private final FinancePageCursorCodec cursors;
    private final Clock clock;

    public FinanceInvoiceService(
        JdbcTemplate jdbc,
        FinanceAuthorizationService authorization,
        FinanceMutationJournal journal,
        FinanceCanonicalJson canonical,
        FinancePackageService packages,
        FinancePrivateStorageAdapter storage,
        FinanceMalwareScanner scanner,
        FinanceF04EvidenceResolver f04Evidence,
        FinancePolicyService policies,
        FinanceExceptionValidityService exceptionValidity,
        FinancePageCursorCodec cursors,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.journal = journal;
        this.canonical = canonical;
        this.packages = packages;
        this.storage = storage;
        this.scanner = scanner;
        this.f04Evidence = f04Evidence;
        this.policies = policies;
        this.exceptionValidity = exceptionValidity;
        this.cursors = cursors;
        this.clock = clock;
    }

    public Map<String, Object> access(String subject) {
        List<UUID> engagements = authorizedEngagements(subject, "finance.read");
        if (engagements.isEmpty()) {
            throw new EntityNotFoundException("Finance scope not found.");
        }
        UUID engagementId = engagements.getFirst();
        var scope = authorization.requireEngagement(
            subject, engagementId, "finance.read");
        String engagementLabel = jdbc.queryForObject(
            "SELECT name FROM engagements WHERE id = ?",
            String.class, engagementId);
        String organizationLabel = jdbc.query("""
            SELECT organization.display_name
            FROM organizations organization
            JOIN memberships membership
              ON membership.organization_id = organization.id
            JOIN user_profiles profile
              ON profile.id = membership.user_profile_id
            WHERE profile.identity_subject = ?
              AND membership.status = 'ACTIVE'
            ORDER BY organization.display_name
            LIMIT 1
            """, rs -> rs.next() ? rs.getString(1) : "Scoped organization",
            subject);
        return map(
            "permissions", permissions(subject, engagementId),
            "organizationLabel", organizationLabel,
            "scopeLabel", engagementLabel,
            "scope", map(
                "engagementId", engagementId,
                "vendorOrganizationId", scope.vendorOrganizationId()),
            "storage", storage.configurationStatus(),
            "scanner", scanner.configurationStatus(),
            "renderer", "ACTION_REQUIRED",
            "erp", "NOT_CONFIGURED");
    }

    public List<Map<String, Object>> months(String subject) {
        List<UUID> authorized = authorizedEngagements(subject, "finance.read");
        if (authorized.isEmpty()) {
            throw new EntityNotFoundException("Finance scope not found.");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (UUID engagementId : authorized) {
            authorization.requireEngagement(subject, engagementId, "finance.read");
            result.addAll(jdbc.query("""
                SELECT month.id, month.certification_version,
                       month.month_start_date, engagement.name,
                       vendor.display_name,
                       package.version,
                       COUNT(invoice.id),
                       BOOL_OR(readiness.eligible),
                       MAX(COALESCE(invoice.updated_at, package.generated_at))
                FROM engagement_months month
                JOIN engagements engagement ON engagement.id = month.engagement_id
                JOIN organizations vendor
                  ON vendor.id = engagement.vendor_organization_id
                LEFT JOIN evidence_package_versions package
                  ON package.engagement_month_id = month.id
                 AND package.status = 'CURRENT'
                LEFT JOIN invoices invoice
                  ON invoice.engagement_month_id = month.id
                 AND invoice.status NOT IN ('SUPERSEDED', 'CANCELLED')
                LEFT JOIN invoice_readiness_runs readiness
                  ON readiness.id = invoice.current_readiness_run_id
                WHERE month.engagement_id = ?
                GROUP BY month.id, month.certification_version,
                         month.month_start_date, engagement.name,
                         vendor.display_name, package.version
                ORDER BY month.month_start_date DESC
                """, (rs, rowNum) -> map(
                    "monthId", rs.getObject(1, UUID.class),
                    "version", rs.getLong(2),
                    "monthLabel", rs.getObject(3, LocalDate.class).toString(),
                    "engagementLabel", rs.getString(4),
                    "vendorLabel", rs.getString(5),
                    "currentPackageVersion", rs.getObject(6),
                    "invoiceCount", rs.getLong(7),
                    "readiness", rs.getBoolean(8) ? "COMPLETE" : "BLOCKING",
                    "refreshedAt", offset(rs.getTimestamp(9)),
                    "freshness", "CURRENT",
                    "permissions", permissions(subject, engagementId)),
                engagementId));
        }
        return result;
    }

    public Map<String, Object> months(String subject, String cursor) {
        List<UUID> authorized = authorizedEngagements(subject, "finance.read");
        if (authorized.isEmpty()) {
            throw new EntityNotFoundException("Finance scope not found.");
        }
        String resource = "finance-months";
        FinancePageCursorCodec.Cursor decoded = decodeCursor(
            cursor, resource, subject, authorized);
        Instant snapshotAt = decoded == null
            ? clock.instant() : decoded.snapshotAt();
        LocalDate lastMonth = decoded == null ? null
            : LocalDate.parse(decoded.lastSortValue());
        UUID lastId = decoded == null ? null : decoded.lastId();
        List<Map<String, Object>> rows = jdbc.query("""
            SELECT month.id, month.certification_version,
                   month.month_start_date, engagement.name,
                   vendor.display_name, package.version,
                   COUNT(invoice.id), BOOL_OR(readiness.eligible),
                   MAX(COALESCE(invoice.updated_at, package.generated_at,
                                month.updated_at)),
                   month.engagement_id
            FROM engagement_months month
            JOIN engagements engagement ON engagement.id = month.engagement_id
            JOIN organizations vendor
              ON vendor.id = engagement.vendor_organization_id
            LEFT JOIN evidence_package_versions package
              ON package.engagement_month_id = month.id
             AND package.status = 'CURRENT'
            LEFT JOIN invoices invoice
              ON invoice.engagement_month_id = month.id
             AND invoice.status NOT IN ('SUPERSEDED', 'CANCELLED')
            LEFT JOIN invoice_readiness_runs readiness
              ON readiness.id = invoice.current_readiness_run_id
            WHERE month.engagement_id = ANY (?::uuid[])
              AND month.created_at <= ?
              AND (
                  ?::date IS NULL
                  OR (month.month_start_date, month.id)
                     < (?::date, ?::uuid)
              )
            GROUP BY month.id, month.certification_version,
                     month.month_start_date, engagement.name,
                     vendor.display_name, package.version,
                     month.engagement_id
            ORDER BY month.month_start_date DESC, month.id DESC
            LIMIT 51
            """, (rs, index) -> {
                UUID engagementId = rs.getObject(10, UUID.class);
                return map(
                    "monthId", rs.getObject(1, UUID.class),
                    "version", rs.getLong(2),
                    "monthLabel", rs.getObject(3, LocalDate.class).toString(),
                    "engagementLabel", rs.getString(4),
                    "vendorLabel", rs.getString(5),
                    "currentPackageVersion", rs.getObject(6),
                    "invoiceCount", rs.getLong(7),
                    "readiness", rs.getBoolean(8) ? "COMPLETE" : "BLOCKING",
                    "refreshedAt", offset(rs.getTimestamp(9)),
                    "freshness", "LIVE_AT_READ",
                    "permissions", permissions(subject, engagementId));
            }, authorized.toArray(UUID[]::new), Timestamp.from(snapshotAt),
            lastMonth, lastMonth, lastId);
        Long total = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM engagement_months month
            WHERE month.engagement_id = ANY (?::uuid[])
              AND month.created_at <= ?
            """, Long.class, authorized.toArray(UUID[]::new),
            Timestamp.from(snapshotAt));
        return page(rows, total == null ? 0 : total,
            resource, subject, authorized, snapshotAt,
            "monthLabel", "monthId");
    }

    public Map<String, Object> workspace(String subject, UUID monthId) {
        var scope = authorization.requireMonth(
            subject, monthId, "finance.read",
            FinanceAuthorizationService.Party.ANY);
        Map<String, Object> month = jdbc.query("""
            SELECT month.certification_version, month.month_start_date,
                   engagement.name, vendor.display_name
            FROM engagement_months month
            JOIN engagements engagement ON engagement.id = month.engagement_id
            JOIN organizations vendor
              ON vendor.id = engagement.vendor_organization_id
            WHERE month.id = ?
            """, rs -> {
                if (!rs.next()) {
                    throw new EntityNotFoundException("Finance month not found.");
                }
                List<Map<String, Object>> invoiceRows = invoiceSummaries(
                    subject, monthId, scope.engagementId());
                Integer packageVersion = jdbc.queryForObject("""
                    SELECT COALESCE(MAX(version), 0)
                    FROM evidence_package_versions
                    WHERE engagement_month_id = ?
                    """, Integer.class, monthId);
                return map(
                    "monthId", monthId,
                    "version", packageVersion == null ? 1 : packageVersion + 1,
                    "serverVersion", rs.getLong(1),
                    "monthLabel", rs.getObject(2, LocalDate.class).toString(),
                    "engagementLabel", rs.getString(3),
                    "vendorLabel", rs.getString(4),
                    "readiness", currentEligibility(monthId)
                        ? "COMPLETE" : "BLOCKING",
                    "invoiceCount", invoiceRows.size(),
                    "currentPackageVersion",
                        packageVersion != null && packageVersion > 0
                            ? packageVersion : null,
                    "refreshedAt", OffsetDateTime.now(clock),
                    "freshness", "CURRENT",
                    "permissions", permissions(subject, scope.engagementId()));
            }, monthId);
        Map<String, Object> handoff = effectiveHandoff(monthId);
        List<Map<String, Object>> invoiceRows =
            invoiceSummaries(subject, monthId, scope.engagementId());
        List<Map<String, Object>> packageRows = packages.history(subject, monthId);
        UUID readinessId = jdbc.query("""
            SELECT readiness.id
            FROM invoice_readiness_runs readiness
            JOIN invoices invoice ON invoice.id = readiness.invoice_id
            WHERE invoice.engagement_month_id = ?
              AND readiness.current_result
            ORDER BY readiness.evaluated_at DESC
            LIMIT 1
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, monthId);
        List<String> blockers = new ArrayList<>();
        if (handoff == null) {
            blockers.add("An effective verified F04 handoff is required.");
        }
        if (invoiceRows.isEmpty()) {
            blockers.add("Create represented invoice metadata and upload the invoice document.");
        }
        if (packageRows.isEmpty()) {
            blockers.add("Generate an immutable evidence package from the current handoff.");
        }
        return map(
            "month", month,
            "permissions", permissions(subject, scope.engagementId()),
            "sourceHandoff", handoff,
            "invoices", invoiceRows,
            "packages", packageRows,
            "currentReadinessRunId", readinessId,
            "blockers", blockers);
    }

    public List<Map<String, Object>> invoices(String subject, UUID monthId) {
        if (monthId != null) {
            var scope = authorization.requireMonth(
                subject, monthId, "finance.read",
                FinanceAuthorizationService.Party.ANY);
            return invoiceSummaries(subject, monthId, scope.engagementId());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        List<UUID> authorized = authorizedEngagements(subject, "finance.read");
        if (authorized.isEmpty()) {
            throw new EntityNotFoundException("Finance scope not found.");
        }
        for (UUID engagementId : authorized) {
            result.addAll(jdbc.query("""
                SELECT month.id
                FROM engagement_months month
                WHERE month.engagement_id = ?
                ORDER BY month.month_start_date DESC
                """, (rs, rowNum) -> invoiceSummaries(
                    subject, rs.getObject(1, UUID.class), engagementId),
                engagementId).stream().flatMap(List::stream).toList());
        }
        return result;
    }

    public Map<String, Object> invoices(
        String subject,
        UUID monthId,
        String cursor
    ) {
        List<UUID> authorized;
        if (monthId == null) {
            authorized = authorizedEngagements(subject, "finance.read");
            if (authorized.isEmpty()) {
                throw new EntityNotFoundException("Finance scope not found.");
            }
        } else {
            var scope = authorization.requireMonth(
                subject, monthId, "finance.read",
                FinanceAuthorizationService.Party.ANY);
            authorized = List.of(scope.engagementId());
        }
        String resource = "finance-invoices:"
            + (monthId == null ? "all" : monthId);
        FinancePageCursorCodec.Cursor decoded = decodeCursor(
            cursor, resource, subject, authorized);
        Instant snapshotAt = decoded == null
            ? clock.instant() : decoded.snapshotAt();
        OffsetDateTime lastCreatedAt = decoded == null ? null
            : OffsetDateTime.parse(decoded.lastSortValue());
        UUID lastId = decoded == null ? null : decoded.lastId();
        Timestamp lastCreated = lastCreatedAt == null ? null
            : Timestamp.from(lastCreatedAt.toInstant());
        List<Map<String, Object>> rows = jdbc.query("""
            SELECT invoice.id, invoice.invoice_number, invoice.status,
                   invoice.current_version, invoice.optimistic_version,
                   invoice.updated_at, month.month_start_date,
                   engagement.name, vendor.display_name, artifact.scan_status,
                   invoice.engagement_month_id, month.engagement_id,
                   invoice.created_at
            FROM invoices invoice
            JOIN engagement_months month
              ON month.id = invoice.engagement_month_id
            JOIN engagements engagement ON engagement.id = month.engagement_id
            JOIN organizations vendor
              ON vendor.id = invoice.vendor_organization_id
            LEFT JOIN invoice_versions version
              ON version.invoice_id = invoice.id
             AND version.version = invoice.current_version
            LEFT JOIN f05_private_artifacts artifact
              ON artifact.id = version.document_artifact_id
            WHERE month.engagement_id = ANY (?::uuid[])
              AND (?::uuid IS NULL OR invoice.engagement_month_id = ?)
              AND invoice.created_at <= ?
              AND (
                  ?::timestamptz IS NULL
                  OR (invoice.created_at, invoice.id)
                     < (?::timestamptz, ?::uuid)
              )
            ORDER BY invoice.created_at DESC, invoice.id DESC
            LIMIT 51
            """, (rs, index) -> {
                UUID engagementId = rs.getObject(12, UUID.class);
                return map(
                    "invoiceId", rs.getObject(1, UUID.class),
                    "monthId", rs.getObject(11, UUID.class),
                    "invoiceNumber", rs.getString(2),
                    "state", rs.getString(3),
                    "documentVersion", rs.getInt(4),
                    "version", rs.getLong(5),
                    "updatedAt", offset(rs.getTimestamp(6)),
                    "monthLabel", rs.getObject(7, LocalDate.class).toString(),
                    "engagementLabel", rs.getString(8),
                    "vendorLabel", rs.getString(9),
                    "scanStatus", rs.getString(10),
                    "createdAt", offset(rs.getTimestamp(13)),
                    "freshness", "LIVE_AT_READ",
                    "permissions", permissions(subject, engagementId));
            }, authorized.toArray(UUID[]::new), monthId, monthId,
            Timestamp.from(snapshotAt), lastCreated, lastCreated, lastId);
        Long total = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM invoices invoice
            JOIN engagement_months month
              ON month.id = invoice.engagement_month_id
            WHERE month.engagement_id = ANY (?::uuid[])
              AND (?::uuid IS NULL OR invoice.engagement_month_id = ?)
              AND invoice.created_at <= ?
            """, Long.class, authorized.toArray(UUID[]::new),
            monthId, monthId, Timestamp.from(snapshotAt));
        return page(rows, total == null ? 0 : total,
            resource, subject, authorized, snapshotAt,
            "createdAt", "invoiceId");
    }

    public Map<String, Object> invoice(String subject, UUID invoiceId) {
        try {
            authorization.requireInvoice(
                subject, invoiceId, "finance.read",
                FinanceAuthorizationService.Party.ANY);
        } catch (AccessDeniedException exception) {
            throw new EntityNotFoundException("Invoice not found.");
        }
        InvoiceRow row = invoiceRow(invoiceId, false);
        if (exceptionValidity.expireInvoice(invoiceId, subject)) {
            row = invoiceRow(invoiceId, false);
        }
        return invoiceView(subject, row);
    }

    @Transactional
    public InvoiceDocumentDownload downloadDocument(
        String subject,
        UUID invoiceId
    ) {
        InvoiceRow invoice = invoiceRow(invoiceId, false);
        var scope = authorization.requireInvoice(
            subject, invoiceId, "finance.read",
            FinanceAuthorizationService.Party.ANY);
        DownloadArtifact artifact = jdbc.query("""
            SELECT artifact.id, artifact.safe_name, artifact.media_type,
                   artifact.content_hash, artifact.scan_status
            FROM invoices invoice
            JOIN invoice_versions version
              ON version.invoice_id = invoice.id
             AND version.version = invoice.current_version
            JOIN f05_private_artifacts artifact
              ON artifact.id = version.document_artifact_id
            WHERE invoice.id = ?
            """, rs -> rs.next() ? new DownloadArtifact(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5)) : null, invoiceId);
        if (artifact == null) {
            throw new EntityNotFoundException("Invoice document not found.");
        }
        if (!"PASSED".equals(artifact.scanStatus())) {
            throw new DomainConflictException(
                "ARTIFACT_SCAN_BLOCKED",
                "Invoice document download is blocked until its scan passes.");
        }
        byte[] content = storage.read(artifact.id());
        if (!canonical.sha256Bytes(content).equals(artifact.hash())) {
            throw new DomainConflictException(
                "INVOICE_ARTIFACT_INTEGRITY_FAILED",
                "Invoice document integrity verification failed.");
        }
        journal.audit(invoice.monthId(), "INVOICE_DOCUMENT_DOWNLOADED",
            "INVOICE", invoiceId, invoice.version(), "SUCCESS",
            "AUTHORIZED_DOWNLOAD", subject,
            authority(scope, "finance.read"),
            List.of(map("type", "INVOICE_ARTIFACT", "id", artifact.id())));
        return new InvoiceDocumentDownload(
            content, artifact.mediaType(), artifact.safeName());
    }

    @Transactional
    public Map<String, Object> create(
        String subject,
        String idempotencyKey,
        CreateInvoiceInput request
    ) {
        var scope = authorization.requireMonth(
            subject, request.monthId(), "invoice.manage",
            FinanceAuthorizationService.Party.VENDOR);
        UUID replay = journal.replay(
            subject, "INVOICE_CREATE", request.monthId(),
            idempotencyKey, request);
        if (replay != null) {
            return invoice(subject, replay);
        }
        String kind = request.documentKind().toUpperCase(Locale.ROOT);
        if (!Set.of("PRIMARY", "CORRECTION", "CREDIT_NOTE", "DEBIT_NOTE")
            .contains(kind)) {
            throw new IllegalArgumentException("Unsupported invoice document kind.");
        }
        UUID corrected = "CORRECTION".equals(kind)
            ? request.relatedInvoiceId() : null;
        UUID noteFor = Set.of("CREDIT_NOTE", "DEBIT_NOTE").contains(kind)
            ? request.relatedInvoiceId() : null;
        requireRelated(request.monthId(), scope.vendorOrganizationId(),
            corrected != null ? corrected : noteFor, kind);
        var metadata = request.representedMetadata();
        LocalDate expectedStart = jdbc.queryForObject("""
            SELECT month_start_date FROM engagement_months WHERE id = ?
            """, LocalDate.class, request.monthId());
        LocalDate expectedEnd = expectedStart.plusMonths(1).minusDays(1);
        if (!expectedStart.equals(metadata.billingPeriodStart())
            || !expectedEnd.equals(metadata.billingPeriodEnd())) {
            throw new IllegalArgumentException(
                "Invoice billing period must equal the engagement month.");
        }
        String normalized = normalizeInvoiceNumber(metadata.invoiceNumber());
        UUID invoiceId = UUID.randomUUID();
        UUID correlationId = journal.correlationId();
        if (corrected != null) {
            jdbc.update("""
                UPDATE invoices
                SET status = 'SUPERSEDED',
                    optimistic_version = optimistic_version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, corrected);
        }
        jdbc.update("""
            INSERT INTO invoices(
                id, engagement_month_id, vendor_organization_id,
                invoice_type, invoice_number, normalized_invoice_number,
                invoice_date, billing_period_start, billing_period_end, currency,
                taxable_value, tax_value, total_value, po_reference,
                work_order_reference, status, corrected_invoice_id,
                note_for_invoice_id, created_by_subject, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT',
                      ?, ?, ?, ?)
            """, invoiceId, request.monthId(), scope.vendorOrganizationId(),
            kind, clean(metadata.invoiceNumber(), 160), normalized,
            metadata.invoiceDate(),
            metadata.billingPeriodStart(), metadata.billingPeriodEnd(),
            metadata.currency().toUpperCase(Locale.ROOT),
            decimal(metadata.taxableValue()), decimal(metadata.taxValue()),
            decimal(metadata.totalValue()),
            clean(metadata.purchaseOrderReference(), 160),
            clean(metadata.workOrderReference(), 160),
            corrected, noteFor, subject, correlationId);
        Map<String, Object> represented = representedMetadata(request);
        jdbc.update("""
            INSERT INTO invoice_versions(
                id, invoice_id, version, metadata_manifest, metadata_hash,
                source, represented_at, created_by_subject, correlation_id
            ) VALUES (?, ?, 1, ?::jsonb, ?, 'VENDOR_METADATA',
                      CURRENT_TIMESTAMP, ?, ?)
            """, UUID.randomUUID(), invoiceId, canonical.write(represented),
            canonical.sha256(represented), subject, correlationId);
        journal.event(request.monthId(), "f05.invoice.created.v1",
            "INVOICE", invoiceId, 1,
            map("documentKind", kind, "normalizedInvoiceNumber", normalized),
            subject);
        journal.audit(request.monthId(), "INVOICE_CREATED", "INVOICE",
            invoiceId, 1L, "SUCCESS", "REPRESENTED_METADATA", subject,
            authority(scope, "invoice.manage"), List.of());
        journal.remember(subject, "INVOICE_CREATE", request.monthId(),
            idempotencyKey, request, "INVOICE", invoiceId);
        return invoice(subject, invoiceId);
    }

    @Transactional
    public Map<String, Object> uploadDocument(
        String subject,
        UUID invoiceId,
        String ifMatch,
        String idempotencyKey,
        MultipartFile file,
        UploadDocumentMetadata request
    ) {
        var scope = authorization.requireInvoice(
            subject, invoiceId, "invoice.manage",
            FinanceAuthorizationService.Party.VENDOR);
        requireIfMatch(ifMatch, request.expectedVersion());
        FinancePolicyService.Policy policy =
            policies.active(scope.engagementId(), subject);
        byte[] bytes = documentBytes(file, policy.maximumUploadBytes());
        String mediaType = validateMediaType(
            file, bytes, policy.allowedMimeTypes());
        String evidenceClassification = classification(request.classification());
        if (!policy.allowedClassifications().contains(evidenceClassification)) {
            throw new IllegalArgumentException(
                "Evidence classification is not allowed by the effective F05 policy.");
        }
        if (!policy.retentionClass().equals(request.retentionPolicy())) {
            throw new IllegalArgumentException(
                "Evidence retention must match the effective F05 policy.");
        }
        String contentHash = sha256(bytes);
        String safeName = safeName(file.getOriginalFilename(), mediaType);
        UUID replay = journal.replay(
            subject, "INVOICE_DOCUMENT", invoiceId,
            idempotencyKey, documentIntent(request, file, contentHash));
        if (replay != null) {
            return invoice(subject, invoiceId);
        }
        InvoiceRow invoice = invoiceRow(invoiceId, true);
        requireVersion(invoice, request.expectedVersion());
        if (!Set.of("DRAFT", "UPLOADED", "EVIDENCE_PENDING",
                "CHANGES_REQUESTED", "REJECTED")
            .contains(invoice.state())) {
            throw new DomainConflictException(
                "INVOICE_DOCUMENT_LOCKED",
                "This invoice state requires a governed correction version.");
        }
        UUID priorArtifact = currentArtifact(invoiceId);
        UUID artifactId = UUID.randomUUID();
        String objectVersion = "postgres-" + UUID.randomUUID();
        jdbc.update("""
            INSERT INTO f05_private_artifacts(
                id, engagement_month_id, owner_organization_id, logical_type,
                safe_name, media_type, byte_size, content_hash, object_key,
                object_version, classification, retention_class,
                scan_status, provider_status, source, uploaded_by_subject,
                supersedes_id, correlation_id
            ) VALUES (?, ?, ?, 'INVOICE_DOCUMENT', ?, ?, ?, ?, ?, ?,
                      ?, ?, 'PENDING', 'CONFIGURED', ?, ?, ?, ?)
            """, artifactId, invoice.monthId(), scope.vendorOrganizationId(),
            safeName, mediaType, bytes.length, contentHash,
            "invoices/" + scope.vendorOrganizationId() + "/"
                + invoice.monthId() + "/" + artifactId,
            objectVersion, evidenceClassification,
            policy.retentionClass(),
            clean(request.source(), 48), subject, priorArtifact,
            journal.correlationId());
        storage.store(artifactId, bytes);
        FinanceMalwareScanner.ScanResult scan =
            scanner.scan(bytes, mediaType, safeName);
        jdbc.update("""
            UPDATE f05_private_artifacts
            SET scan_status = ?, scan_engine = ?, scan_reason_code = ?,
                scanned_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, scan.status(), scan.engine(), scan.reasonCode(), artifactId);
        int nextDocumentVersion = invoice.documentVersion() + 1;
        Map<String, Object> manifest = map(
            "invoiceId", invoiceId,
            "documentVersion", nextDocumentVersion,
            "artifactId", artifactId,
            "contentHash", contentHash,
            "safeName", safeName,
            "mediaType", mediaType,
            "reason", clean(request.reason(), 1000));
        UUID priorVersion = jdbc.query("""
            SELECT id FROM invoice_versions
            WHERE invoice_id = ?
            ORDER BY version DESC LIMIT 1
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, invoiceId);
        jdbc.update("""
            INSERT INTO invoice_versions(
                id, invoice_id, version, document_artifact_id,
                metadata_manifest, metadata_hash, source, represented_at,
                created_by_subject, supersedes_id, correlation_id
            ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, CURRENT_TIMESTAMP,
                      ?, ?, ?)
            """, UUID.randomUUID(), invoiceId, nextDocumentVersion,
            artifactId, canonical.write(manifest), canonical.sha256(manifest),
            clean(request.source(), 48), subject, priorVersion,
            journal.correlationId());
        long nextVersion = invoice.version() + 1;
        jdbc.update("""
            UPDATE invoices
            SET current_version = ?, optimistic_version = ?,
                status = 'UPLOADED', current_readiness_run_id = NULL,
                current_package_version_id = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND optimistic_version = ?
            """, nextDocumentVersion, nextVersion, invoiceId, invoice.version());
        jdbc.update("""
            UPDATE invoice_readiness_runs
            SET current_result = FALSE, eligible = FALSE,
                invalidated_at = CURRENT_TIMESTAMP
            WHERE invoice_id = ? AND current_result
            """, invoiceId);
        journal.event(invoice.monthId(), "f05.invoice.document.versioned.v1",
            "INVOICE", invoiceId, nextVersion,
            map("artifactId", artifactId, "contentHash", contentHash,
                "scanStatus", scan.status(), "providerStatus", "CONFIGURED",
                "scanEngine", scan.engine(), "policyVersion", policy.label()),
            subject);
        journal.audit(invoice.monthId(), "INVOICE_DOCUMENT_VERSIONED",
            "INVOICE", invoiceId, nextVersion,
            "PASSED".equals(scan.status()) ? "SUCCESS" : "BLOCKED",
            clean(request.reason(), 100), subject,
            authority(scope, "invoice.manage"),
            List.of(map("type", "INVOICE_ARTIFACT", "id", artifactId)));
        journal.remember(subject, "INVOICE_DOCUMENT", invoiceId,
            idempotencyKey, documentIntent(request, file, contentHash),
            "INVOICE_ARTIFACT", artifactId);
        return invoice(subject, invoiceId);
    }

    @Transactional
    public Map<String, Object> evaluateReadiness(
        String subject,
        UUID invoiceId,
        String ifMatch,
        String idempotencyKey,
        ReadinessInput request
    ) {
        var scope = authorization.requireInvoice(
            subject, invoiceId, "invoice.manage",
            FinanceAuthorizationService.Party.VENDOR);
        requireIfMatch(ifMatch, request.expectedVersion());
        UUID replay = journal.replay(
            subject, "INVOICE_READINESS", invoiceId,
            idempotencyKey, request);
        if (replay != null) {
            return invoice(subject, invoiceId);
        }
        InvoiceRow invoice = invoiceRow(invoiceId, true);
        requireVersion(invoice, request.expectedVersion());
        FinanceF04EvidenceResolver.HandoffEvidence evidence =
            f04Evidence.resolve(invoice.monthId());
        FinancePolicyService.Policy policy =
            policies.active(scope.engagementId(), subject);
        Handoff handoff = new Handoff(
            evidence.handoffId(), evidence.handoffHash(),
            "READY_LOCAL", evidence.handoffCreatedAt());
        PackageRef packageRef = currentPackage(
            invoice.monthId(), invoiceId, invoice.documentVersion());
        ArtifactRef artifact = artifact(invoiceId);
        Map<String, Object> input = map(
            "invoiceId", invoiceId,
            "invoiceDocumentVersion", invoice.documentVersion(),
            "invoiceMetadataHash", invoice.metadataHash(),
            "artifactHash", artifact == null ? null : artifact.hash(),
            "artifactScanStatus", artifact == null ? null : artifact.scanStatus(),
            "handoffId", handoff == null ? null : handoff.id(),
            "handoffHash", handoff == null ? null : handoff.hash(),
            "handoffStatus", handoff == null ? "MISSING" : handoff.status(),
            "packageId", packageRef == null ? null : packageRef.id(),
            "packageVersion", packageRef == null ? null : packageRef.version(),
            "packageHash", packageRef == null ? null : packageRef.hash(),
            "policyVersion", policy.label(),
            "f04ReadinessHash", evidence.readinessHash(),
            "f04Pillars", evidence.pillars());
        String inputHash = canonical.sha256(input);
        UUID existing = jdbc.query("""
            SELECT id FROM invoice_readiness_runs
            WHERE invoice_id = ? AND input_hash = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            invoiceId, inputHash);
        if (existing != null) {
            jdbc.update("""
                UPDATE invoice_readiness_runs
                SET current_result = (id = ?)
                WHERE invoice_id = ?
                """, existing, invoiceId);
            jdbc.update("""
                UPDATE invoices
                SET current_readiness_run_id = ?,
                    optimistic_version = optimistic_version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND optimistic_version = ?
                """, existing, invoiceId, invoice.version());
            journal.remember(subject, "INVOICE_READINESS", invoiceId,
                idempotencyKey, request, "READINESS_RUN", existing);
            return invoice(subject, invoiceId);
        }
        jdbc.update("""
            UPDATE invoice_readiness_runs
            SET current_result = FALSE, eligible = FALSE,
                invalidated_at = CURRENT_TIMESTAMP
            WHERE invoice_id = ? AND current_result
            """, invoiceId);
        boolean documentReady = artifact != null
            && "PASSED".equals(artifact.scanStatus());
        boolean packageReady = packageRef != null
            && "CURRENT".equals(packageRef.status());
        List<String> readinessRules = policy.mandatoryRules();
        List<RuleResult> ruleResults = readinessRules.stream()
            .map(rule -> ruleResult(
                rule, evidence, documentReady, packageReady,
                artifact, packageRef))
            .toList();
        boolean eligible = ruleResults.stream()
            .noneMatch(RuleResult::blocking);
        String overall = eligible
            ? "PASS"
            : "BLOCKED_MISSING_EVIDENCE";
        UUID runId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO invoice_readiness_runs(
                id, invoice_id, invoice_version, package_version_id,
                handoff_id, input_manifest, input_hash, policy_version,
                overall_status, eligible, evaluated_by_subject, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?,
                      ?, ?, ?, ?)
            """, runId, invoiceId, invoice.documentVersion(),
            packageRef == null ? null : packageRef.id(),
            handoff.id(), canonical.write(input), inputHash,
            policy.label(),
            overall, eligible,
            subject, journal.correlationId());
        for (int index = 0; index < readinessRules.size(); index++) {
            String rule = readinessRules.get(index);
            RuleResult result = ruleResults.get(index);
            jdbc.update("""
                INSERT INTO invoice_readiness_results(
                    id, readiness_run_id, rule_code, result, severity,
                    owner_label, source_object_type, source_object_id,
                    source_version, source_hash, freshness_at, remediation_cta
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), runId, rule, result.status(),
                result.blocking() ? "BLOCKING" : "INFORMATION",
                result.owner(), result.sourceType(), result.sourceId(),
                result.sourceVersion(), result.sourceHash(),
                Timestamp.from(OffsetDateTime.now(clock).toInstant()),
                result.cta());
        }
        long nextVersion = invoice.version() + 1;
        jdbc.update("""
            UPDATE invoices
            SET current_readiness_run_id = ?,
                current_package_version_id = ?,
                status = ?, optimistic_version = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND optimistic_version = ?
            """, runId, packageRef == null ? null : packageRef.id(),
            eligible ? "READY_FOR_VENDOR_SUBMISSION" : "EVIDENCE_PENDING",
            nextVersion, invoiceId, invoice.version());
        journal.event(invoice.monthId(), "f05.invoice.readiness.evaluated.v1",
            "INVOICE", invoiceId, nextVersion,
            map("readinessRunId", runId, "inputHash", inputHash,
                "overallStatus", overall, "eligible", eligible), subject);
        journal.audit(invoice.monthId(), "INVOICE_READINESS_EVALUATED",
            "INVOICE", invoiceId, nextVersion,
            eligible ? "SUCCESS" : "BLOCKED", overall, subject,
            authority(scope, "invoice.manage"),
            List.of(map("type", "READINESS_RUN", "id", runId)));
        journal.remember(subject, "INVOICE_READINESS", invoiceId,
            idempotencyKey, request, "READINESS_RUN", runId);
        return invoice(subject, invoiceId);
    }

    @Transactional(noRollbackFor = DomainConflictException.class)
    public Map<String, Object> submit(
        String subject,
        UUID invoiceId,
        String ifMatch,
        String idempotencyKey,
        SubmitInvoiceInput request
    ) {
        var scope = authorization.requireInvoice(
            subject, invoiceId, "invoice.submit",
            FinanceAuthorizationService.Party.VENDOR);
        exceptionValidity.expireInvoice(invoiceId, subject);
        requireIfMatch(ifMatch, request.expectedVersion());
        UUID replay = journal.replay(
            subject, "INVOICE_SUBMIT", invoiceId,
            idempotencyKey, request);
        if (replay != null) {
            return invoice(subject, invoiceId);
        }
        InvoiceRow invoice = invoiceRow(invoiceId, true);
        requireVersion(invoice, request.expectedVersion());
        if (!request.acknowledgment()) {
            throw new IllegalArgumentException(
                "Exact-version submission acknowledgment is required.");
        }
        Boolean exact = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM invoice_readiness_runs readiness
                JOIN evidence_package_versions package
                  ON package.id = readiness.package_version_id
                WHERE readiness.id = ?
                  AND readiness.invoice_id = ?
                  AND readiness.invoice_version = ?
                  AND readiness.eligible
                  AND readiness.current_result
                  AND package.id = ?
                  AND package.version = ?
                  AND package.status = 'CURRENT'
                  AND package.invoice_id = readiness.invoice_id
                  AND package.invoice_version = readiness.invoice_version
                  AND package.invoice_document_artifact_id = (
                      SELECT version.document_artifact_id
                      FROM invoice_versions version
                      WHERE version.invoice_id = readiness.invoice_id
                        AND version.version = readiness.invoice_version
                  )
            )
            """, Boolean.class, request.readinessRunId(), invoiceId,
            invoice.documentVersion(), request.packageId(),
            request.packageVersion());
        if (!Boolean.TRUE.equals(exact)
            || !Set.of("READY_FOR_VENDOR_SUBMISSION", "EXCEPTION_ACCEPTED")
                .contains(invoice.state())) {
            throw new DomainConflictException(
                "INVOICE_NOT_READY",
                "Submission requires the exact current eligible package and readiness run.");
        }
        long nextVersion = invoice.version() + 1;
        jdbc.update("""
            UPDATE invoices
            SET status = 'SUBMITTED_TO_PROCUREMENT',
                optimistic_version = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND optimistic_version = ?
            """, nextVersion, invoiceId, invoice.version());
        journal.event(invoice.monthId(), "f05.invoice.submitted.v1",
            "INVOICE", invoiceId, nextVersion,
            map("packageId", request.packageId(),
                "packageVersion", request.packageVersion(),
                "readinessRunId", request.readinessRunId()), subject);
        journal.audit(invoice.monthId(), "INVOICE_SUBMITTED",
            "INVOICE", invoiceId, nextVersion, "SUCCESS",
            clean(request.reason(), 100), subject,
            authority(scope, "invoice.submit"),
            List.of(
                map("type", "EVIDENCE_PACKAGE", "id", request.packageId()),
                map("type", "READINESS_RUN", "id", request.readinessRunId())));
        journal.remember(subject, "INVOICE_SUBMIT", invoiceId,
            idempotencyKey, request, "INVOICE", invoiceId);
        return invoice(subject, invoiceId);
    }

    private List<Map<String, Object>> invoiceSummaries(
        String subject,
        UUID monthId,
        UUID engagementId
    ) {
        List<String> permissionSet = permissions(subject, engagementId);
        return jdbc.query("""
            SELECT invoice.id, invoice.invoice_number, invoice.status,
                   invoice.current_version, invoice.optimistic_version,
                   invoice.updated_at, month.month_start_date,
                   engagement.name, vendor.display_name, artifact.scan_status
            FROM invoices invoice
            JOIN engagement_months month
              ON month.id = invoice.engagement_month_id
            JOIN engagements engagement ON engagement.id = month.engagement_id
            JOIN organizations vendor
              ON vendor.id = invoice.vendor_organization_id
            LEFT JOIN invoice_versions version
              ON version.invoice_id = invoice.id
             AND version.version = invoice.current_version
            LEFT JOIN f05_private_artifacts artifact
              ON artifact.id = version.document_artifact_id
            WHERE invoice.engagement_month_id = ?
            ORDER BY invoice.updated_at DESC
            """, (rs, rowNum) -> map(
                "invoiceId", rs.getObject(1, UUID.class),
                "monthId", monthId,
                "invoiceNumber", rs.getString(2),
                "state", rs.getString(3),
                "documentVersion", rs.getInt(4),
                "version", rs.getLong(5),
                "updatedAt", offset(rs.getTimestamp(6)),
                "monthLabel", rs.getObject(7, LocalDate.class).toString(),
                "engagementLabel", rs.getString(8),
                "vendorLabel", rs.getString(9),
                "scanStatus", rs.getString(10),
                "freshness", "CURRENT",
                "permissions", permissionSet), monthId);
    }

    private Map<String, Object> invoiceView(String subject, InvoiceRow row) {
        UUID engagementId = jdbc.queryForObject("""
            SELECT engagement_id FROM engagement_months WHERE id = ?
            """, UUID.class, row.monthId());
        Map<String, Object> base = new LinkedHashMap<>(
            invoiceSummaries(subject, row.monthId(), engagementId).stream()
                .filter(item -> row.id().equals(item.get("invoiceId")))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("Invoice not found.")));
        base.put("etag", Long.toString(row.version()));
        base.put("readOnly", Set.of(
            "SUBMITTED_TO_PROCUREMENT", "PROCUREMENT_REVIEW",
            "APPROVED_FOR_PROCESSING", "PAYMENT_INITIATED", "PAID",
            "CLOSED", "SUPERSEDED", "CANCELLED")
            .contains(row.state()));
        base.put("representedMetadata", representedMetadata(row));
        base.put("currentDocument", document(row.id()));
        base.put("versions", versions(row.id()));
        base.put("readiness", readiness(row.id()));
        base.put("linkedPackage", row.packageId() == null
            ? null : packages.summary(subject, row.packageId()));
        base.put("reviews", reviews(row.id()));
        base.put("queries", queries(subject, row.id(), engagementId));
        base.put("exceptions", exceptions(row.id()));
        base.put("paymentTimeline", payments(row.id()));
        return base;
    }

    private Map<String, Object> representedMetadata(InvoiceRow row) {
        return map(
            "invoiceNumber", row.invoiceNumber(),
            "invoiceDate", row.invoiceDate(),
            "billingPeriodStart", row.billingStart(),
            "billingPeriodEnd", row.billingEnd(),
            "currency", row.currency(),
            "taxableValue", string(row.taxableValue()),
            "taxValue", string(row.taxValue()),
            "totalValue", string(row.totalValue()),
            "purchaseOrderReference", row.poReference(),
            "workOrderReference", row.workOrderReference());
    }

    private Map<String, Object> representedMetadata(CreateInvoiceInput request) {
        var value = request.representedMetadata();
        return map(
            "invoiceNumber", value.invoiceNumber(),
            "invoiceDate", value.invoiceDate(),
            "billingPeriodStart", value.billingPeriodStart(),
            "billingPeriodEnd", value.billingPeriodEnd(),
            "currency", value.currency(),
            "taxableValue", value.taxableValue(),
            "taxValue", value.taxValue(),
            "totalValue", value.totalValue(),
            "purchaseOrderReference", value.purchaseOrderReference(),
            "workOrderReference", value.workOrderReference(),
            "documentKind", request.documentKind(),
            "relatedInvoiceId", request.relatedInvoiceId());
    }

    private Map<String, Object> document(UUID invoiceId) {
        return jdbc.query("""
            SELECT artifact.id, artifact.safe_name, artifact.media_type,
                   artifact.byte_size, artifact.content_hash,
                   artifact.object_version, artifact.scan_status,
                   artifact.classification, artifact.retention_class,
                   artifact.recorded_at,
                   version.version < invoice.current_version
            FROM invoices invoice
            JOIN invoice_versions version
              ON version.invoice_id = invoice.id
             AND version.version = invoice.current_version
            JOIN f05_private_artifacts artifact
              ON artifact.id = version.document_artifact_id
            WHERE invoice.id = ?
            """, rs -> rs.next() ? map(
                "documentId", rs.getObject(1, UUID.class),
                "fileName", rs.getString(2),
                "mimeType", rs.getString(3),
                "sizeBytes", rs.getLong(4),
                "sha256", rs.getString(5),
                "objectVersion", rs.getString(6),
                "scanStatus", rs.getString(7),
                "classification", rs.getString(8),
                "retentionPolicy", rs.getString(9),
                "uploadedAt", offset(rs.getTimestamp(10)),
                "superseded", rs.getBoolean(11))
                : null, invoiceId);
    }

    private List<Map<String, Object>> versions(UUID invoiceId) {
        return jdbc.query("""
            SELECT version.id, version.version, invoice.invoice_type,
                   invoice.status, version.recorded_at,
                   version.created_by_subject,
                   version.metadata_manifest ->> 'reason',
                   version.supersedes_id, artifact.id, artifact.safe_name,
                   artifact.media_type, artifact.byte_size,
                   artifact.content_hash, artifact.object_version,
                   artifact.scan_status, artifact.classification,
                   artifact.retention_class, artifact.recorded_at
            FROM invoice_versions version
            JOIN invoices invoice ON invoice.id = version.invoice_id
            LEFT JOIN f05_private_artifacts artifact
              ON artifact.id = version.document_artifact_id
            WHERE version.invoice_id = ?
            ORDER BY version.version DESC
            """, (rs, rowNum) -> {
                Map<String, Object> document = rs.getObject(9) == null
                    ? null : map(
                        "documentId", rs.getObject(9, UUID.class),
                        "fileName", rs.getString(10),
                        "mimeType", rs.getString(11),
                        "sizeBytes", rs.getLong(12),
                        "sha256", rs.getString(13),
                        "objectVersion", rs.getString(14),
                        "scanStatus", rs.getString(15),
                        "classification", rs.getString(16),
                        "retentionPolicy", rs.getString(17),
                        "uploadedAt", offset(rs.getTimestamp(18)),
                        "superseded", rs.getInt(2) < currentDocumentVersion(invoiceId));
                return map(
                    "versionId", rs.getObject(1, UUID.class),
                    "version", rs.getInt(2),
                    "kind", rs.getString(3),
                    "state", rs.getString(4),
                    "createdAt", offset(rs.getTimestamp(5)),
                    "createdByDisplay", rs.getString(6),
                    "reason", rs.getString(7),
                    "supersedesVersionId", rs.getObject(8, UUID.class),
                    "document", document);
            }, invoiceId);
    }

    private Map<String, Object> readiness(UUID invoiceId) {
        ReadinessRow row = jdbc.query("""
            SELECT id, invoice_version, package_version_id, handoff_id,
                   input_hash, policy_version, overall_status, eligible,
                   current_result, evaluated_at
            FROM invoice_readiness_runs
            WHERE invoice_id = ? AND current_result
            """, rs -> rs.next() ? new ReadinessRow(
                rs.getObject(1, UUID.class), rs.getInt(2),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getBoolean(8), rs.getBoolean(9),
                offset(rs.getTimestamp(10))) : null, invoiceId);
        if (row == null) {
            return null;
        }
        List<Map<String, Object>> rules = jdbc.query("""
            SELECT id, rule_code, result, severity, owner_label,
                   source_object_type, source_object_id, source_version,
                   source_hash, freshness_at, remediation_cta
            FROM invoice_readiness_results
            WHERE readiness_run_id = ?
            ORDER BY rule_code
            """, (rs, rowNum) -> map(
                "ruleId", rs.getObject(1, UUID.class),
                "pillar", rs.getString(2),
                "label", rs.getString(2).replace('_', ' '),
                "mandatory", true,
                "status", rs.getString(3),
                "severity", "INFORMATION".equals(rs.getString(4))
                    ? "INFO" : rs.getString(4),
                "ownerDisplay", rs.getString(5),
                "remediationLabel", rs.getString(11),
                "remediationPath", remediationPath(rs.getString(2)),
                "source", rs.getObject(7) == null ? null : map(
                    "sourceType", rs.getString(6),
                    "sourceId", rs.getObject(7, UUID.class),
                    "version", rs.getString(8),
                    "checksum", rs.getString(9),
                    "provenance", "AUTHORITATIVE_VERSION",
                    "freshness", "CURRENT",
                    "temporalMode", "SNAPSHOT",
                    "representedAt", offset(rs.getTimestamp(10)),
                    "recordedAt", offset(rs.getTimestamp(10)),
                    "superseded", false),
                "exceptionId", null,
                "exceptionExpiresAt", null), row.id());
        return map(
            "runId", row.id(),
            "version", row.invoiceVersion(),
            "inputHash", row.inputHash(),
            "policyVersion", row.policyVersion(),
            "evaluatedAt", row.evaluatedAt(),
            "eligibleForSubmission", row.eligible(),
            "stale", !row.current(),
            "rules", rules,
            "overallStatus", row.overallStatus());
    }

    private List<Map<String, Object>> reviews(UUID invoiceId) {
        return jdbc.query("""
            SELECT review.id, review.decision, review.category,
                   review.comment, review.reviewed_by_subject,
                   review.invoice_version, package.version,
                   review.readiness_run_id, review.reviewed_at
            FROM procurement_reviews review
            JOIN evidence_package_versions package
              ON package.id = review.package_version_id
            WHERE review.invoice_id = ?
            ORDER BY review.reviewed_at DESC
            """, (rs, rowNum) -> map(
                "reviewId", rs.getObject(1, UUID.class),
                "version", rowNum + 1,
                "decision", rs.getString(2),
                "category", rs.getString(3),
                "comment", rs.getString(4),
                "actorDisplay", rs.getString(5),
                "authorityDisplay", "Server-derived Procurement scope",
                "invoiceVersion", rs.getInt(6),
                "packageVersion", rs.getInt(7),
                "readinessRunId", rs.getObject(8, UUID.class),
                "recordedAt", offset(rs.getTimestamp(9))), invoiceId);
    }

    private List<Map<String, Object>> queries(
        String subject,
        UUID invoiceId,
        UUID engagementId
    ) {
        boolean procurementViewer;
        try {
            authorization.requireEngagement(
                subject, engagementId, "procurement.review");
            procurementViewer = true;
        } catch (RuntimeException exception) {
            procurementViewer = false;
        }
        boolean canReadAllResponses = procurementViewer;
        return jdbc.query("""
            SELECT query.id, query.status, query.category,
                   review.comment, query.owner_subject, query.due_at,
                   query.created_at
            FROM procurement_queries query
            JOIN procurement_reviews review ON review.id = query.review_id
            WHERE query.invoice_id = ?
            ORDER BY query.created_at DESC
            """, (rs, rowNum) -> {
                UUID queryId = rs.getObject(1, UUID.class);
                String owner = rs.getString(5);
                boolean visible = canReadAllResponses || subject.equals(owner);
                return map(
                    "queryId", queryId,
                    "version", rowNum + 1,
                    "status", rs.getString(2),
                    "category", rs.getString(3),
                    "summary", rs.getString(4),
                    "ownerDisplay", owner,
                    "dueAt", offset(rs.getTimestamp(6)),
                    "createdAt", offset(rs.getTimestamp(7)),
                    "sourceCorrectionPath", "/confirmation",
                    "responses", visible
                        ? queryResponses(queryId) : List.of(),
                    "responsesRestricted", !visible);
            }, invoiceId);
    }

    private List<Map<String, Object>> queryResponses(UUID queryId) {
        return jdbc.query("""
            SELECT id, response_text, responded_by_subject, responded_at
            FROM procurement_query_responses
            WHERE query_id = ?
            ORDER BY responded_at, id
            """, (rs, rowNum) -> map(
                "responseId", rs.getObject(1, UUID.class),
                "response", rs.getString(2),
                "respondedByDisplay", rs.getString(3),
                "recordedAt", offset(rs.getTimestamp(4))), queryId);
    }

    private List<Map<String, Object>> exceptions(UUID invoiceId) {
        return jdbc.query("""
            SELECT exception.id, result.id, exception.rationale,
                   exception.second_approver_subject, exception.valid_until,
                   review.invoice_version, package.version,
                   exception.requested_at, exception.status,
                   exception.requested_by_subject,
                   exception.second_approved_at, exception.expired_at,
                   exception.policy_version_id, exception.policy_version,
                   exception.readiness_run_id,
                   exception.package_version_id
            FROM procurement_exceptions exception
            JOIN procurement_reviews review ON review.id = exception.review_id
            JOIN invoice_readiness_results result
              ON result.id = exception.readiness_result_id
            JOIN evidence_package_versions package
              ON package.id = review.package_version_id
            WHERE review.invoice_id = ?
            ORDER BY exception.requested_at DESC
            """, (rs, rowNum) -> map(
                "exceptionId", rs.getObject(1, UUID.class),
                "ruleId", rs.getObject(2, UUID.class),
                "status", rs.getString(9),
                "rationale", rs.getString(3),
                "authorityDisplay", "Server-derived Procurement exception authority",
                "secondApproverRequired",
                    "PENDING_SECOND_APPROVAL".equals(rs.getString(9))
                        || rs.getString(4) != null,
                "requestedByDisplay", rs.getString(10),
                "secondApproverDisplay", rs.getString(4),
                "validUntil", offset(rs.getTimestamp(5)),
                "invoiceVersion", rs.getInt(6),
                "packageVersion", rs.getInt(7),
                "createdAt", offset(rs.getTimestamp(8)),
                "secondApprovedAt", offset(rs.getTimestamp(11)),
                "expiredAt", offset(rs.getTimestamp(12)),
                "policyVersionId", rs.getObject(13, UUID.class),
                "policyVersion", rs.getInt(14),
                "readinessRunId", rs.getObject(15, UUID.class),
                "packageId", rs.getObject(16, UUID.class)), invoiceId);
    }

    private List<Map<String, Object>> payments(UUID invoiceId) {
        return jdbc.query("""
            SELECT id, sequence_number, status, source, sanitized_comment,
                   external_reference, status_at, expected_payment_date,
                   actual_payment_date, recorded_at, recorded_by_subject
            FROM payment_status_history
            WHERE invoice_id = ?
            ORDER BY sequence_number
            """, (rs, rowNum) -> map(
                "paymentEventId", rs.getObject(1, UUID.class),
                "version", rs.getInt(2),
                "status", rs.getString(3),
                "source", rs.getString(4),
                "provenance", "Append-only AP status fact",
                "comment", rs.getString(5),
                "externalReference", rs.getString(6),
                "statusAt", offset(rs.getTimestamp(7)),
                "expectedPaymentDate", rs.getObject(8, LocalDate.class),
                "actualPaymentDate", rs.getObject(9, LocalDate.class),
                "recordedAt", offset(rs.getTimestamp(10)),
                "recordedByDisplay", rs.getString(11)), invoiceId);
    }

    private Map<String, Object> effectiveHandoff(UUID monthId) {
        Handoff handoff = handoff(monthId);
        if (handoff == null) {
            return null;
        }
        return map(
            "contractVersion", "certification.confirmation.readiness.v1",
            "confirmationDisposition",
                "INVALIDATED".equals(handoff.status()) ? "INVALID" : "CONFIRMED",
            "source", map(
                "sourceType", "F04_HANDOFF",
                "sourceId", handoff.id(),
                "version", handoff.hash(),
                "checksum", handoff.hash(),
                "provenance", "AUTHORITATIVE_HANDOFF",
                "freshness", "INVALIDATED".equals(handoff.status())
                    ? "STALE" : "CURRENT",
                "temporalMode", "SNAPSHOT",
                "representedAt", handoff.createdAt(),
                "recordedAt", handoff.createdAt(),
                "superseded", "INVALIDATED".equals(handoff.status())));
    }

    private Handoff handoff(UUID monthId) {
        return jdbc.query("""
            SELECT id, package_hash, effective_status, created_at
            FROM effective_f05_certification_handoffs
            WHERE engagement_month_id = ?
              AND effective_status <> 'INVALIDATED'
            ORDER BY created_at DESC
            LIMIT 1
            """, rs -> rs.next() ? new Handoff(
                rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), offset(rs.getTimestamp(4))) : null, monthId);
    }

    private UUID latestAnyHandoff(UUID monthId) {
        UUID value = jdbc.query("""
            SELECT id FROM f05_certification_handoffs
            WHERE engagement_month_id = ?
            ORDER BY created_at DESC LIMIT 1
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, monthId);
        if (value == null) {
            throw new DomainConflictException(
                "F04_HANDOFF_REQUIRED",
                "A recorded F04 handoff is required before readiness can run.");
        }
        return value;
    }

    private PackageRef currentPackage(
        UUID monthId,
        UUID invoiceId,
        int invoiceVersion
    ) {
        return jdbc.query("""
            SELECT package.id, package.version, package.status,
                   package.canonical_input_hash,
                   CONCAT('f05-policy-v', COALESCE(policy.version, 0))
            FROM evidence_package_versions package
            LEFT JOIN f05_policy_versions policy
              ON policy.id = package.policy_version_id
            WHERE package.engagement_month_id = ?
              AND package.invoice_id = ?
              AND package.invoice_version = ?
              AND package.status = 'CURRENT'
            """, rs -> rs.next() ? new PackageRef(
                rs.getObject(1, UUID.class), rs.getInt(2),
                rs.getString(3), rs.getString(4), rs.getString(5))
                : null, monthId, invoiceId, invoiceVersion);
    }

    private ArtifactRef artifact(UUID invoiceId) {
        return jdbc.query("""
            SELECT artifact.id, artifact.content_hash, artifact.scan_status,
                   artifact.provider_status
            FROM invoices invoice
            JOIN invoice_versions version
              ON version.invoice_id = invoice.id
             AND version.version = invoice.current_version
            JOIN f05_private_artifacts artifact
              ON artifact.id = version.document_artifact_id
            WHERE invoice.id = ?
            """, rs -> rs.next() ? new ArtifactRef(
                rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getString(4)) : null, invoiceId);
    }

    private RuleResult ruleResult(
        String rule,
        FinanceF04EvidenceResolver.HandoffEvidence evidence,
        boolean documentReady,
        boolean packageReady,
        ArtifactRef artifact,
        PackageRef packageRef
    ) {
        boolean pass;
        String owner;
        String cta;
        String sourceType;
        UUID sourceId;
        String sourceVersion;
        String sourceHash;
        if ("INVOICE_DOCUMENT".equals(rule)) {
            pass = documentReady;
            owner = "Vendor invoice owner";
            cta = pass ? null : "Upload a structurally accepted private invoice document";
            sourceType = "INVOICE_ARTIFACT";
            sourceId = artifact == null ? null : artifact.id();
            sourceVersion = artifact == null ? null : artifact.providerStatus();
            sourceHash = artifact == null ? null : artifact.hash();
        } else if ("PACKAGE_MANIFEST".equals(rule)) {
            pass = packageReady;
            owner = "Evidence package owner";
            cta = pass ? null : "Generate the current immutable evidence package";
            sourceType = "EVIDENCE_PACKAGE";
            sourceId = packageRef == null ? null : packageRef.id();
            sourceVersion = packageRef == null
                ? null : Integer.toString(packageRef.version());
            sourceHash = packageRef == null ? null : packageRef.hash();
        } else {
            String pillarCode = switch (rule) {
                case "ROSTER_ALLOCATION" -> "ROSTER_ALLOCATION";
                case "ATTENDANCE" -> "ATTENDANCE";
                case "APPROVED_PLAN", "LINEAR_SNAPSHOT" -> "PLAN_LINEAR";
                case "DELIVERY_CERTIFICATION" -> "CERTIFICATION";
                case "VERIFIED_CONFIRMATION", "ENGAGEMENT_CONTRACT" ->
                    "CONFIRMATION_F05";
                default -> throw new IllegalArgumentException(
                    "Unsupported readiness rule " + rule);
            };
            FinanceF04EvidenceResolver.PillarFact fact =
                evidence.pillar(pillarCode);
            pass = "READY".equals(fact.status())
                && !"STALE".equals(fact.freshness());
            owner = "Month-close evidence owner";
            cta = pass ? null : "Resolve F04 confirmation or reopen invalidation";
            sourceType = "F04_READINESS_" + pillarCode;
            sourceId = fact.id();
            sourceVersion = fact.sourceVersion() == null
                ? evidence.readinessHash() : fact.sourceVersion();
            sourceHash = canonical.sha256(map(
                "factId", fact.id(), "pillar", fact.pillar(),
                "status", fact.status(), "details", fact.details()));
        }
        return new RuleResult(
            pass ? "PASS" : "BLOCKED_MISSING_EVIDENCE",
            !pass, owner, cta, sourceType, sourceId, sourceVersion, sourceHash);
    }

    private InvoiceRow invoiceRow(UUID invoiceId, boolean lock) {
        String sql = """
            SELECT invoice.id, invoice.engagement_month_id,
                   invoice.vendor_organization_id, invoice.invoice_type,
                   invoice.invoice_number, invoice.invoice_date, invoice.status,
                   invoice.current_version, invoice.optimistic_version,
                   invoice.billing_period_start, invoice.billing_period_end,
                   invoice.currency, invoice.taxable_value, invoice.tax_value,
                   invoice.total_value, invoice.po_reference,
                   invoice.work_order_reference,
                   invoice.current_package_version_id,
                   invoice.current_readiness_run_id,
                   invoice.created_by_subject,
                   version.metadata_hash
            FROM invoices invoice
            JOIN invoice_versions version
              ON version.invoice_id = invoice.id
             AND version.version = invoice.current_version
            WHERE invoice.id = ?
            """ + (lock ? " FOR UPDATE" : "");
        InvoiceRow row = jdbc.query(sql, rs -> rs.next() ? new InvoiceRow(
            rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
            rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
            rs.getObject(6, LocalDate.class), rs.getString(7),
            rs.getInt(8), rs.getLong(9),
            rs.getObject(10, LocalDate.class), rs.getObject(11, LocalDate.class),
            rs.getString(12), rs.getBigDecimal(13), rs.getBigDecimal(14),
            rs.getBigDecimal(15), rs.getString(16), rs.getString(17),
            rs.getObject(18, UUID.class), rs.getObject(19, UUID.class),
            rs.getString(20), rs.getString(21)) : null, invoiceId);
        if (row == null) {
            throw new EntityNotFoundException("Invoice not found.");
        }
        return row;
    }

    private void requireVersion(InvoiceRow row, long expected) {
        if (row.version() != expected) {
            throw new DomainConflictException(
                "VERSION_MISMATCH", "Invoice version is stale.", row.version());
        }
    }

    private void requireIfMatch(String ifMatch, long expected) {
        if (ifMatch == null) {
            throw new IllegalArgumentException("If-Match is required.");
        }
        try {
            String normalized = ifMatch.strip()
                .replaceFirst("^W/", "").replace("\"", "");
            if (Long.parseLong(normalized) != expected) {
                throw new DomainConflictException(
                    "VERSION_MISMATCH", "If-Match and request version differ.");
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "If-Match must be a numeric version.", exception);
        }
    }

    private void requireRelated(
        UUID monthId,
        UUID vendorId,
        UUID relatedId,
        String kind
    ) {
        if ("PRIMARY".equals(kind)) {
            if (relatedId != null) {
                throw new IllegalArgumentException(
                    "A primary invoice cannot reference another invoice.");
            }
            return;
        }
        Boolean valid = relatedId == null ? false : jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM invoices
                WHERE id = ? AND engagement_month_id = ?
                  AND vendor_organization_id = ?
            )
            """, Boolean.class, relatedId, monthId, vendorId);
        if (!Boolean.TRUE.equals(valid)) {
            throw new IllegalArgumentException(
                "Correction and note lineage must remain in the same vendor month.");
        }
    }

    private UUID currentArtifact(UUID invoiceId) {
        return jdbc.query("""
            SELECT document_artifact_id
            FROM invoice_versions
            WHERE invoice_id = ? AND document_artifact_id IS NOT NULL
            ORDER BY version DESC LIMIT 1
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, invoiceId);
    }

    private int currentDocumentVersion(UUID invoiceId) {
        Integer value = jdbc.queryForObject("""
            SELECT current_version FROM invoices WHERE id = ?
            """, Integer.class, invoiceId);
        return value == null ? 0 : value;
    }

    private boolean currentEligibility(UUID monthId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM invoices invoice
                JOIN invoice_readiness_runs readiness
                  ON readiness.id = invoice.current_readiness_run_id
                WHERE invoice.engagement_month_id = ?
                  AND readiness.eligible
                  AND readiness.current_result
            )
            """, Boolean.class, monthId));
    }

    private List<UUID> authorizedEngagements(String subject, String permission) {
        LocalDate today = LocalDate.now(clock);
        return jdbc.query("""
            SELECT DISTINCT engagement.id
            FROM engagements engagement
            JOIN organizations organization
              ON organization.id IN (
                  engagement.vendor_organization_id,
                  engagement.client_organization_id,
                  engagement.procurement_organization_id,
                  engagement.finance_organization_id)
            JOIN memberships membership
              ON membership.organization_id = organization.id
            JOIN user_profiles profile
              ON profile.id = membership.user_profile_id
            JOIN role_assignments assignment
              ON assignment.user_profile_id = profile.id
             AND assignment.organization_id = organization.id
            JOIN role_permissions role_permission
              ON role_permission.role_id = assignment.role_id
            JOIN permissions granted
              ON granted.id = role_permission.permission_id
            WHERE profile.identity_subject = ?
              AND profile.status = 'ACTIVE'
              AND membership.status = 'ACTIVE'
              AND membership.valid_from <= ?
              AND (membership.valid_to IS NULL OR membership.valid_to >= ?)
              AND assignment.status = 'ACTIVE'
              AND assignment.valid_from <= ?
              AND (assignment.valid_to IS NULL OR assignment.valid_to >= ?)
              AND granted.code = ?
              AND (
                  (assignment.scope_type = 'ORGANIZATION'
                   AND assignment.scope_id = organization.id)
                  OR (assignment.scope_type = 'ENGAGEMENT'
                      AND assignment.scope_id = engagement.id)
                  OR (assignment.scope_type = 'PROJECT'
                      AND EXISTS (
                          SELECT 1 FROM projects project
                          WHERE project.id = assignment.scope_id
                            AND project.engagement_id = engagement.id)))
            ORDER BY engagement.id
            """, (rs, rowNum) -> rs.getObject(1, UUID.class),
            subject, today, today, today, today, permission);
    }

    private List<String> permissions(String subject, UUID engagementId) {
        Map<String, String> mapping = Map.ofEntries(
            Map.entry("finance.read", "INVOICE_VIEW"),
            Map.entry("evidence.package.generate", "EVIDENCE_PACKAGE_GENERATE"),
            Map.entry("evidence.package.download", "EVIDENCE_PACKAGE_DOWNLOAD"),
            Map.entry("invoice.manage", "INVOICE_CREATE"),
            Map.entry("invoice.submit", "INVOICE_SUBMIT"),
            Map.entry("procurement.review", "PROCUREMENT_REVIEW"),
            Map.entry("procurement.exception", "PROCUREMENT_EXCEPTION"),
            Map.entry("payment.update", "PAYMENT_UPDATE"),
            Map.entry("report.export", "REPORT_EXPORT"),
            Map.entry("finance.audit.read", "EVIDENCE_PACKAGE_ACCESS_AUDIT"));
        List<String> result = new ArrayList<>(List.of(
            "EVIDENCE_PACKAGE_VIEW", "INVOICE_VIEW",
            "PAYMENT_VIEW", "REPORT_VIEW"));
        mapping.forEach((backend, frontend) -> {
            try {
                authorization.requireEngagement(subject, engagementId, backend);
                if (!result.contains(frontend)) {
                    result.add(frontend);
                }
                if ("INVOICE_CREATE".equals(frontend)) {
                    result.add("INVOICE_UPLOAD");
                    result.add("INVOICE_REPLACE");
                }
                if ("PROCUREMENT_REVIEW".equals(frontend)) {
                    result.add("PROCUREMENT_QUERY");
                }
            } catch (RuntimeException ignored) {
                // Permission is intentionally absent from the rendered capability view.
            }
        });
        return result;
    }

    private byte[] documentBytes(MultipartFile file, long maximumBytes) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("An invoice document is required.");
        }
        if (file.getSize() > maximumBytes) {
            throw new IllegalArgumentException(
                "Invoice document exceeds the effective F05 policy limit.");
        }
        try {
            return file.getBytes();
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                "Unable to read the invoice document.", exception);
        }
    }

    private String validateMediaType(
        MultipartFile file,
        byte[] bytes,
        Set<String> allowedTypes
    ) {
        String claimed = file.getContentType() == null
            ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        String sniffed;
        if (bytes.length >= 5
            && new String(bytes, 0, 5, StandardCharsets.US_ASCII)
                .startsWith("%PDF")) {
            sniffed = "application/pdf";
        } else if (bytes.length >= 8
            && bytes[0] == (byte) 0x89 && bytes[1] == 'P'
            && bytes[2] == 'N' && bytes[3] == 'G') {
            sniffed = "image/png";
        } else if (bytes.length >= 3
            && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8
            && bytes[2] == (byte) 0xff) {
            sniffed = "image/jpeg";
        } else {
            throw new IllegalArgumentException(
                "Invoice document content is not an allowed PDF, PNG or JPEG.");
        }
        if (!allowedTypes.contains(claimed) || !sniffed.equals(claimed)) {
            throw new IllegalArgumentException(
                "Claimed and detected invoice media types must match.");
        }
        return sniffed;
    }

    private String safeName(String original, String mediaType) {
        String extension = switch (mediaType) {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            default -> ".jpg";
        };
        String base = original == null ? "invoice" : original
            .replace('\\', '/')
            .substring(original.replace('\\', '/').lastIndexOf('/') + 1)
            .replaceAll("[^A-Za-z0-9._-]", "_")
            .replaceAll("\\.{2,}", ".");
        if (base.isBlank() || base.startsWith(".")) {
            base = "invoice" + extension;
        }
        if (!base.toLowerCase(Locale.ROOT).endsWith(extension)) {
            base = base.replaceFirst("\\.[^.]+$", "") + extension;
        }
        return base.length() <= 255 ? base : base.substring(0, 240) + extension;
    }

    private String normalizeInvoiceNumber(String value) {
        String normalized = clean(value, 160)
            .toUpperCase(Locale.ROOT)
            .replaceAll("\\s+", "");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Invoice number is required.");
        }
        return normalized;
    }

    private String classification(String value) {
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!Set.of("INTERNAL", "CONFIDENTIAL", "RESTRICTED")
            .contains(normalized)) {
            throw new IllegalArgumentException(
                "Unsupported evidence classification.");
        }
        return normalized;
    }

    private Map<String, Object> documentIntent(
        UploadDocumentMetadata request,
        MultipartFile file,
        String contentHash
    ) {
        return map(
            "expectedVersion", request.expectedVersion(),
            "classification", request.classification(),
            "retentionPolicy", request.retentionPolicy(),
            "source", request.source(),
            "reason", request.reason(),
            "fileName", file == null ? null : file.getOriginalFilename(),
            "size", file == null ? null : file.getSize(),
            "contentHash", contentHash);
    }

    private String remediationPath(String rule) {
        return switch (rule) {
            case "INVOICE_DOCUMENT" -> "/finance";
            case "PACKAGE_MANIFEST" -> "/finance";
            case "VERIFIED_CONFIRMATION" -> "/confirmation";
            default -> "/certification";
        };
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            BigDecimal result = new BigDecimal(value);
            if (result.signum() < 0) {
                throw new IllegalArgumentException(
                    "Represented invoice values cannot be negative.");
            }
            return result;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "Represented invoice value is invalid.", exception);
        }
    }

    private String string(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private String clean(String value, int limit) {
        if (value == null) {
            return null;
        }
        String cleaned = value.strip();
        return cleaned.length() <= limit ? cleaned : cleaned.substring(0, limit);
    }

    private Map<String, Object> authority(
        FinanceAuthorizationService.Scope scope,
        String permission
    ) {
        return map(
            "permission", permission,
            "engagementId", scope.engagementId(),
            "vendorOrganizationId", scope.vendorOrganizationId(),
            "clientOrganizationId", scope.clientOrganizationId(),
            "procurementOrganizationId", scope.procurementOrganizationId());
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
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
        return map(
            "items", items,
            "nextCursor", nextCursor,
            "totalCount", totalCount,
            "membershipSnapshotAt",
                OffsetDateTime.ofInstant(snapshotAt, ZoneOffset.UTC),
            "temporalMode", "SNAPSHOT_MEMBERSHIP_VALUES_LIVE_AT_READ");
    }

    private static OffsetDateTime offset(Timestamp value) {
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
    }

    private record InvoiceRow(
        UUID id,
        UUID monthId,
        UUID vendorId,
        String documentKind,
        String invoiceNumber,
        LocalDate invoiceDate,
        String state,
        int documentVersion,
        long version,
        LocalDate billingStart,
        LocalDate billingEnd,
        String currency,
        BigDecimal taxableValue,
        BigDecimal taxValue,
        BigDecimal totalValue,
        String poReference,
        String workOrderReference,
        UUID packageId,
        UUID readinessId,
        String createdBy,
        String metadataHash
    ) {
    }

    private record Handoff(
        UUID id,
        String hash,
        String status,
        OffsetDateTime createdAt
    ) {
    }

    private record PackageRef(
        UUID id,
        int version,
        String status,
        String hash,
        String policyVersion
    ) {
    }

    private record ArtifactRef(
        UUID id,
        String hash,
        String scanStatus,
        String providerStatus
    ) {
    }

    private record DownloadArtifact(
        UUID id,
        String safeName,
        String mediaType,
        String hash,
        String scanStatus
    ) {
    }

    public record InvoiceDocumentDownload(
        byte[] content,
        String mediaType,
        String safeName
    ) {
    }

    private record ReadinessRow(
        UUID id,
        int invoiceVersion,
        UUID packageId,
        UUID handoffId,
        String inputHash,
        String policyVersion,
        String overallStatus,
        boolean eligible,
        boolean current,
        OffsetDateTime evaluatedAt
    ) {
    }

    private record RuleResult(
        String status,
        boolean blocking,
        String owner,
        String cta,
        String sourceType,
        UUID sourceId,
        String sourceVersion,
        String sourceHash
    ) {
    }
}
