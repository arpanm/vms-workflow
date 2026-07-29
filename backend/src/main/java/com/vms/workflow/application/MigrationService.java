package com.vms.workflow.application;

import com.vms.workflow.api.DomainConflictException;
import com.vms.workflow.api.MigrationDtos;
import com.vms.workflow.infrastructure.CorrelationIdFilter;
import com.vms.workflow.security.MigrationAuthorizationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class MigrationService {
    private static final long MAX_BYTES = 25L * 1024 * 1024;
    private static final int MAX_ROWS = 100_000;
    private static final Pattern SAFE_FILENAME =
        Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");
    private static final Pattern COMMERCIAL_HEADER = Pattern.compile(
        "(?i)(^|_)(salary|rate|markup|margin|payroll|cost_to_company|ctc)($|_)");
    private static final Pattern EMAIL = Pattern.compile(
        "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern LINEAR_IDENTIFIER =
        Pattern.compile("^[A-Z][A-Z0-9]+-[1-9][0-9]*$");
    private static final Set<String> TERMINAL = Set.of(
        "COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED", "CANCELLED",
        "ROLLED_BACK");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MigrationTemplateRegistry templates;
    private final MigrationCsvParser parser;
    private final MigrationAuthorizationService authorization;
    private final MigrationMalwareScanner scanner;
    private final MigrationDomainAdapter domainAdapter;
    private final FinancePageCursorCodec cursors;
    private final MigrationMetrics metrics;
    private final int retentionDays;

    public MigrationService(
        JdbcTemplate jdbc,
        ObjectMapper mapper,
        MigrationTemplateRegistry templates,
        MigrationCsvParser parser,
        MigrationAuthorizationService authorization,
        MigrationMalwareScanner scanner,
        MigrationDomainAdapter domainAdapter,
        FinancePageCursorCodec cursors,
        MigrationMetrics metrics,
        @Value("${vms.migration.retention-days:2555}") int retentionDays
    ) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.templates = templates;
        this.parser = parser;
        this.authorization = authorization;
        this.scanner = scanner;
        this.domainAdapter = domainAdapter;
        this.cursors = cursors;
        this.metrics = metrics;
        this.retentionDays = Math.max(1, retentionDays);
    }

    public Map<String, Object> access(String subject, UUID engagementId) {
        List<UUID> scopes = engagementId == null
            ? authorization.authorizedEngagements(subject, "migration.read")
            : List.of(engagementId);
        if (scopes.isEmpty()) {
            throw new org.springframework.security.access.AccessDeniedException(
                "No active migration scope is assigned.");
        }
        UUID selected = scopes.getFirst();
        authorization.requireEngagement(subject, selected, "migration.read");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("engagementId", selected);
        result.put("scopes", scopes.stream()
            .map(id -> Map.of("engagementId", id))
            .toList());
        List<String> granted = new ArrayList<>();
        for (String permission : List.of(
            "read", "upload", "validate", "approve", "commit", "rollback",
            "retro")) {
            boolean allowed = authorization.has(
                subject, selected, "migration." + permission);
            result.put(permission, allowed);
            if (allowed) {
                granted.add("MIGRATION_" + permission.toUpperCase(Locale.ROOT));
            }
        }
        result.put("permissions", List.copyOf(granted));
        if (granted.contains("MIGRATION_APPROVE")) {
            try {
                result.put("approvalRole",
                    authorization.requireApprovalAuthority(
                        subject, selected).approvalRole());
            } catch (org.springframework.security.access.AccessDeniedException
                     exception) {
                result.put("approvalRole", null);
            }
        } else {
            result.put("approvalRole", null);
        }
        result.put("scopeLabel", "Authorized migration scope");
        result.put("externalAcceptance", "ACTION_REQUIRED");
        return Collections.unmodifiableMap(result);
    }

    public List<MigrationTemplateRegistry.Template> templates(
        String subject,
        UUID engagementId
    ) {
        authorization.requireEngagement(subject, engagementId, "migration.read");
        return templates.all();
    }

    public Download sample(
        String subject,
        UUID engagementId,
        String templateCode
    ) {
        authorization.requireEngagement(subject, engagementId, "migration.read");
        MigrationTemplateRegistry.Template template =
            templates.require(templateCode);
        return new Download(
            template.filename(), "text/csv; charset=UTF-8",
            templates.safeSample(templateCode));
    }

    @Transactional
    public Map<String, Object> upload(
        String subject,
        MultipartFile file,
        MigrationDtos.UploadMetadata input
    ) {
        MigrationAuthorizationService.Scope scope =
            authorization.requireEngagement(
                subject, input.engagementId(), "migration.upload");
        authorization.requireOrganizationInScope(scope, input.organizationId());
        MigrationTemplateRegistry.Template template =
            templates.require(input.templateCode());
        if (!MigrationTemplateRegistry.VERSION.equals(input.templateVersion())) {
            throw new IllegalArgumentException("FILE_TEMPLATE_VERSION_UNSUPPORTED");
        }
        byte[] bytes = secureBytes(file);
        List<MigrationCsvParser.Record> parsed = parse(bytes);
        validateHeader(template, parsed.getFirst().fields());
        String hash = sha256(bytes);
        UUID existingJob = jdbc.query("""
            SELECT job.id
            FROM migration_source_files source
            JOIN migration_jobs job ON job.source_file_id = source.id
            WHERE source.engagement_id = ?
              AND source.organization_id = ?
              AND source.template_code = ?
              AND source.template_version = ?
              AND source.sha256 = ?
            ORDER BY job.created_at
            LIMIT 1
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            input.engagementId(), input.organizationId(), template.code(),
            template.version(), hash);
        if (existingJob != null) {
            Map<String, Object> existing = job(subject, existingJob);
            if (!Boolean.valueOf(input.partialCommit()).equals(
                existing.get("partialCommit"))) {
                throw new DomainConflictException(
                    "UPLOAD_COMMIT_POLICY_MISMATCH",
                    "The identical immutable source already has a different commit policy.",
                    ((Number) existing.get("version")).longValue());
            }
            return existing;
        }

        UUID sourceId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String safeName = safeFilename(file.getOriginalFilename());
        String mediaType = normalizedMediaType(file.getContentType());
        jdbc.update("""
            INSERT INTO migration_source_files
              (id, engagement_id, organization_id, template_code,
               template_version, safe_filename, media_type, byte_size, sha256,
               scan_status, uploaded_by_subject, retention_until)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
            """, sourceId, input.engagementId(), input.organizationId(),
            template.code(), template.version(), safeName, mediaType,
            bytes.length, hash, subject, LocalDate.now().plusDays(retentionDays));
        jdbc.update("""
            INSERT INTO migration_source_blobs(source_file_id, content)
            VALUES (?, ?)
            """, sourceId, bytes);
        jdbc.update("""
            INSERT INTO migration_jobs
              (id, source_file_id, engagement_id, organization_id,
               engagement_month_id, template_code, template_version, mode,
               state, partial_commit, parent_job_id, prior_job_id,
               requested_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'UPLOADED', ?, ?, ?, ?)
            """, jobId, sourceId, input.engagementId(), input.organizationId(),
            input.engagementMonthId(), template.code(), template.version(),
            input.mode(), input.partialCommit(), input.parentJobId(),
            input.priorJobId(), subject);
        MigrationMalwareScanner.Verdict verdict =
            scanner.inspect(bytes, hash);
        metrics.recordScan(verdict.status().name());
        if (verdict.status() !=
            MigrationMalwareScanner.Verdict.Status.PENDING) {
            jdbc.update("""
                INSERT INTO migration_scan_verdicts
                  (id, source_file_id, verdict, scanner_name, scanner_version,
                   signature_version, reason_code, content_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), sourceId, verdict.status().name(),
                verdict.scannerName(), verdict.scannerVersion(),
                verdict.signatureVersion(), verdict.reasonCode(), hash);
        }
        audit(input.engagementId(), input.organizationId(), jobId,
            "MIGRATION_SOURCE_UPLOADED", subject,
            Map.of("templateCode", template.code(), "sha256", hash,
                "byteSize", bytes.length));
        return job(subject, jobId);
    }

    public Map<String, Object> jobs(
        String subject,
        UUID engagementId,
        UUID organizationId,
        int limit,
        String cursor
    ) {
        MigrationAuthorizationService.Scope scope =
            authorization.requireEngagement(
                subject, engagementId, "migration.read");
        if (organizationId != null) {
            authorization.requireOrganizationInScope(scope, organizationId);
        }
        int bounded = Math.max(1, Math.min(limit, 100));
        String resource = "migration-jobs:"
            + engagementId + ":"
            + (organizationId == null ? "*" : organizationId);
        FinancePageCursorCodec.Cursor decoded =
            cursor == null || cursor.isBlank() ? null
                : cursors.decode(
                    cursor, resource, subject, List.of(engagementId));
        Instant snapshotAt = cursors.snapshot(cursor, decoded);
        OffsetDateTime snapshotTimestamp =
            OffsetDateTime.ofInstant(snapshotAt, ZoneOffset.UTC);
        OffsetDateTime afterCreatedAt = decoded == null ? null
            : OffsetDateTime.parse(decoded.lastSortValue());
        UUID afterId = decoded == null ? null : decoded.lastId();
        List<Map<String, Object>> items = jdbc.query("""
            SELECT job.id, job.template_code, job.template_version, job.mode,
                   job.state, job.row_count, job.valid_count,
                   job.warning_count, job.invalid_count, job.committed_count,
                   job.rejected_count, job.version, job.created_at,
                   job.updated_at, source.safe_filename, source.sha256,
                   source.scan_status, job.organization_id,
                   job.engagement_month_id, job.engagement_id,
                   month.month_start_date, job.partial_commit
            FROM migration_jobs job
            JOIN migration_source_files source ON source.id = job.source_file_id
            LEFT JOIN engagement_months month ON month.id = job.engagement_month_id
            WHERE job.engagement_id = ?
              AND (?::uuid IS NULL OR job.organization_id = ?::uuid)
              AND job.created_at <= ?::timestamptz
              AND (
                ?::timestamptz IS NULL
                OR (job.created_at, job.id) < (?::timestamptz, ?::uuid)
              )
            ORDER BY job.created_at DESC, job.id DESC
            LIMIT ?
            """, (rs, rowNumber) -> jobSummary(rs), engagementId,
            organizationId, organizationId, snapshotTimestamp,
            afterCreatedAt, afterCreatedAt, afterId, bounded + 1);
        long totalCount = jdbc.queryForObject("""
            SELECT count(*)
            FROM migration_jobs job
            WHERE job.engagement_id = ?
              AND (?::uuid IS NULL OR job.organization_id = ?::uuid)
              AND job.created_at <= ?::timestamptz
            """, Long.class, engagementId, organizationId,
            organizationId, snapshotTimestamp);
        boolean hasMore = items.size() > bounded;
        if (hasMore) {
            items = new ArrayList<>(items.subList(0, bounded));
        }
        List<Map<String, Object>> decorated = items.stream()
            .map(item -> decorateJob(subject, item, false))
            .toList();
        String nextCursor = hasMore
            ? cursors.encode(
                resource, subject, List.of(engagementId), snapshotAt,
                String.valueOf(items.getLast().get("createdAt")),
                (UUID) items.getLast().get("id"))
            : "";
        return Map.of(
            "items", decorated,
            "hasMore", hasMore,
            "nextCursor", nextCursor,
            "totalCount", totalCount);
    }

    public Map<String, Object> job(String subject, UUID jobId) {
        authorization.requireJob(subject, jobId, "migration.read");
        Map<String, Object> result = jdbc.query("""
            SELECT job.id, job.template_code, job.template_version, job.mode,
                   job.state, job.row_count, job.valid_count,
                   job.warning_count, job.invalid_count, job.committed_count,
                   job.rejected_count, job.version, job.created_at,
                   job.updated_at, source.safe_filename, source.sha256,
                   source.scan_status, job.organization_id,
                   job.engagement_month_id, job.engagement_id,
                   month.month_start_date, job.partial_commit
            FROM migration_jobs job
            JOIN migration_source_files source ON source.id = job.source_file_id
            LEFT JOIN engagement_months month ON month.id = job.engagement_month_id
            WHERE job.id = ?
            """, rs -> rs.next() ? jobSummary(rs) : null, jobId);
        if (result == null) {
            throw new EntityNotFoundException("Migration resource not found.");
        }
        List<Map<String, Object>> approvals = jdbc.query("""
            SELECT id, approval_role, decision, actor_subject, job_version,
                   reconciliation_hash, created_at
            FROM migration_approvals
            WHERE job_id = ?
            ORDER BY created_at
            """, (rs, ignored) -> {
                Map<String, Object> approval = new LinkedHashMap<>();
                approval.put("approvalId", rs.getObject(1, UUID.class));
                approval.put("role", "GOVERNANCE".equals(rs.getString(2))
                    ? "GOVERNANCE_REVIEWER" : rs.getString(2));
                approval.put("decision", rs.getString(3));
                approval.put("actorSubject", rs.getString(4));
                approval.put("actorDisplay", rs.getString(4));
                approval.put("jobVersion", rs.getLong(5));
                approval.put("reconciliationHash", rs.getString(6));
                OffsetDateTime recordedAt =
                    rs.getObject(7, OffsetDateTime.class);
                approval.put("createdAt", recordedAt);
                approval.put("recordedAt", recordedAt);
                return Collections.unmodifiableMap(approval);
            }, jobId);
        Map<String, Object> resultWithApprovals =
            new LinkedHashMap<>(result);
        resultWithApprovals.put("approvals", approvals);
        Map<String, Object> detail = new LinkedHashMap<>(
            decorateJob(subject, resultWithApprovals, true));
        detail.put("terminal", TERMINAL.contains(String.valueOf(result.get("state"))));
        return Collections.unmodifiableMap(detail);
    }

    @Transactional
    public Map<String, Object> validate(
        String subject,
        UUID jobId,
        long expectedVersion,
        String idempotencyKey
    ) {
        authorization.requireJob(subject, jobId, "migration.validate");
        return validateInternal(
            subject, jobId, expectedVersion, idempotencyKey, null);
    }

    private Map<String, Object> validateInternal(
        String subject,
        UUID jobId,
        long expectedVersion,
        String idempotencyKey,
        String leaseOwner
    ) {
        JobContext job = lockedJob(jobId);
        requireVersion(job, expectedVersion);
        String scanStatus = jdbc.queryForObject("""
            SELECT source.scan_status
            FROM migration_source_files source
            JOIN migration_jobs value ON value.source_file_id = source.id
            WHERE value.id = ?
            """, String.class, jobId);
        if (!"PASSED".equals(scanStatus)) {
            throw new DomainConflictException(
                "SOURCE_SCAN_NOT_PASSED",
                "The private source remains pending or quarantined and cannot be parsed.",
                job.version());
        }
        if ("READY_TO_COMMIT".equals(job.state())) {
            return operationResult(subject, jobId, leaseOwner);
        }
        if (!Set.of("UPLOADED", "FAILED", "COMPLETED_WITH_ERRORS")
            .contains(job.state())) {
            throw conflict("JOB_STATE_CONFLICT", job);
        }
        if ("UPLOADED".equals(job.state())) {
            transition(jobId, expectedVersion, "SCANNING");
            expectedVersion++;
            transition(jobId, expectedVersion, "PARSING");
            expectedVersion++;
        } else {
            transition(jobId, expectedVersion,
                "COMPLETED_WITH_ERRORS".equals(job.state())
                    ? "VALIDATING" : "SCANNING");
            expectedVersion++;
            if ("FAILED".equals(job.state())) {
                transition(jobId, expectedVersion, "PARSING");
                expectedVersion++;
            }
        }
        transition(jobId, expectedVersion, "VALIDATING");
        expectedVersion++;
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM migration_rows WHERE job_id = ?)
            """, Boolean.class, jobId))) {
            throw new DomainConflictException(
                "VALIDATION_ATTEMPT_IMMUTABLE",
                "Prior validation evidence is immutable; create a rejected-row reprocess job.",
                expectedVersion);
        }

        byte[] content = leaseOwner == null
            ? jdbc.queryForObject("""
                SELECT blob.content
                FROM migration_source_blobs blob
                JOIN migration_jobs job
                  ON job.source_file_id = blob.source_file_id
                WHERE job.id = ?
                """, byte[].class, jobId)
            : jdbc.queryForObject(
                "SELECT f07_migration_leased_source(?, ?)",
                byte[].class, jobId, leaseOwner);
        MigrationTemplateRegistry.Template template =
            templates.require(job.templateCode());
        List<MigrationCsvParser.Record> records = parse(content);
        validateHeader(template, records.getFirst().fields());
        UUID validationAttemptId = UUID.randomUUID();
        int validationAttempt = jdbc.queryForObject("""
            SELECT COALESCE(MAX(attempt_number), 0) + 1
            FROM migration_validation_attempts WHERE job_id = ?
            """, Integer.class, jobId);
        jdbc.update("""
            INSERT INTO migration_validation_attempts
              (id, job_id, attempt_number, state, source_sha256,
               started_by_subject)
            SELECT ?, job.id, ?, 'RUNNING', source.sha256, ?
            FROM migration_jobs job
            JOIN migration_source_files source ON source.id = job.source_file_id
            WHERE job.id = ?
            """, validationAttemptId, validationAttempt, subject, jobId);
        Set<String> seenKeys = new LinkedHashSet<>();
        boolean rejectedRowReprocess = Boolean.TRUE.equals(
            jdbc.queryForObject("""
                SELECT mode = 'REPROCESS_REJECTS'
                FROM migration_jobs WHERE id = ?
                """, Boolean.class, jobId));
        Set<Integer> reprocessRowNumbers = rejectedRowReprocess
            ? new LinkedHashSet<>(jdbc.queryForList("""
                SELECT parent_row.row_number
                FROM migration_jobs child
                JOIN migration_rows parent_row
                  ON parent_row.job_id = child.parent_job_id
                WHERE child.id = ?
                  AND parent_row.state IN (
                    'INVALID', 'REJECTED', 'DUPLICATE_CONFLICT')
                ORDER BY parent_row.row_number
                """, Integer.class, jobId))
            : Set.of();
        int valid = 0;
        int warnings = 0;
        int invalid = 0;
        int processedRows = 0;
        for (int index = 1; index < records.size(); index++) {
            MigrationCsvParser.Record record = records.get(index);
            if (rejectedRowReprocess
                && !reprocessRowNumbers.contains(record.physicalLine())) {
                continue;
            }
            processedRows++;
            UUID rowId = UUID.randomUUID();
            Map<String, String> values = rowValues(template, record);
            String naturalKey = naturalKey(template, values);
            String naturalHash = sha256(naturalKey);
            String contentHash = sha256(canonical(values));
            List<Finding> findings = validateRow(job, template, values);
            if (!seenKeys.add(naturalHash)) {
                findings.add(new Finding(
                    "ERROR", "DUPLICATE_CONFLICT", null,
                    "A duplicate natural key exists in this file.", null,
                    naturalHash));
            }
            String duplicateState = duplicateState(
                job.engagementId(), template.code(), naturalHash, contentHash);
            String state;
            if (findings.stream().anyMatch(value -> "ERROR".equals(value.severity()))) {
                state = "INVALID";
                invalid++;
            } else if (duplicateState != null) {
                state = duplicateState;
                if ("DUPLICATE_CONFLICT".equals(state)) {
                    invalid++;
                    findings.add(new Finding(
                        "ERROR", "DUPLICATE_CONFLICT", null,
                        "An active fact has the same natural key and different content.",
                        null, naturalHash));
                } else {
                    warnings++;
                }
            } else if (!findings.isEmpty()) {
                state = "WARNING";
                warnings++;
            } else {
                state = "VALID";
                valid++;
            }
            String sourceType = normalizedSource(values.get("source_system"));
            String confidence = normalizedConfidence(values.get("confidence"),
                template.code());
            OffsetDateTime representedAt = representedAt(values);
            jdbc.update("""
                INSERT INTO migration_rows
                  (id, job_id, row_number, raw_sha256, natural_key_hash,
                   content_hash, state, source_type, confidence,
                   represented_at, normalized_payload, limitations,
                   validation_attempt_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?)
                """, rowId, jobId, record.physicalLine(),
                sha256(String.join("\u001f", record.fields())), naturalHash,
                contentHash, state, sourceType, confidence, representedAt,
                json(values),
                limitation(template.code(), values, confidence),
                validationAttemptId);
            for (Finding finding : findings) {
                jdbc.update("""
                    INSERT INTO migration_row_findings
                      (id, row_id, job_id, severity, code, field_name,
                       safe_message, dependency_template, dependency_key_hash,
                       validation_attempt_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), rowId, jobId, finding.severity(),
                    finding.code(), finding.field(), finding.safeMessage(),
                    finding.dependencyTemplate(), finding.dependencyKeyHash(),
                    validationAttemptId);
            }
        }
        int rowCount = processedRows;
        jdbc.update("""
            UPDATE migration_jobs
            SET row_count = ?, valid_count = ?, warning_count = ?,
                invalid_count = ?, version = version + 1
            WHERE id = ? AND state = 'VALIDATING' AND version = ?
            """, rowCount, valid, warnings, invalid, jobId, expectedVersion);
        expectedVersion++;
        if (rowCount == 0) {
            throw new DomainConflictException(
                "FILE_HAS_NO_DATA_ROWS", "The CSV contains no data rows.",
                expectedVersion);
        }
        jdbc.update("""
            UPDATE migration_validation_attempts
            SET state = 'COMPLETED', result_hash = ?,
                completed_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, sha256(jobId + ":" + rowCount + ":" + valid + ":"
                + warnings + ":" + invalid), validationAttemptId);
        JobContext reconciledJob = context(jobId);
        reconcileInternal(reconciledJob, subject);
        transition(jobId, expectedVersion, "READY_TO_COMMIT");
        checkpoint(jobId, "VALIDATING", rowCount + 1,
            sha256(jobId + ":" + rowCount + ":" + invalid));
        audit(job.engagementId(), job.organizationId(), jobId,
            "MIGRATION_VALIDATED", subject,
            Map.of("rows", rowCount, "valid", valid, "warnings", warnings,
                "invalid", invalid, "idempotencyHash", sha256(idempotencyKey)));
        metrics.recordRows(
            "validate", job.templateCode(),
            invalid == 0 ? "completed" : "completed_with_errors", rowCount);
        return operationResult(subject, jobId, leaseOwner);
    }

    public Map<String, Object> rows(
        String subject,
        UUID jobId,
        String state,
        int limit,
        int afterRow
    ) {
        authorization.requireJob(subject, jobId, "migration.read");
        int bounded = Math.max(1, Math.min(limit, 200));
        List<Map<String, Object>> items = jdbc.query("""
            SELECT row.id, row.row_number, row.state, row.source_type,
                   row.confidence, row.represented_at, row.recorded_at,
                   row.natural_key_hash, row.limitations,
                   COALESCE(jsonb_agg(jsonb_build_object(
                       'severity', finding.severity, 'code', finding.code,
                       'field', finding.field_name,
                       'message', finding.safe_message
                   )) FILTER (WHERE finding.id IS NOT NULL), '[]'::jsonb)::text
            FROM migration_rows row
            LEFT JOIN migration_row_findings finding ON finding.row_id = row.id
            WHERE row.job_id = ?
              AND (CAST(? AS TEXT) IS NULL OR row.state = ?)
              AND row.row_number > ?
            GROUP BY row.id
            ORDER BY row.row_number
            LIMIT ?
            """, (rs, ignored) -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("id", rs.getObject(1, UUID.class));
                value.put("rowNumber", rs.getInt(2));
                value.put("state", rs.getString(3));
                value.put("sourceType", rs.getString(4));
                value.put("confidence", rs.getString(5));
                value.put("representedAt", rs.getObject(6, OffsetDateTime.class));
                value.put("recordedAt", rs.getObject(7, OffsetDateTime.class));
                value.put("naturalKeyHash", rs.getString(8));
                value.put("limitations", rs.getString(9));
                value.put("findings", readJson(rs.getString(10)));
                return Collections.unmodifiableMap(value);
            }, jobId, state, state, afterRow, bounded + 1);
        boolean hasMore = items.size() > bounded;
        if (hasMore) {
            items = new ArrayList<>(items.subList(0, bounded));
        }
        return Map.of(
            "items", items,
            "hasMore", hasMore,
            "nextRow", hasMore
                ? items.getLast().get("rowNumber") : 0);
    }

    @Transactional
    public Map<String, Object> resolve(
        String subject,
        UUID jobId,
        UUID rowId,
        MigrationDtos.ResolveConflictInput input,
        String idempotencyKey
    ) {
        authorization.requireJob(subject, jobId, "migration.validate");
        JobContext job = lockedJob(jobId);
        requireVersion(job, input.expectedVersion());
        if (!"READY_TO_COMMIT".equals(job.state())) {
            throw conflict("JOB_STATE_CONFLICT", job);
        }
        Integer changed = jdbc.query("""
            SELECT 1 FROM migration_rows
            WHERE id = ? AND job_id = ? AND state = 'DUPLICATE_CONFLICT'
            FOR UPDATE
            """, rs -> rs.next() ? 1 : null, rowId, jobId);
        if (changed == null) {
            throw new EntityNotFoundException("Migration row not found.");
        }
        jdbc.update("""
            INSERT INTO migration_decisions
              (id, job_id, row_id, decision, reason, actor_subject,
               job_version, idempotency_key)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (actor_subject, idempotency_key) DO NOTHING
            """, UUID.randomUUID(), jobId, rowId, input.decision(),
            input.reason(), subject, job.version(), idempotencyKey);
        String state = switch (input.decision()) {
            case "KEEP_EXISTING", "REJECT" -> "REJECTED";
            case "VERSIONED_SUPERSEDE" -> "VALID";
            default -> throw new IllegalArgumentException("Invalid decision.");
        };
        jdbc.update("UPDATE migration_rows SET state = ? WHERE id = ?",
            state, rowId);
        jdbc.update("""
            UPDATE migration_jobs
            SET invalid_count = GREATEST(0, invalid_count - 1),
                valid_count = valid_count + CASE WHEN ? = 'VALID' THEN 1 ELSE 0 END,
                rejected_count = rejected_count
                    + CASE WHEN ? = 'REJECTED' THEN 1 ELSE 0 END,
                version = version + 1
            WHERE id = ? AND version = ?
            """, state, state, jobId, job.version());
        return job(subject, jobId);
    }

    @Transactional
    public Map<String, Object> approve(
        String subject,
        UUID jobId,
        MigrationDtos.ApprovalInput input,
        String idempotencyKey
    ) {
        authorization.requireJob(subject, jobId, "migration.approve");
        JobContext job = lockedJob(jobId);
        requireVersion(job, input.expectedVersion());
        if (!"READY_TO_COMMIT".equals(job.state())) {
            throw conflict("JOB_STATE_CONFLICT", job);
        }
        MigrationAuthorizationService.ApprovalAuthority derived =
            authorization.requireApprovalAuthority(
                subject, job.engagementId());
        String requested = normalizeApprovalRole(input.role());
        if (requested != null
            && !requested.equals(derived.approvalRole())) {
            throw new org.springframework.security.access.AccessDeniedException(
                "The requested sign-off role is not the actor's assigned authority.");
        }
        Map<String, Object> report = currentReconciliation(jobId);
        UUID reportId = (UUID) report.get("id");
        String reportHash = String.valueOf(report.get("reportHash"));
        if (input.reconciliationId() != null
            && !input.reconciliationId().equals(reportId)) {
            throw new DomainConflictException(
                "RECONCILIATION_VERSION_STALE",
                "Approval must bind the current pre-commit reconciliation.",
                job.version());
        }
        if (input.reconciliationHash() != null
            && !MessageDigest.isEqual(
                input.reconciliationHash().getBytes(StandardCharsets.US_ASCII),
                reportHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new DomainConflictException(
                "RECONCILIATION_HASH_STALE",
                "Approval must bind the current pre-commit reconciliation.",
                job.version());
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
              SELECT 1 FROM migration_approvals
              WHERE job_id = ? AND actor_subject = ?
                AND decision = 'APPROVED' AND job_version = ?
            )
            """, Boolean.class, jobId, subject, job.version()))) {
            throw new DomainConflictException(
                "MIGRATION_SOD_VIOLATION",
                "One actor cannot provide both required approvals.",
                job.version());
        }
        jdbc.update("""
            INSERT INTO migration_approvals
              (id, job_id, approval_role, decision, actor_subject,
               job_version, reason, idempotency_key, reconciliation_id,
               reconciliation_hash, authority_role_code,
               authority_organization_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (actor_subject, idempotency_key) DO NOTHING
            """, UUID.randomUUID(), jobId, derived.approvalRole(),
            input.decision(), subject, job.version(), input.reason(),
            idempotencyKey, reportId, reportHash, derived.assignmentRole(),
            derived.authorityOrganizationId());
        audit(job.engagementId(), job.organizationId(), jobId,
            "MIGRATION_APPROVAL_RECORDED", subject,
            Map.of("role", derived.approvalRole(),
                "assignmentRole", derived.assignmentRole(),
                "authorityOrganizationId",
                derived.authorityOrganizationId(),
                "reconciliationHash", reportHash,
                "decision", input.decision(),
                "jobVersion", job.version()));
        return job(subject, jobId);
    }

    @Transactional
    public Map<String, Object> commit(
        String subject,
        UUID jobId,
        long expectedVersion,
        boolean partialCommit,
        String idempotencyKey
    ) {
        authorization.requireJob(subject, jobId, "migration.commit");
        JobContext job = lockedJob(jobId);
        requireVersion(job, expectedVersion);
        if ("COMPLETED".equals(job.state())
            || "COMPLETED_WITH_ERRORS".equals(job.state())) {
            return job(subject, jobId);
        }
        if (!"READY_TO_COMMIT".equals(job.state())) {
            throw conflict("JOB_STATE_CONFLICT", job);
        }
        if (partialCommit != job.partialCommit()) {
            throw new DomainConflictException(
                "COMMIT_POLICY_MISMATCH",
                "Commit must reaffirm the immutable upload-time commit policy.",
                job.version());
        }
        if (job.invalidCount() > 0 && !partialCommit) {
            throw new DomainConflictException(
                "JOB_INVALID_ROWS_BLOCK_COMMIT",
                "Unresolved invalid rows block this all-or-nothing commit.",
                job.version());
        }
        requireDependencies(job);
        requireDualApproval(job);
        transition(jobId, job.version(), "COMMITTING");
        long committingVersion = job.version() + 1;
        List<RowContext> rows = jdbc.query("""
            SELECT id, row_number, natural_key_hash, content_hash,
                   normalized_payload::text, represented_at, source_type,
                   confidence, limitations
            FROM migration_rows
            WHERE job_id = ? AND state IN ('VALID', 'WARNING')
            ORDER BY row_number
            FOR UPDATE
            """, (rs, ignored) -> new RowContext(
                rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3),
                rs.getString(4), rs.getString(5),
                rs.getObject(6, OffsetDateTime.class), rs.getString(7),
                rs.getString(8), rs.getString(9)), jobId);
        int committed = 0;
        for (RowContext row : rows) {
            UUID current = jdbc.query("""
                SELECT id
                FROM migration_canonical_facts
                WHERE engagement_id = ? AND template_code = ?
                  AND natural_key_hash = ? AND active
                FOR UPDATE
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                job.engagementId(), job.templateCode(), row.naturalKeyHash());
            boolean supersede = current != null && Boolean.TRUE.equals(
                jdbc.queryForObject("""
                    SELECT EXISTS (
                      SELECT 1 FROM migration_decisions
                      WHERE job_id = ? AND row_id = ?
                        AND decision = 'VERSIONED_SUPERSEDE'
                    )
                    """, Boolean.class, jobId, row.id()));
            if (current != null && !supersede) {
                if (!job.partialCommit()) {
                    throw new DomainConflictException(
                        "LATE_DUPLICATE_CONFLICT",
                        "A concurrent canonical record now conflicts with this all-or-nothing batch.",
                        job.version());
                }
                jdbc.update("""
                    UPDATE migration_rows
                    SET state = 'DUPLICATE_CONFLICT'
                    WHERE id = ?
                """, row.id());
                continue;
            }
            enforceAttendanceAuthority(job, row);
            int version = 1;
            if (current != null) {
                version = jdbc.queryForObject("""
                    SELECT version + 1 FROM migration_canonical_facts
                    WHERE id = ?
                    """, Integer.class, current);
                jdbc.update("""
                    UPDATE migration_canonical_facts SET active = FALSE
                    WHERE id = ?
                    """, current);
            }
            List<MigrationDomainAdapter.DomainEffect> domainEffects =
                domainAdapter.apply(
                    job.templateCode(), job.engagementId(),
                    job.organizationId(), job.monthId(), row.payload(),
                    subject, jobId + ":" + row.id());
            UUID factId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO migration_canonical_facts
                  (id, engagement_id, organization_id, engagement_month_id,
                   template_code, natural_key_hash, content_hash,
                   business_payload, represented_at, source_type, confidence,
                   supersedes_id, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?, ?, ?, ?)
                """, factId, job.engagementId(), job.organizationId(),
                job.monthId(), job.templateCode(), row.naturalKeyHash(),
                row.contentHash(), row.payload(), row.representedAt(),
                row.sourceType(), row.confidence(), current, version);
            jdbc.update("""
                INSERT INTO migration_provenance_links
                  (fact_id, job_id, source_file_id, row_id, source_sha256,
                   represented_at, limitations)
                SELECT ?, job.id, job.source_file_id, ?, source.sha256, ?, ?
                FROM migration_jobs job
                JOIN migration_source_files source
                  ON source.id = job.source_file_id
                WHERE job.id = ?
                """, factId, row.id(), row.representedAt(),
                row.limitations(), jobId);
            for (int effectIndex = 0;
                 effectIndex < domainEffects.size();
                 effectIndex++) {
                MigrationDomainAdapter.DomainEffect effect =
                    domainEffects.get(effectIndex);
                jdbc.update("""
                    INSERT INTO migration_domain_provenance
                      (id, job_id, row_id, template_code, domain_table,
                       domain_record_id, domain_version, source_file_id,
                       source_sha256, represented_at, effect_sequence,
                       effect_kind, before_state)
                    SELECT ?, job.id, ?, job.template_code, ?, ?, ?,
                           source.id, source.sha256, ?, ?, ?, CAST(? AS JSONB)
                    FROM migration_jobs job
                    JOIN migration_source_files source
                      ON source.id = job.source_file_id
                    WHERE job.id = ?
                    """, UUID.randomUUID(), row.id(), effect.table(),
                    effect.recordId(), effect.version(), row.representedAt(),
                    effectIndex + 1, effect.kind().name(),
                    json(effect.beforeState()), jobId);
            }
            jdbc.update("""
                UPDATE migration_rows
                SET state = 'COMMITTED', committed_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, row.id());
            outbox("MIGRATION_FACT", factId, "MIGRATION_FACT_COMMITTED",
                jobId + ":" + row.id(),
                Map.of("jobId", jobId, "factId", factId,
                    "templateCode", job.templateCode(),
                    "rowNumber", row.rowNumber()));
            committed++;
        }
        int rejected = jdbc.queryForObject("""
            SELECT count(*) FROM migration_rows
            WHERE job_id = ? AND state IN (
              'INVALID', 'REJECTED', 'DUPLICATE_CONFLICT')
            """, Integer.class, jobId);
        jdbc.update("""
            UPDATE migration_jobs
            SET committed_count = ?, rejected_count = ?, version = version + 1
            WHERE id = ? AND version = ?
            """, committed, rejected, jobId, committingVersion);
        committingVersion++;
        String terminal = rejected > 0
            ? "COMPLETED_WITH_ERRORS" : "COMPLETED";
        transition(jobId, committingVersion, terminal);
        checkpoint(jobId, "COMMITTING", rows.isEmpty()
            ? 1 : rows.getLast().rowNumber(),
            sha256(jobId + ":" + committed + ":" + rejected));
        UUID reportId = (UUID) currentReconciliation(jobId).get("id");
        if (job.monthId() != null) {
            jdbc.update("""
                UPDATE engagement_months
                SET state = 'HISTORICAL_REVIEW', historical_flag = TRUE,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state IN (
                  'HISTORICAL_DRAFT', 'HISTORICAL_IMPORT_IN_PROGRESS')
                """, job.monthId());
        }
        audit(job.engagementId(), job.organizationId(), jobId,
            "MIGRATION_JOB_COMMITTED", subject,
            Map.of("committed", committed, "rejected", rejected,
                "reconciliationId", reportId,
                "idempotencyHash", sha256(idempotencyKey)));
        metrics.recordRows(
            "commit", job.templateCode(), terminal, committed + rejected);
        return job(subject, jobId);
    }

    @Transactional
    public Map<String, Object> cancel(
        String subject,
        UUID jobId,
        long expectedVersion,
        String reason,
        String idempotencyKey
    ) {
        authorization.requireJob(subject, jobId, "migration.validate");
        JobContext job = lockedJob(jobId);
        requireVersion(job, expectedVersion);
        if (TERMINAL.contains(job.state())) {
            return job(subject, jobId);
        }
        if ("COMMITTING".equals(job.state())) {
            throw new DomainConflictException(
                "COMMIT_CANCELLATION_BOUNDARY",
                "An atomic commit cannot be cancelled after its transaction began.",
                job.version());
        }
        jdbc.update("""
            INSERT INTO migration_decisions
              (id, job_id, decision, reason, actor_subject, job_version,
               idempotency_key)
            VALUES (?, ?, 'CANCEL', ?, ?, ?, ?)
            ON CONFLICT (actor_subject, idempotency_key) DO NOTHING
            """, UUID.randomUUID(), jobId, reason, subject, job.version(),
            idempotencyKey);
        transition(jobId, job.version(), "CANCELLED");
        return job(subject, jobId);
    }

    /**
     * Safely resumes scanner/validation work. Immutable cancelled history is
     * never reopened: retrying it creates a new append-only replay job.
     */
    @Transactional
    public Map<String, Object> retry(
        String subject,
        UUID jobId,
        long expectedVersion,
        String reason,
        String idempotencyKey
    ) {
        authorization.requireJob(subject, jobId, "migration.validate");
        return retryInternal(
            subject, jobId, expectedVersion, reason, idempotencyKey, null);
    }

    /**
     * Worker-only recovery entry point. It is package-private, has no
     * controller/JWT route, never calls the user authorization service and
     * requires the unguessable live lease acquired by MigrationRecoveryWorker.
     */
    @Transactional
    void retryClaimed(
        UUID jobId,
        long expectedVersion,
        String leaseOwner
    ) {
        if (leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException(
                "A live migration worker lease is required.");
        }
        Boolean liveLease = jdbc.queryForObject("""
            SELECT EXISTS (
              SELECT 1
              FROM migration_jobs
              WHERE id = ? AND version = ?
                AND lease_owner = ?
                AND lease_until > CURRENT_TIMESTAMP
            )
            """, Boolean.class, jobId, expectedVersion, leaseOwner);
        if (!Boolean.TRUE.equals(liveLease)) {
            throw new IllegalStateException(
                "Migration recovery lease is absent, stale or not owned.");
        }
        retryInternal(
            "SYSTEM:F06_RECOVERY", jobId, expectedVersion,
            "Automated recovery of an expired migration lease.",
            "f06-worker:" + jobId + ":" + expectedVersion,
            leaseOwner);
        int released = jdbc.update("""
            UPDATE migration_jobs
            SET lease_owner = NULL, lease_until = NULL,
                version = version + 1
            WHERE id = ? AND lease_owner = ?
            """, jobId, leaseOwner);
        if (released != 1) {
            throw new IllegalStateException(
                "Migration recovery lease changed before completion.");
        }
    }

    private Map<String, Object> retryInternal(
        String subject,
        UUID jobId,
        long expectedVersion,
        String reason,
        String idempotencyKey,
        String leaseOwner
    ) {
        UUID priorReplay = jdbc.query("""
            SELECT job_id FROM migration_decisions
            WHERE actor_subject = ? AND idempotency_key = ?
              AND decision = 'REPLAY'
            """, rs -> rs.next()
                ? rs.getObject(1, UUID.class) : null,
            subject, idempotencyKey);
        if (priorReplay != null) {
            metrics.recordRetry("idempotent_replay");
            return operationResult(subject, priorReplay, leaseOwner);
        }

        JobContext prior = lockedJob(jobId);
        requireVersion(prior, expectedVersion);
        if ("CANCELLED".equals(prior.state())) {
            if (leaseOwner != null) {
                throw conflict("JOB_RETRY_NOT_ALLOWED", prior);
            }
            UUID replayId = appendOnlyReplay(
                prior, subject, reason, idempotencyKey,
                "MIGRATION_CANCELLED_JOB_REPLAY_CREATED");
            metrics.recordRetry("cancelled_replay_created");
            return operationResult(subject, replayId, null);
        }
        if (!Set.of("UPLOADED", "FAILED").contains(prior.state())) {
            throw conflict("JOB_RETRY_NOT_ALLOWED", prior);
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM migration_rows WHERE job_id = ?)
            """, Boolean.class, jobId))) {
            throw new DomainConflictException(
                "VALIDATION_ATTEMPT_IMMUTABLE",
                "Retry cannot replace staged evidence; create a rejected-row "
                    + "reprocess job.",
                prior.version());
        }

        RetryState retryState = jdbc.queryForObject("""
            SELECT retry_count, dead_lettered_at IS NOT NULL
            FROM migration_jobs WHERE id = ?
            """, (rs, ignored) -> new RetryState(
                rs.getInt(1), rs.getBoolean(2)), jobId);
        int retryCount = retryState.count();
        if (retryCount >= 10 && retryState.deadLettered()
            && leaseOwner == null) {
            UUID replayId = appendOnlyReplay(
                prior, subject, reason, idempotencyKey,
                "MIGRATION_DEAD_LETTER_REPLAY_CREATED");
            metrics.recordRetry("dead_letter_replay_created");
            return operationResult(subject, replayId, null);
        }
        if (retryCount >= 10) {
            throw new DomainConflictException(
                "MIGRATION_RETRY_EXHAUSTED",
                "The bounded retry budget is exhausted; operator review is "
                    + "required.",
                prior.version());
        }
        boolean exhausted = retryCount + 1 >= 10;
        int changed = leaseOwner == null
            ? jdbc.update("""
                UPDATE migration_jobs
                SET retry_count = retry_count + 1,
                    dead_lettered_at = CASE WHEN retry_count + 1 >= 10
                        THEN CURRENT_TIMESTAMP ELSE dead_lettered_at END,
                    lease_owner = NULL, lease_until = NULL,
                    version = version + 1
                WHERE id = ? AND version = ? AND retry_count < 10
                """, jobId, prior.version())
            : jdbc.update("""
                UPDATE migration_jobs
                SET retry_count = retry_count + 1,
                    dead_lettered_at = CASE WHEN retry_count + 1 >= 10
                        THEN CURRENT_TIMESTAMP ELSE dead_lettered_at END,
                    version = version + 1
                WHERE id = ? AND version = ? AND retry_count < 10
                  AND lease_owner = ?
                  AND lease_until > CURRENT_TIMESTAMP
                """, jobId, prior.version(), leaseOwner);
        if (changed != 1) {
            throw new DomainConflictException(
                "ETAG_MISMATCH", "The migration job version is stale.",
                context(jobId).version());
        }
        long retryVersion = prior.version() + 1;
        jdbc.update("""
            INSERT INTO migration_decisions
              (id, job_id, decision, reason, actor_subject, job_version,
               idempotency_key)
            VALUES (?, ?, 'REPLAY', ?, ?, ?, ?)
            """, UUID.randomUUID(), jobId, reason, subject,
            prior.version(), idempotencyKey);
        if (exhausted) {
            audit(prior.engagementId(), prior.organizationId(), jobId,
                "MIGRATION_RETRY_DEAD_LETTERED", subject,
                Map.of("retryCount", retryCount + 1,
                    "idempotencyHash", sha256(idempotencyKey)));
            metrics.recordRetry("dead_lettered");
            return operationResult(subject, jobId, leaseOwner);
        }

        SourceScan source = sourceScan(jobId, leaseOwner);
        if ("PENDING".equals(source.status())) {
            MigrationMalwareScanner.Verdict verdict =
                scanner.inspect(source.content(), source.sha256());
            metrics.recordScan(verdict.status().name());
            if (verdict.status()
                != MigrationMalwareScanner.Verdict.Status.PENDING) {
                jdbc.update("""
                    INSERT INTO migration_scan_verdicts
                      (id, source_file_id, verdict, scanner_name,
                       scanner_version, signature_version, reason_code,
                       content_sha256)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), source.sourceId(),
                    verdict.status().name(), verdict.scannerName(),
                    verdict.scannerVersion(), verdict.signatureVersion(),
                    verdict.reasonCode(), source.sha256());
                source = new SourceScan(
                    source.sourceId(), verdict.status().name(),
                    source.sha256(), source.content());
            }
        }
        checkpoint(jobId, "SCANNING", 1,
            sha256(jobId + ":" + source.sha256() + ":" + source.status()
                + ":" + (retryCount + 1)));
        if ("PENDING".equals(source.status())) {
            metrics.recordRetry("scan_pending");
            return operationResult(subject, jobId, leaseOwner);
        }
        if (!"PASSED".equals(source.status())) {
            metrics.recordRetry("scan_rejected");
            return operationResult(subject, jobId, leaseOwner);
        }
        metrics.recordRetry("validation_started");
        return validateInternal(
            subject, jobId, retryVersion,
            idempotencyKey + ":validation", leaseOwner);
    }

    @Transactional
    public Map<String, Object> reprocess(
        String subject,
        UUID jobId,
        long expectedVersion,
        String idempotencyKey
    ) {
        authorization.requireJob(subject, jobId, "migration.validate");
        JobContext prior = lockedJob(jobId);
        requireVersion(prior, expectedVersion);
        if (!Set.of("COMPLETED_WITH_ERRORS", "FAILED")
            .contains(prior.state())) {
            throw conflict("JOB_REPROCESS_NOT_ALLOWED", prior);
        }
        UUID newId = jdbc.query("""
            SELECT id FROM migration_jobs
            WHERE source_file_id = ? AND mode = 'REPROCESS_REJECTS'
              AND parent_job_id = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            prior.sourceFileId(), jobId);
        if (newId == null) {
            newId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO migration_jobs
                  (id, source_file_id, engagement_id, organization_id,
                   engagement_month_id, template_code, template_version,
                   mode, state, partial_commit, parent_job_id, prior_job_id,
                   requested_by_subject)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'REPROCESS_REJECTS',
                        'UPLOADED', ?, ?, ?, ?)
                """, newId, prior.sourceFileId(), prior.engagementId(),
                prior.organizationId(), prior.monthId(), prior.templateCode(),
                MigrationTemplateRegistry.VERSION, prior.partialCommit(),
                jobId, jobId, subject);
            audit(prior.engagementId(), prior.organizationId(), newId,
                "MIGRATION_REPROCESS_CREATED", subject,
                Map.of("parentJobId", jobId,
                    "idempotencyHash", sha256(idempotencyKey)));
        }
        return job(subject, newId);
    }

    private UUID appendOnlyReplay(
        JobContext prior,
        String subject,
        String reason,
        String idempotencyKey,
        String auditEvent
    ) {
        UUID replayId = jdbc.query("""
            SELECT id FROM migration_jobs
            WHERE source_file_id = ? AND parent_job_id = ?
              AND prior_job_id = ?
            ORDER BY created_at LIMIT 1
            """, rs -> rs.next()
                ? rs.getObject(1, UUID.class) : null,
            prior.sourceFileId(), prior.id(), prior.id());
        if (replayId == null) {
            replayId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO migration_jobs
                  (id, source_file_id, engagement_id, organization_id,
                   engagement_month_id, template_code, template_version,
                   mode, state, partial_commit, parent_job_id, prior_job_id,
                   requested_by_subject)
                SELECT ?, source_file_id, engagement_id, organization_id,
                       engagement_month_id, template_code, template_version,
                       mode, 'UPLOADED', partial_commit, id, id, ?
                FROM migration_jobs WHERE id = ?
                """, replayId, subject, prior.id());
            audit(prior.engagementId(), prior.organizationId(), replayId,
                auditEvent, subject,
                Map.of("priorJobId", prior.id(),
                    "reasonHash", sha256(reason)));
        }
        jdbc.update("""
            INSERT INTO migration_decisions
              (id, job_id, decision, reason, actor_subject, job_version,
               idempotency_key)
            VALUES (?, ?, 'REPLAY', ?, ?, 1, ?)
            """, UUID.randomUUID(), replayId, reason, subject,
            idempotencyKey);
        return replayId;
    }

    private SourceScan sourceScan(UUID jobId, String leaseOwner) {
        if (leaseOwner == null) {
            return jdbc.queryForObject("""
                SELECT source.id, source.scan_status, source.sha256,
                       blob.content
                FROM migration_jobs job
                JOIN migration_source_files source
                  ON source.id = job.source_file_id
                JOIN migration_source_blobs blob
                  ON blob.source_file_id = source.id
                WHERE job.id = ?
                """, (rs, ignored) -> new SourceScan(
                    rs.getObject(1, UUID.class), rs.getString(2),
                    rs.getString(3), rs.getBytes(4)), jobId);
        }
        return jdbc.queryForObject("""
            SELECT source.id, source.scan_status, source.sha256,
                   f07_migration_leased_source(job.id, ?)
            FROM migration_jobs job
            JOIN migration_source_files source
              ON source.id = job.source_file_id
            WHERE job.id = ?
            """, (rs, ignored) -> new SourceScan(
                rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getBytes(4)), leaseOwner, jobId);
    }

    private Map<String, Object> operationResult(
        String subject,
        UUID jobId,
        String leaseOwner
    ) {
        if (leaseOwner == null) {
            return job(subject, jobId);
        }
        return jdbc.queryForObject("""
            SELECT id, state, version
            FROM migration_jobs
            WHERE id = ? AND lease_owner = ?
              AND lease_until > CURRENT_TIMESTAMP
            """, (rs, ignored) -> Map.of(
                "id", rs.getObject(1, UUID.class),
                "state", rs.getString(2),
                "version", rs.getLong(3)),
            jobId, leaseOwner);
    }

    @Transactional
    public Map<String, Object> rollback(
        String subject,
        UUID jobId,
        MigrationDtos.RollbackInput input,
        String idempotencyKey
    ) {
        authorization.requireJob(subject, jobId, "migration.rollback");
        JobContext job = lockedJob(jobId);
        requireVersion(job, input.expectedVersion());
        if ("ROLLED_BACK".equals(job.state())) {
            return job(subject, jobId);
        }
        if (!Set.of("COMPLETED", "COMPLETED_WITH_ERRORS").contains(job.state())) {
            throw conflict("ROLLBACK_STATE_CONFLICT", job);
        }
        boolean consumed = job.monthId() != null && Boolean.TRUE.equals(
            jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM attendance_snapshot_versions
                    WHERE engagement_month_id = ?
                  UNION ALL
                  SELECT 1 FROM evidence_package_versions
                    WHERE engagement_month_id = ?
                  UNION ALL
                  SELECT 1 FROM invoices
                    WHERE engagement_month_id = ?
                      AND NOT EXISTS (
                        SELECT 1
                        FROM migration_domain_provenance provenance
                        WHERE provenance.job_id = ?
                          AND provenance.domain_table = 'invoices'
                          AND provenance.domain_record_id = invoices.id
                          AND provenance.active
                      )
                )
                """, Boolean.class, job.monthId(), job.monthId(),
                job.monthId(), jobId));
        UUID actionId = UUID.randomUUID();
        if (consumed) {
            jdbc.update("""
                INSERT INTO migration_rollback_actions
                  (id, job_id, action, reason, actor_subject, job_version,
                   idempotency_key)
                VALUES (?, ?, 'DENIED_REOPEN_REQUIRED', ?, ?, ?, ?)
                """, actionId, jobId, input.reason(), subject, job.version(),
                idempotencyKey);
            throw new DomainConflictException(
                "ROLLBACK_REQUIRES_REOPEN",
                "The batch is consumed by governed downstream evidence; use reopen/version correction.",
                job.version());
        }
        jdbc.update("""
            INSERT INTO migration_rollback_actions
              (id, job_id, action, reason, actor_subject, job_version,
               idempotency_key)
            VALUES (?, ?, 'COMPENSATE', ?, ?, ?, ?)
            """, actionId, jobId, input.reason(), subject, job.version(),
            idempotencyKey);
        jdbc.update("""
            UPDATE migration_canonical_facts fact
            SET active = FALSE, rollback_action_id = ?
            FROM migration_provenance_links provenance
            WHERE provenance.fact_id = fact.id
              AND provenance.job_id = ? AND fact.active
            """, actionId, jobId);
        List<CompensableEffect> effects = jdbc.query("""
            SELECT provenance.id, provenance.domain_table,
                   provenance.domain_record_id,
                   provenance.domain_version,
                   provenance.effect_kind,
                   provenance.before_state::text
            FROM migration_domain_provenance provenance
            JOIN migration_rows row ON row.id = provenance.row_id
            WHERE provenance.job_id = ? AND provenance.active
            ORDER BY row.row_number DESC,
                     provenance.effect_sequence DESC,
                     provenance.recorded_at DESC
            FOR UPDATE OF provenance
            """, (rs, ignored) -> new CompensableEffect(
                rs.getObject(1, UUID.class), rs.getString(2),
                rs.getObject(3, UUID.class), rs.getInt(4),
                MigrationDomainAdapter.EffectKind.valueOf(rs.getString(5)),
                mapper.convertValue(
                    mapper.readTree(rs.getString(6)), Map.class)), jobId);
        for (CompensableEffect effect : effects) {
            domainAdapter.compensate(
                new MigrationDomainAdapter.DomainEffect(
                    effect.table(), effect.recordId(), effect.version(),
                    effect.recordId(), effect.kind(), effect.beforeState()),
                actionId);
            UUID compensationId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO migration_domain_compensations
                  (id, rollback_action_id, provenance_id, domain_table,
                   domain_record_id, compensation_kind,
                   compensated_by_subject)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, compensationId, actionId, effect.provenanceId(),
                effect.table(), effect.recordId(),
                effect.kind() == MigrationDomainAdapter.EffectKind.UPDATE
                    ? "RESTORE_PREVIOUS"
                    : "invoices".equals(effect.table())
                        ? "DEACTIVATE_INSERTED" : "DELETE_INSERTED",
                subject);
            jdbc.update("""
                UPDATE migration_domain_provenance
                SET active = FALSE, compensation_record_id = ?,
                    compensated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND active
                """, compensationId, effect.provenanceId());
        }
        jdbc.update("""
            DELETE FROM migration_attendance_authorities
            WHERE job_id = ?
            """, jobId);
        transition(jobId, job.version(), "ROLLED_BACK");
        outbox("MIGRATION_JOB", jobId, "MIGRATION_BATCH_COMPENSATED",
            "rollback:" + jobId,
            Map.of("jobId", jobId, "actionId", actionId));
        audit(job.engagementId(), job.organizationId(), jobId,
            "MIGRATION_BATCH_COMPENSATED", subject,
            Map.of("actionId", actionId,
                "reasonCode", "AUTHORIZED_UNCONSUMED_COMPENSATION"));
        return job(subject, jobId);
    }

    public Map<String, Object> reconciliation(
        String subject,
        UUID jobId
    ) {
        authorization.requireJob(subject, jobId, "migration.read");
        Map<String, Object> result = jdbc.query("""
            SELECT report.id, report.version, report.report_hash,
                   report.source_hashes::text, report.counts::text,
                   report.coverage::text, report.exceptions::text,
                   report.canonical_checksum, report.created_at
            FROM migration_reconciliation_reports report
            WHERE report.job_id = ?
            ORDER BY report.version DESC LIMIT 1
            """, rs -> rs.next() ? reconciliationMap(rs) : null, jobId);
        if (result == null) {
            throw new EntityNotFoundException(
                "Migration reconciliation not found.");
        }
        UUID reportId = (UUID) result.get("id");
        List<Map<String, Object>> signoffs = jdbc.query("""
            SELECT signoff_role, actor_subject, decision, created_at
            FROM migration_reconciliation_signoffs
            WHERE report_id = ?
            ORDER BY created_at
            """, (rs, ignored) -> Map.of(
                "role", rs.getString(1),
                "actorSubject", rs.getString(2),
                "decision", rs.getString(3),
                "createdAt", rs.getObject(4, OffsetDateTime.class)), reportId);
        Map<String, Object> withSignoffs = new LinkedHashMap<>(result);
        withSignoffs.put("signOffs", signoffs);
        return Collections.unmodifiableMap(withSignoffs);
    }

    @Transactional
    public Map<String, Object> signOff(
        String subject,
        UUID reportId,
        MigrationDtos.SignOffInput input,
        String idempotencyKey
    ) {
        authorization.requireReport(subject, reportId, "migration.approve");
        UUID reportJobId = jdbc.queryForObject("""
            SELECT job_id FROM migration_reconciliation_reports WHERE id = ?
            """, UUID.class, reportId);
        JobContext reportJob = context(reportJobId);
        MigrationAuthorizationService.ApprovalAuthority derived =
            authorization.requireApprovalAuthority(
                subject, reportJob.engagementId());
        String requestedRole = normalizeApprovalRole(input.role());
        if (requestedRole != null
            && !requestedRole.equals(derived.approvalRole())) {
            throw new org.springframework.security.access.AccessDeniedException(
                "The requested sign-off role is not the actor's assigned authority.");
        }
        String currentHash = jdbc.query("""
            SELECT report_hash FROM migration_reconciliation_reports
            WHERE id = ?
            """, rs -> rs.next() ? rs.getString(1) : null, reportId);
        if (currentHash == null) {
            throw new EntityNotFoundException(
                "Migration reconciliation not found.");
        }
        if (!MessageDigest.isEqual(
            currentHash.getBytes(StandardCharsets.US_ASCII),
            input.reportHash().getBytes(StandardCharsets.US_ASCII))) {
            throw new DomainConflictException(
                "RECONCILIATION_HASH_STALE",
                "The reconciliation changed; review and sign the current hash.");
        }
        try {
            jdbc.update("""
                INSERT INTO migration_reconciliation_signoffs
                  (id, report_id, report_hash, signoff_role, actor_subject,
                   decision, reason, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (actor_subject, idempotency_key) DO NOTHING
                """, UUID.randomUUID(), reportId, currentHash,
                derived.approvalRole(),
                subject, input.decision(), input.reason(), idempotencyKey);
        } catch (DataIntegrityViolationException exception) {
            throw new DomainConflictException(
                "MIGRATION_SOD_VIOLATION",
                "Reconciliation sign-off actors must be distinct.");
        }
        return reconciliation(subject, reportJobId);
    }

    @Transactional
    public Map<String, Object> retro(
        String subject,
        MigrationDtos.RetroRequestInput input,
        String idempotencyKey
    ) {
        authorization.requireEngagement(
            subject, input.engagementId(), "migration.retro");
        if (input.representedMonth().isBefore(LocalDate.of(2026, 6, 1))
            || input.representedMonth().getDayOfMonth() != 1) {
            throw new IllegalArgumentException("Historical month is invalid.");
        }
        if (input.originalActorUnavailable()
            && (input.delegationEvidenceReference() == null
                || input.delegationEvidenceReference().isBlank())) {
            throw new IllegalArgumentException(
                "Delegation evidence is required for an unavailable approver.");
        }
        UUID existing = jdbc.query("""
            SELECT id FROM migration_retro_requests
            WHERE requested_by_subject = ? AND idempotency_key = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            subject, idempotencyKey);
        UUID id = existing == null ? UUID.randomUUID() : existing;
        if (existing == null) {
            jdbc.update("""
                INSERT INTO migration_retro_requests
                  (id, engagement_id, engagement_month_id, request_type,
                   state, represented_month, reason,
                   original_actor_unavailable,
                   delegation_evidence_reference, requested_by_subject,
                   idempotency_key)
                VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?)
                """, id, input.engagementId(), input.engagementMonthId(),
                input.requestType(), input.representedMonth(), input.reason(),
                input.originalActorUnavailable(),
                input.delegationEvidenceReference(), subject, idempotencyKey);
            outbox("MIGRATION_RETRO_REQUEST", id,
                "HISTORICAL_RETRO_REQUESTED", "retro:" + id,
                Map.of("requestId", id, "requestType", input.requestType(),
                    "representedMonth", input.representedMonth().toString(),
                    "decisionTimestampPolicy", "CURRENT_AUTHENTICATED_TIME"));
        }
        return jdbc.query("""
            SELECT id, request_type, state, represented_month, reason,
                   original_actor_unavailable, procurement_notification_state,
                   created_at, decision_at, version
            FROM migration_retro_requests WHERE id = ?
            """, rs -> {
                if (!rs.next()) {
                    throw new EntityNotFoundException("Retro request not found.");
                }
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("id", rs.getObject(1, UUID.class));
                value.put("requestType", rs.getString(2));
                value.put("state", rs.getString(3));
                value.put("representedMonth", rs.getObject(4, LocalDate.class));
                value.put("reason", rs.getString(5));
                value.put("originalActorUnavailable", rs.getBoolean(6));
                value.put("procurementNotificationState", rs.getString(7));
                value.put("createdAt", rs.getObject(8, OffsetDateTime.class));
                value.put("decisionAt", rs.getObject(9, OffsetDateTime.class));
                value.put("version", rs.getLong(10));
                return Collections.unmodifiableMap(value);
            }, id);
    }

    public Download errors(String subject, UUID jobId) {
        authorization.requireJob(subject, jobId, "migration.audit.read");
        JobContext job = context(jobId);
        StringBuilder csv = new StringBuilder(
            "row_number,severity,code,field,message\r\n");
        jdbc.query("""
            SELECT row.row_number, finding.severity, finding.code,
                   COALESCE(finding.field_name, ''),
                   finding.safe_message
            FROM migration_row_findings finding
            JOIN migration_rows row ON row.id = finding.row_id
            WHERE finding.job_id = ?
            ORDER BY row.row_number, finding.id
            """, rs -> {
                csv.append(rs.getInt(1)).append(',')
                    .append(csvCell(rs.getString(2))).append(',')
                    .append(csvCell(rs.getString(3))).append(',')
                    .append(csvCell(rs.getString(4))).append(',')
                    .append(csvCell(rs.getString(5))).append("\r\n");
            }, jobId);
        audit(job.engagementId(), job.organizationId(), jobId,
            "MIGRATION_ERROR_REPORT_DOWNLOADED", subject, Map.of());
        return new Download(
            "migration-errors-" + jobId + ".csv",
            "text/csv; charset=UTF-8",
            csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    public List<Map<String, Object>> auditTrail(
        String subject,
        UUID jobId
    ) {
        authorization.requireJob(subject, jobId, "migration.audit.read");
        return jdbc.query("""
            SELECT event_type, actor_subject, metadata::text, correlation_id,
                   created_at
            FROM migration_audit_events
            WHERE job_id = ?
            ORDER BY created_at, id
            """, (rs, ignored) -> Map.of(
                "eventType", rs.getString(1),
                "actorSubject", rs.getString(2),
                "metadata", readJson(rs.getString(3)),
                "correlationId", rs.getObject(4, UUID.class),
                "createdAt", rs.getObject(5, OffsetDateTime.class)),
            jobId);
    }

    private List<Finding> validateRow(
        JobContext job,
        MigrationTemplateRegistry.Template template,
        Map<String, String> values
    ) {
        List<Finding> findings = new ArrayList<>();
        required(values, "template_version", findings);
        if (!MigrationTemplateRegistry.VERSION.equals(values.get("template_version"))) {
            findings.add(error("FILE_TEMPLATE_VERSION_UNSUPPORTED",
                "template_version", "Template version must be exactly 1."));
        }
        if (values.containsKey("source_system")) {
            required(values, "source_system", findings);
            if (!MigrationTemplateRegistry.SOURCE_TYPES.contains(
                normalizedSource(values.get("source_system")))) {
                findings.add(error("FIELD_INVALID_ENUM", "source_system",
                    "Source type is not in the approved registry."));
            }
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String field = entry.getKey();
            String value = entry.getValue();
            if (field.endsWith("_email") && !value.isBlank()
                && !EMAIL.matcher(value).matches()) {
                findings.add(error("FIELD_INVALID_EMAIL", field,
                    "Email format is invalid."));
            }
            if ((field.equals("billing_month")
                || field.equals("attendance_date")
                || field.equals("leave_date")
                || field.equals("override_date")
                || field.equals("holiday_date"))
                && !value.isBlank() && beforeMigrationStart(value)) {
                findings.add(error("TEMPORAL_OUTSIDE_ENGAGEMENT", field,
                    "Historical transaction dates must be on or after 2026-06-01."));
            }
            if (hasUnsafeControls(value)) {
                findings.add(error("FIELD_UNSAFE_TEXT", field,
                    "Control or markup content is not accepted."));
            }
        }
        validateReferences(job, values, findings);
        switch (template.code()) {
            case "01_employees" -> validateEmployee(values, findings);
            case "02_employee_allocations" ->
                validateAllocation(values, findings);
            case "05_leave_balances" -> {
                if (values.containsKey("final_balance")) {
                    findings.add(error("FILE_HEADER_MISMATCH", "final_balance",
                        "Leave balances must be immutable ledger entries."));
                }
                decimalPositive(values, "quantity_days", findings);
            }
            case "06_leave_requests" ->
                decimalPositive(values, "quantity_days", findings);
            case "07a_attendance_punches" ->
                validatePunch(values, findings);
            case "07b_attendance_daily" ->
                validateDaily(values, findings);
            case "09_deliverable_linear_links" ->
                validateLinear(values, findings);
            case "10_delivery_certifications" ->
                validateCertification(values, findings);
            case "11_business_confirmations" ->
                validateConfirmation(values, findings);
            case "12_invoices" -> validateInvoice(values, findings);
            case "13_approval_history" ->
                findings.add(new Finding(
                    "WARNING", "APPROVAL_AUTHORITY_REVIEW_REQUIRED",
                    "actor_email",
                    "Historical approval authority requires independent verification.",
                    null, null));
            default -> {
                // Common and referential rules are complete for this template.
            }
        }
        for (String dependency : template.dependencies()) {
            if (!hasDependency(job, dependency)) {
                findings.add(new Finding(
                    "ERROR", "REFERENCE_PREDECESSOR_JOB_MISSING", null,
                    "A required predecessor is unresolved.",
                    dependency, null));
            }
        }
        return findings;
    }

    private void validateReferences(
        JobContext job,
        Map<String, String> values,
        List<Finding> findings
    ) {
        String engagementCode = values.get("engagement_code");
        if (engagementCode != null && !engagementCode.isBlank()
            && !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM engagements
                  WHERE id = ? AND engagement_code = ?
                )
                """, Boolean.class, job.engagementId(), engagementCode))) {
            findings.add(error("REFERENCE_ENGAGEMENT_NOT_FOUND",
                "engagement_code", "Engagement reference was not resolved."));
        }
        String organizationCode = firstNonBlank(
            values.get("organization_code"),
            values.get("vendor_organization_code"));
        if (organizationCode != null && !Boolean.TRUE.equals(
            jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM organizations WHERE id = ? AND code = ?
                )
                """, Boolean.class, job.organizationId(), organizationCode))) {
            findings.add(error("REFERENCE_ORGANIZATION_NOT_FOUND",
                "organization_code",
                "Organization reference was not resolved in this scope."));
        }
        String projectCode = values.get("project_code");
        if (projectCode != null && !projectCode.isBlank()
            && !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM projects
                  WHERE engagement_id = ? AND project_code = ?
                )
                """, Boolean.class, job.engagementId(), projectCode))) {
            findings.add(error("REFERENCE_PROJECT_NOT_FOUND", "project_code",
                "Project reference was not resolved in this engagement."));
        }
        String employee = values.get("employee_number");
        if (employee != null && !employee.isBlank()
            && !"01_employees".equals(job.templateCode())
            && !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1
                  FROM employees employee
                  WHERE employee.organization_id = ?
                    AND employee.employee_number = ?
                  UNION ALL
                  SELECT 1
                  FROM migration_canonical_facts fact
                  WHERE fact.engagement_id = ?
                    AND fact.template_code = '01_employees'
                    AND fact.active
                    AND fact.business_payload->>'employee_number' = ?
                )
                """, Boolean.class, job.organizationId(), employee,
                job.engagementId(), employee))) {
            findings.add(error("REFERENCE_EMPLOYEE_NOT_FOUND",
                "employee_number",
                "Employee reference was not resolved in this scope."));
        }
    }

    private void validateEmployee(
        Map<String, String> values,
        List<Finding> findings
    ) {
        for (String field : List.of(
            "organization_code", "employee_number", "first_name", "last_name",
            "display_name", "work_email", "join_date", "employment_status",
            "timezone", "working_calendar_code", "attendance_policy_code",
            "leave_policy_code", "attendance_source_mode",
            "activation_status")) {
            required(values, field, findings);
        }
        LocalDate join = date(values.get("join_date"), "join_date", findings);
        LocalDate exit = date(values.get("exit_date"), "exit_date", findings);
        if (join != null && exit != null && exit.isBefore(join)) {
            findings.add(error("TEMPORAL_EMPLOYMENT_DATES_INVALID",
                "exit_date", "Exit date must not precede join date."));
        }
        String manager = values.get("manager_employee_number");
        if (manager != null && manager.equals(values.get("employee_number"))) {
            findings.add(error("EMPLOYEE_MANAGER_CYCLE",
                "manager_employee_number",
                "An employee cannot manage themselves."));
        }
        zone(values.get("timezone"), "timezone", findings);
    }

    private void validateAllocation(
        Map<String, String> values,
        List<Finding> findings
    ) {
        decimalPositive(values, "allocation_percent", findings);
        try {
            if (Double.parseDouble(values.getOrDefault(
                    "allocation_percent", "0")) > 100
                && values.getOrDefault("override_reason", "").isBlank()) {
                findings.add(error("ALLOCATION_OVER_100_PERCENT",
                    "allocation_percent",
                    "Allocation above 100 percent requires an override reason."));
            }
        } catch (NumberFormatException ignored) {
            // Stable numeric finding was already added.
        }
    }

    private void validatePunch(
        Map<String, String> values,
        List<Finding> findings
    ) {
        required(values, "attendance_event_external_id", findings);
        required(values, "event_type", findings);
        required(values, "occurred_at", findings);
        zone(values.get("timezone"), "timezone", findings);
        if (!Set.of("IN", "OUT", "BREAK_START", "BREAK_END")
            .contains(values.get("event_type"))) {
            findings.add(error("FIELD_INVALID_ENUM", "event_type",
                "Attendance event type is invalid."));
        }
        timestamp(values.get("occurred_at"), "occurred_at", findings);
    }

    private void validateDaily(
        Map<String, String> values,
        List<Finding> findings
    ) {
        zone(values.get("timezone"), "timezone", findings);
        integerRange(values, "expected_minutes", 0, 1440, findings);
        integerRange(values, "net_worked_minutes", 0, 1440, findings);
        OffsetDateTime first = timestamp(
            values.get("first_in_at"), "first_in_at", findings);
        OffsetDateTime last = timestamp(
            values.get("last_out_at"), "last_out_at", findings);
        if (first != null && last != null && last.isBefore(first)) {
            findings.add(error("ATTENDANCE_EVENT_ORDER_INVALID",
                "last_out_at", "Last-out cannot precede first-in."));
        }
    }

    private void validateLinear(
        Map<String, String> values,
        List<Finding> findings
    ) {
        String identifier = values.getOrDefault(
            "linear_issue_identifier", "");
        if (!identifier.isBlank()
            && !LINEAR_IDENTIFIER.matcher(identifier).matches()) {
            findings.add(error("LINEAR_LINK_INVALID",
                "linear_issue_identifier",
                "Linear identifier format is invalid."));
        }
        if (values.getOrDefault("historical_snapshot_at", "").isBlank()
            || "LINEAR_API".equals(values.get("source_system"))) {
            findings.add(new Finding(
                "WARNING", "LINEAR_HISTORICAL_STATE_UNPROVEN",
                "historical_snapshot_at",
                "State captured now is classified CURRENT_STATE_ONLY.",
                null, null));
        }
    }

    private void validateCertification(
        Map<String, String> values,
        List<Finding> findings
    ) {
        if (!values.getOrDefault("client_certification_decision", "").isBlank()
            && (values.getOrDefault("source_reference", "").isBlank()
                || values.getOrDefault("product_owner_email", "").isBlank())) {
            findings.add(error("CERTIFICATION_APPROVER_UNAUTHORIZED",
                "product_owner_email",
                "Client certification requires actor and evidence verification."));
        }
    }

    private void validateConfirmation(
        Map<String, String> values,
        List<Finding> findings
    ) {
        if (!values.getOrDefault("decision", "").isBlank()
            && values.getOrDefault("evidence_sha256", "").isBlank()) {
            findings.add(error("CONFIRMATION_EVIDENCE_MISSING",
                "evidence_sha256",
                "Original confirmation decisions require hashed evidence."));
        }
    }

    private void validateInvoice(
        Map<String, String> values,
        List<Finding> findings
    ) {
        for (String field : List.of(
            "engagement_code", "billing_month", "invoice_number",
            "invoice_date", "billing_start_date", "billing_end_date",
            "invoice_filename", "invoice_sha256")) {
            required(values, field, findings);
        }
        String sha = values.getOrDefault("invoice_sha256", "");
        if (!sha.isBlank() && !sha.matches("^[0-9a-fA-F]{64}$")) {
            findings.add(error("FIELD_INVALID_HASH", "invoice_sha256",
                "Invoice document hash must be SHA-256."));
        }
    }

    private void requireDependencies(JobContext job) {
        for (String dependency :
            templates.require(job.templateCode()).dependencies()) {
            if (!hasDependency(job, dependency)) {
                throw new DomainConflictException(
                    "REFERENCE_PREDECESSOR_JOB_MISSING",
                    "Required predecessor template is unresolved: "
                        + dependency,
                    job.version());
            }
        }
    }

    private boolean hasDependency(JobContext job, String dependency) {
        if ("01_employees".equals(dependency)
            && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM employees WHERE organization_id = ?
                )
                """, Boolean.class, job.organizationId()))) {
            return true;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
              SELECT 1 FROM migration_jobs
              WHERE engagement_id = ? AND template_code = ?
                AND state IN ('COMPLETED', 'COMPLETED_WITH_ERRORS')
            )
            """, Boolean.class, job.engagementId(), dependency));
    }

    private void requireDualApproval(JobContext job) {
        List<Map<String, Object>> approvals = jdbc.queryForList("""
            SELECT approval_role, actor_subject, authority_organization_id
            FROM migration_approvals
            WHERE job_id = ? AND reconciliation_id = ?
              AND reconciliation_hash = ? AND decision = 'APPROVED'
            """, job.id(), currentReconciliation(job.id()).get("id"),
            currentReconciliation(job.id()).get("reportHash"));
        Set<String> actors = new LinkedHashSet<>();
        Set<String> authorityOrganizations = new LinkedHashSet<>();
        boolean lead = false;
        boolean reviewer = false;
        for (Map<String, Object> approval : approvals) {
            actors.add(String.valueOf(approval.get("actor_subject")));
            authorityOrganizations.add(String.valueOf(
                approval.get("authority_organization_id")));
            String role = String.valueOf(approval.get("approval_role"));
            lead |= "MIGRATION_LEAD".equals(role);
            reviewer |= Set.of("GOVERNANCE", "BUSINESS").contains(role);
        }
        if (!lead || !reviewer || actors.size() < 2
            || authorityOrganizations.size() < 2) {
            throw new DomainConflictException(
                "MIGRATION_DUAL_APPROVAL_REQUIRED",
                "Commit requires a migration lead and a distinct governance or business reviewer.",
                job.version());
        }
    }

    private void enforceAttendanceAuthority(JobContext job, RowContext row) {
        if (!Set.of("07a_attendance_punches", "07b_attendance_daily")
            .contains(job.templateCode())) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = mapper.convertValue(
            readJson(row.payload()), Map.class);
        String employee = String.valueOf(payload.get("employee_number"));
        String dateValue = "07b_attendance_daily".equals(job.templateCode())
            ? String.valueOf(payload.get("attendance_date"))
            : String.valueOf(payload.get("occurred_at"));
        if (dateValue.length() < 10) {
            throw new DomainConflictException(
                "ATTENDANCE_SOURCE_CONFLICT",
                "Attendance authority date could not be derived.");
        }
        LocalDate date;
        if ("07a_attendance_punches".equals(job.templateCode())) {
            String timezone = String.valueOf(payload.get("timezone"));
            try {
                date = OffsetDateTime.parse(dateValue)
                    .atZoneSameInstant(java.time.ZoneId.of(timezone))
                    .toLocalDate();
            } catch (RuntimeException exception) {
                throw new DomainConflictException(
                    "ATTENDANCE_TIMEZONE_INVALID",
                    "Attendance authority requires an explicit timestamp and validated IANA timezone.",
                    job.version());
            }
        } else {
            date = LocalDate.parse(dateValue);
        }
        String employeeHash = sha256(job.organizationId() + ":" + employee);
        String existing = jdbc.query("""
            SELECT authoritative_template_code
            FROM migration_attendance_authorities
            WHERE engagement_id = ? AND employee_key_hash = ?
              AND attendance_date = ?
            FOR UPDATE
            """, rs -> rs.next() ? rs.getString(1) : null,
            job.engagementId(), employeeHash, date);
        if (existing != null && !existing.equals(job.templateCode())) {
            throw new DomainConflictException(
                "ATTENDANCE_SOURCE_CONFLICT",
                "Raw and daily attendance cannot both be authoritative for an employee-day.",
                job.version());
        }
        jdbc.update("""
            INSERT INTO migration_attendance_authorities
              (engagement_id, employee_key_hash, attendance_date,
               authoritative_template_code, job_id)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (engagement_id, employee_key_hash, attendance_date)
            DO NOTHING
            """, job.engagementId(), employeeHash, date,
            job.templateCode(), job.id());
    }

    private UUID reconcileInternal(JobContext job, String subject) {
        int version = jdbc.queryForObject("""
            SELECT COALESCE(MAX(version), 0) + 1
            FROM migration_reconciliation_reports WHERE job_id = ?
            """, Integer.class, job.id());
        Map<String, Object> counts = jdbc.queryForMap("""
            SELECT count(*) AS total,
                   count(*) FILTER (WHERE state = 'COMMITTED') AS committed,
                   count(*) FILTER (WHERE state IN (
                     'INVALID', 'REJECTED', 'DUPLICATE_CONFLICT')) AS rejected,
                   count(*) FILTER (WHERE confidence IN (
                     'LOW', 'UNVERIFIED')) AS low_confidence
            FROM migration_rows WHERE job_id = ?
            """, job.id());
        String sourceHash = jdbc.queryForObject("""
            SELECT source.sha256
            FROM migration_source_files source
            JOIN migration_jobs value ON value.source_file_id = source.id
            WHERE value.id = ?
            """, String.class, job.id());
        List<String> factHashes = jdbc.queryForList("""
            SELECT content_hash
            FROM migration_rows
            WHERE job_id = ? AND state IN ('VALID', 'WARNING')
            ORDER BY row_number
            """, String.class, job.id());
        String canonicalChecksum = sha256(String.join("", factHashes));
        Map<String, Object> coverage = reconciliationCoverage(job.id());
        Map<String, Object> exceptions =
            reconciliationExceptions(job.id(), counts);
        String reportHash = sha256(json(Map.of(
            "jobId", job.id(), "version", version,
            "sourceHash", sourceHash, "counts", counts,
            "coverage", coverage, "exceptions", exceptions,
            "canonicalChecksum", canonicalChecksum)));
        UUID reportId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO migration_reconciliation_reports
              (id, job_id, engagement_month_id, version, report_hash,
               source_hashes, counts, coverage, exceptions,
               canonical_checksum, created_by_subject)
            VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB),
                    CAST(? AS JSONB), CAST(? AS JSONB), ?, ?)
            """, reportId, job.id(), job.monthId(), version, reportHash,
            json(List.of(sourceHash)), json(counts),
            json(coverage), json(exceptions),
            canonicalChecksum, subject);
        return reportId;
    }

    private Map<String, Object> reconciliationCoverage(UUID jobId) {
        Map<String, Object> coverage = new LinkedHashMap<>(jdbc.queryForMap("""
            SELECT
              count(*) FILTER (WHERE job.template_code = '01_employees'
                AND row.state IN ('VALID', 'WARNING', 'COMMITTED'))
                AS employees,
              count(*) FILTER (WHERE job.template_code IN (
                '05_leave_balances', '06_leave_requests')
                AND row.state IN ('VALID', 'WARNING', 'COMMITTED'))
                AS leave_records,
              count(*) FILTER (WHERE job.template_code = '08_deliverables'
                AND row.state IN ('VALID', 'WARNING', 'COMMITTED'))
                AS deliverables,
              count(*) FILTER (
                WHERE job.template_code = '09_deliverable_linear_links'
                AND row.state IN ('VALID', 'WARNING', 'COMMITTED'))
                AS linear_links,
              count(*) FILTER (
                WHERE job.template_code = '10_delivery_certifications'
                AND row.state IN ('VALID', 'WARNING', 'COMMITTED'))
                AS certifications,
              count(*) FILTER (
                WHERE job.template_code = '11_business_confirmations'
                AND row.state IN ('VALID', 'WARNING', 'COMMITTED'))
                AS confirmations,
              count(*) FILTER (WHERE job.template_code = '12_invoices'
                AND row.state IN ('VALID', 'WARNING', 'COMMITTED'))
                AS invoices,
              count(*) FILTER (WHERE job.template_code = '12_invoices'
                AND row.normalized_payload->>'invoice_sha256'
                    ~ '^[0-9a-fA-F]{64}$'
                AND row.state IN ('VALID', 'WARNING', 'COMMITTED'))
                AS invoice_hash_links,
              count(DISTINCT
                (row.normalized_payload->>'employee_number') || ':' ||
                CASE
                  WHEN job.template_code = '07b_attendance_daily'
                    THEN row.normalized_payload->>'attendance_date'
                  WHEN job.template_code = '07a_attendance_punches'
                    THEN (
                      (row.normalized_payload->>'occurred_at')::timestamptz
                      AT TIME ZONE
                      (row.normalized_payload->>'timezone')
                    )::date::text
                END
              ) FILTER (WHERE job.template_code IN (
                '07a_attendance_punches', '07b_attendance_daily'))
                AS expected_employee_days,
              count(DISTINCT
                (row.normalized_payload->>'employee_number') || ':' ||
                CASE
                  WHEN job.template_code = '07b_attendance_daily'
                    THEN row.normalized_payload->>'attendance_date'
                  WHEN job.template_code = '07a_attendance_punches'
                    THEN (
                      (row.normalized_payload->>'occurred_at')::timestamptz
                      AT TIME ZONE
                      (row.normalized_payload->>'timezone')
                    )::date::text
                END
              ) FILTER (WHERE job.template_code IN (
                '07a_attendance_punches', '07b_attendance_daily')
                AND row.state IN ('VALID', 'WARNING', 'COMMITTED'))
                AS imported_employee_days
            FROM migration_rows row
            JOIN migration_jobs job ON job.id = row.job_id
            WHERE row.job_id = ?
            """, jobId));
        String template = jdbc.queryForObject("""
            SELECT template_code FROM migration_jobs WHERE id = ?
            """, String.class, jobId);
        coverage.put("templateCode", template);
        coverage.put("attendanceAuthorityNonAdditive",
            Set.of("07a_attendance_punches", "07b_attendance_daily")
                .contains(template));
        coverage.put("representedVsRecordedTimeSeparated", true);
        return Collections.unmodifiableMap(coverage);
    }

    private Map<String, Object> reconciliationExceptions(
        UUID jobId,
        Map<String, Object> counts
    ) {
        List<Map<String, Object>> findingCounts = jdbc.query("""
            SELECT finding.code, finding.severity, count(*)
            FROM migration_row_findings finding
            WHERE finding.job_id = ?
            GROUP BY finding.code, finding.severity
            ORDER BY finding.severity, finding.code
            """, (rs, ignored) -> Map.of(
                "code", rs.getString(1),
                "severity", rs.getString(2),
                "count", rs.getLong(3)), jobId);
        List<Map<String, Object>> lowConfidenceRows = jdbc.query("""
            SELECT row_number, natural_key_hash, confidence, limitations
            FROM migration_rows
            WHERE job_id = ? AND confidence IN ('LOW', 'UNVERIFIED')
            ORDER BY row_number
            """, (rs, ignored) -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("rowNumber", rs.getInt(1));
                item.put("naturalKeyHash", rs.getString(2));
                item.put("confidence", rs.getString(3));
                item.put("limitations", rs.getString(4));
                return Collections.unmodifiableMap(item);
            }, jobId);
        return Map.of(
            "findingCounts", findingCounts,
            "lowConfidenceCount", counts.get("low_confidence"),
            "lowConfidenceRows", lowConfidenceRows);
    }

    private Map<String, Object> currentReconciliation(UUID jobId) {
        Map<String, Object> result = jdbc.query("""
            SELECT id, version, report_hash, counts::text, coverage::text
            FROM migration_reconciliation_reports
            WHERE job_id = ?
            ORDER BY version DESC
            LIMIT 1
            """, rs -> {
                if (!rs.next()) {
                    return null;
                }
                return Map.of(
                    "id", rs.getObject(1, UUID.class),
                    "version", rs.getInt(2),
                    "reportHash", rs.getString(3),
                    "counts", readJson(rs.getString(4)),
                    "coverage", readJson(rs.getString(5)));
            }, jobId);
        if (result == null) {
            throw new DomainConflictException(
                "RECONCILIATION_REQUIRED",
                "Validation must produce an exact pre-commit reconciliation.");
        }
        return result;
    }

    private String normalizeApprovalRole(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        return switch (role) {
            case "MIGRATION_LEAD" -> "MIGRATION_LEAD";
            case "GOVERNANCE", "GOVERNANCE_REVIEWER", "BUSINESS" ->
                "GOVERNANCE";
            default -> throw new IllegalArgumentException(
                "Unknown migration approval role.");
        };
    }

    private Map<String, Object> reconciliationMap(
        java.sql.ResultSet rs
    ) throws java.sql.SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", rs.getObject(1, UUID.class));
        result.put("version", rs.getInt(2));
        result.put("reportHash", rs.getString(3));
        result.put("sourceHashes", readJson(rs.getString(4)));
        result.put("counts", readJson(rs.getString(5)));
        result.put("coverage", readJson(rs.getString(6)));
        result.put("exceptions", readJson(rs.getString(7)));
        result.put("canonicalChecksum", rs.getString(8));
        result.put("createdAt", rs.getObject(9, OffsetDateTime.class));
        return Collections.unmodifiableMap(result);
    }

    private JobContext lockedJob(UUID jobId) {
        JobContext result = jdbc.query("""
            SELECT id, source_file_id, engagement_id, organization_id,
                   engagement_month_id, template_code, state, version,
                   partial_commit, invalid_count
            FROM migration_jobs WHERE id = ? FOR UPDATE
            """, rs -> rs.next() ? context(rs) : null, jobId);
        if (result == null) {
            throw new EntityNotFoundException("Migration resource not found.");
        }
        return result;
    }

    private JobContext context(UUID jobId) {
        JobContext result = jdbc.query("""
            SELECT id, source_file_id, engagement_id, organization_id,
                   engagement_month_id, template_code, state, version,
                   partial_commit, invalid_count
            FROM migration_jobs WHERE id = ?
            """, rs -> rs.next() ? context(rs) : null, jobId);
        if (result == null) {
            throw new EntityNotFoundException("Migration resource not found.");
        }
        return result;
    }

    private JobContext context(java.sql.ResultSet rs)
        throws java.sql.SQLException {
        return new JobContext(
            rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
            rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
            rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7),
            rs.getLong(8), rs.getBoolean(9), rs.getInt(10));
    }

    private Map<String, Object> jobSummary(java.sql.ResultSet rs)
        throws java.sql.SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", rs.getObject(1, UUID.class));
        result.put("templateCode", rs.getString(2));
        result.put("templateVersion", rs.getString(3));
        result.put("mode", rs.getString(4));
        result.put("state", rs.getString(5));
        result.put("rowCount", rs.getInt(6));
        result.put("validCount", rs.getInt(7));
        result.put("warningCount", rs.getInt(8));
        result.put("invalidCount", rs.getInt(9));
        result.put("committedCount", rs.getInt(10));
        result.put("rejectedCount", rs.getInt(11));
        result.put("version", rs.getLong(12));
        result.put("createdAt", rs.getObject(13, OffsetDateTime.class));
        result.put("updatedAt", rs.getObject(14, OffsetDateTime.class));
        result.put("safeFilename", rs.getString(15));
        result.put("sourceSha256", rs.getString(16));
        result.put("scanStatus", rs.getString(17));
        result.put("organizationId", rs.getObject(18, UUID.class));
        result.put("engagementMonthId", rs.getObject(19, UUID.class));
        result.put("engagementId", rs.getObject(20, UUID.class));
        LocalDate representedMonth = rs.getObject(21, LocalDate.class);
        result.put("representedPeriod", representedMonth == null
            ? null : representedMonth.toString().substring(0, 7));
        result.put("jobId", result.get("id"));
        result.put("safeFileName", result.get("safeFilename"));
        result.put("originalFileName", result.get("safeFilename"));
        result.put("monthId", result.get("engagementMonthId"));
        result.put("partialCommit", rs.getBoolean(22));
        result.put("totalRows", result.get("rowCount"));
        result.put("validRows", result.get("validCount"));
        result.put("warningRows", result.get("warningCount"));
        result.put("invalidRows", result.get("invalidCount"));
        result.put("committedRows", result.get("committedCount"));
        result.put("progressPercent",
            TERMINAL.contains(String.valueOf(result.get("state")))
                || "READY_TO_COMMIT".equals(result.get("state")) ? 100 : 25);
        result.put("etag", String.valueOf(rs.getLong(12)));
        return Collections.unmodifiableMap(result);
    }

    private Map<String, Object> decorateJob(
        String subject,
        Map<String, Object> source,
        boolean details
    ) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        UUID engagementId = (UUID) source.get("engagementId");
        List<String> permissions = new ArrayList<>();
        for (String permission : List.of(
            "READ", "UPLOAD", "VALIDATE", "APPROVE", "COMMIT",
            "ROLLBACK", "RETRO")) {
            if (authorization.has(
                subject, engagementId,
                "migration." + permission.toLowerCase(Locale.ROOT))) {
                permissions.add("MIGRATION_" + permission);
            }
        }
        result.put("permissions", permissions);
        try {
            result.put("approvalRole",
                authorization.requireApprovalAuthority(
                    subject, engagementId).approvalRole());
        } catch (org.springframework.security.access.AccessDeniedException
                 exception) {
            result.put("approvalRole", null);
        }
        result.put("issues", details ? jdbc.query("""
            SELECT row.row_number, finding.field_name, finding.code,
                   finding.severity, finding.safe_message, row.state
            FROM migration_row_findings finding
            JOIN migration_rows row ON row.id = finding.row_id
            WHERE finding.job_id = ?
            ORDER BY row.row_number, finding.id
            LIMIT 200
            """, (rs, ignored) -> {
                Map<String, Object> issue = new LinkedHashMap<>();
                issue.put("rowNumber", rs.getInt(1));
                issue.put("field", rs.getString(2));
                issue.put("code", rs.getString(3));
                issue.put("severity", rs.getString(4));
                issue.put("safeMessage", rs.getString(5));
                issue.put("state", rs.getString(6));
                return Collections.unmodifiableMap(issue);
            }, source.get("id")) : List.of());
        try {
            Map<String, Object> report = currentReconciliation(
                (UUID) source.get("id"));
            Map<String, Object> reconciliation = new LinkedHashMap<>();
            reconciliation.put("reconciliationId", report.get("id"));
            reconciliation.put("version", report.get("version"));
            reconciliation.put("sha256", report.get("reportHash"));
            reconciliation.put("sourceSha256", source.get("sourceSha256"));
            reconciliation.put("expectedRows", source.get("rowCount"));
            reconciliation.put("validRows", source.get("validCount"));
            reconciliation.put("invalidRows", source.get("invalidCount"));
            reconciliation.put("committedRows", source.get("committedCount"));
            Map<String, Object> reportCounts = mapper.convertValue(
                report.get("counts"), Map.class);
            Map<String, Object> reportCoverage = mapper.convertValue(
                report.get("coverage"), Map.class);
            reconciliation.put("lowConfidenceRows",
                reportCounts.getOrDefault("low_confidence", 0));
            reconciliation.put("expectedEmployeeDays",
                reportCoverage.getOrDefault("expected_employee_days", 0));
            reconciliation.put("importedEmployeeDays",
                reportCoverage.getOrDefault("imported_employee_days", 0));
            reconciliation.put("coverage", reportCoverage);
            reconciliation.put("approvals",
                result.getOrDefault("approvals", List.of()));
            result.put("reconciliation",
                Collections.unmodifiableMap(reconciliation));
        } catch (DomainConflictException exception) {
            result.put("reconciliation", null);
        }
        return Collections.unmodifiableMap(result);
    }

    private void transition(UUID jobId, long version, String state) {
        int updated = jdbc.update("""
            UPDATE migration_jobs SET state = ?, version = version + 1
            WHERE id = ? AND version = ?
            """, state, jobId, version);
        if (updated != 1) {
            Long current = jdbc.queryForObject("""
                SELECT version FROM migration_jobs WHERE id = ?
                """, Long.class, jobId);
            throw new DomainConflictException(
                "ETAG_MISMATCH", "The migration job version is stale.",
                current);
        }
    }

    private void checkpoint(
        UUID jobId,
        String phase,
        int row,
        String hash
    ) {
        jdbc.update("""
            INSERT INTO migration_checkpoints
              (job_id, phase, last_row_number, checkpoint_hash)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (job_id, phase) DO UPDATE
            SET last_row_number = EXCLUDED.last_row_number,
                checkpoint_hash = EXCLUDED.checkpoint_hash,
                attempt = migration_checkpoints.attempt + 1,
                updated_at = CURRENT_TIMESTAMP
            """, jobId, phase, row, hash);
    }

    private void requireVersion(JobContext job, long expected) {
        if (job.version() != expected) {
            throw new DomainConflictException(
                "ETAG_MISMATCH", "The migration job version is stale.",
                job.version());
        }
    }

    private DomainConflictException conflict(String code, JobContext job) {
        return new DomainConflictException(
            code, "The mutation is not legal in the current job state.",
            job.version());
    }

    private byte[] secureBytes(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("FILE_SIZE_INVALID");
        }
        safeFilename(file.getOriginalFilename());
        normalizedMediaType(file.getContentType());
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new IllegalArgumentException("FILE_READ_FAILED", exception);
        }
        if (bytes.length > MAX_BYTES || bytes.length == 0) {
            throw new IllegalArgumentException("FILE_SIZE_INVALID");
        }
        if ((bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K')
            || (bytes.length >= 2 && bytes[0] == 'M' && bytes[1] == 'Z')
            || (bytes.length >= 2 && (bytes[0] & 0xff) == 0x1f
                && (bytes[1] & 0xff) == 0x8b)
            || containsNull(bytes)) {
            throw new IllegalArgumentException("FILE_BINARY_OR_ARCHIVE_REJECTED");
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("FILE_ENCODING_INVALID", exception);
        }
        return bytes;
    }

    private String normalizedMediaType(String mediaType) {
        String value = mediaType == null
            ? MediaType.APPLICATION_OCTET_STREAM_VALUE
            : mediaType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        if (!Set.of(
            "text/csv", "application/csv",
            MediaType.APPLICATION_OCTET_STREAM_VALUE).contains(value)) {
            throw new IllegalArgumentException("FILE_MIME_INVALID");
        }
        return value;
    }

    private String safeFilename(String name) {
        if (name == null || !SAFE_FILENAME.matcher(name).matches()
            || name.contains("..")
            || !name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new IllegalArgumentException("FILE_NAME_INVALID");
        }
        return name;
    }

    private List<MigrationCsvParser.Record> parse(byte[] content) {
        try {
            return parser.parse(new InputStreamReader(
                new ByteArrayInputStream(content), StandardCharsets.UTF_8),
                MAX_ROWS);
        } catch (IOException exception) {
            throw new IllegalArgumentException("FILE_CSV_INVALID", exception);
        }
    }

    private void validateHeader(
        MigrationTemplateRegistry.Template template,
        List<String> actual
    ) {
        Set<String> unique = new LinkedHashSet<>();
        for (String header : actual) {
            if (!unique.add(header)) {
                throw new IllegalArgumentException("FILE_DUPLICATE_HEADER");
            }
            if (COMMERCIAL_HEADER.matcher(header).find()) {
                throw new IllegalArgumentException(
                    "FILE_PROHIBITED_COMMERCIAL_COLUMN");
            }
        }
        if (!template.headers().equals(actual)) {
            throw new IllegalArgumentException("FILE_HEADER_MISMATCH");
        }
    }

    private Map<String, String> rowValues(
        MigrationTemplateRegistry.Template template,
        MigrationCsvParser.Record record
    ) {
        if (record.fields().size() != template.headers().size()) {
            throw new IllegalArgumentException("FILE_COLUMN_COUNT_MISMATCH");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < template.headers().size(); index++) {
            values.put(template.headers().get(index),
                record.fields().get(index).trim());
        }
        return values;
    }

    private Map<String, String> sanitize(Map<String, String> values) {
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(
            key, formulaSafe(value)));
        return result;
    }

    private String formulaSafe(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-'
            || first == '@' ? "'" + value : value;
    }

    private String naturalKey(
        MigrationTemplateRegistry.Template template,
        Map<String, String> values
    ) {
        return template.naturalKeys().stream()
            .map(key -> key + "=" + values.getOrDefault(key, ""))
            .reduce((left, right) -> left + "\u001f" + right)
            .orElse("");
    }

    private String duplicateState(
        UUID engagementId,
        String templateCode,
        String naturalHash,
        String contentHash
    ) {
        return jdbc.query("""
            SELECT CASE WHEN content_hash = ?
                        THEN 'DUPLICATE_IDENTICAL'
                        ELSE 'DUPLICATE_CONFLICT' END
            FROM migration_canonical_facts
            WHERE engagement_id = ? AND template_code = ?
              AND natural_key_hash = ? AND active
            """, rs -> rs.next() ? rs.getString(1) : null,
            contentHash, engagementId, templateCode, naturalHash);
    }

    private String limitation(
        String templateCode,
        Map<String, String> values,
        String confidence
    ) {
        if ("09_deliverable_linear_links".equals(templateCode)
            && ("LINEAR_API".equals(values.get("source_system"))
                || values.getOrDefault(
                    "historical_snapshot_at", "").isBlank())) {
            return "CURRENT_STATE_ONLY";
        }
        if ("LOW".equals(confidence) || "UNVERIFIED".equals(confidence)) {
            return "LOW_OR_UNVERIFIED_SOURCE_DISCLOSED";
        }
        return null;
    }

    private String normalizedSource(String source) {
        if (source == null || source.isBlank()) {
            return "OTHER";
        }
        return "GREYTHR".equals(source)
            ? "GREYTHR_EXPORT" : source.toUpperCase(Locale.ROOT);
    }

    private String normalizedConfidence(
        String confidence,
        String templateCode
    ) {
        if (confidence != null && !confidence.isBlank()) {
            String value = confidence.toUpperCase(Locale.ROOT);
            return MigrationTemplateRegistry.CONFIDENCE.contains(value)
                ? value : "UNVERIFIED";
        }
        return Set.of("10_delivery_certifications",
            "11_business_confirmations", "13_approval_history")
            .contains(templateCode) ? "UNVERIFIED" : "HIGH";
    }

    private OffsetDateTime representedAt(Map<String, String> values) {
        for (String field : List.of(
            "represented_at", "represented_approval_at",
            "represented_decision_at", "represented_plan_approved_at",
            "represented_certification_at", "represented_response_at",
            "represented_uploaded_at", "occurred_at",
            "source_finalized_at")) {
            String value = values.get(field);
            if (value != null && !value.isBlank()) {
                try {
                    return OffsetDateTime.parse(value);
                } catch (RuntimeException ignored) {
                    // Validation emits a stable field finding where required.
                }
            }
        }
        return null;
    }

    private boolean beforeMigrationStart(String value) {
        try {
            String normalized = value.length() == 7 ? value + "-01" : value;
            return LocalDate.parse(normalized.substring(0, 10))
                .isBefore(LocalDate.of(2026, 6, 1));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void required(
        Map<String, String> values,
        String field,
        List<Finding> findings
    ) {
        if (values.getOrDefault(field, "").isBlank()) {
            findings.add(error("FIELD_REQUIRED", field,
                "A required field is missing."));
        }
    }

    private LocalDate date(
        String value,
        String field,
        List<Finding> findings
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            findings.add(error("FIELD_INVALID_DATE", field,
                "Date must use ISO YYYY-MM-DD."));
            return null;
        }
    }

    private OffsetDateTime timestamp(
        String value,
        String field,
        List<Finding> findings
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException exception) {
            findings.add(error("FIELD_INVALID_TIMESTAMP", field,
                "Timestamp must include an explicit UTC offset."));
            return null;
        }
    }

    private void zone(
        String value,
        String field,
        List<Finding> findings
    ) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            java.time.ZoneId.of(value);
        } catch (RuntimeException exception) {
            findings.add(error("FIELD_INVALID_TIMEZONE", field,
                "Timezone must be an IANA zone."));
        }
    }

    private void decimalPositive(
        Map<String, String> values,
        String field,
        List<Finding> findings
    ) {
        try {
            if (Double.parseDouble(values.getOrDefault(field, "0")) <= 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            findings.add(error("FIELD_INVALID_NUMBER", field,
                "Value must be a positive number."));
        }
    }

    private void integerRange(
        Map<String, String> values,
        String field,
        int minimum,
        int maximum,
        List<Finding> findings
    ) {
        try {
            int value = Integer.parseInt(values.getOrDefault(field, ""));
            if (value < minimum || value > maximum) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            findings.add(error("FIELD_INVALID_NUMBER", field,
                "Value is outside the accepted range."));
        }
    }

    private boolean hasUnsafeControls(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("<script") || lower.contains("javascript:")) {
            return true;
        }
        return value.chars().anyMatch(character ->
            character < 0x20 && character != '\t'
                && character != '\n' && character != '\r');
    }

    private Finding error(String code, String field, String message) {
        return new Finding("ERROR", code, field, message, null, null);
    }

    private void audit(
        UUID engagementId,
        UUID organizationId,
        UUID jobId,
        String type,
        String subject,
        Map<String, ?> metadata
    ) {
        jdbc.update("""
            INSERT INTO migration_audit_events
              (id, engagement_id, organization_id, job_id, event_type,
               actor_subject, metadata, correlation_id)
            VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?)
            """, UUID.randomUUID(), engagementId, organizationId, jobId,
            type, subject, json(metadata), UUID.randomUUID());
    }

    private void outbox(
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String eventKey,
        Map<String, ?> payload
    ) {
        jdbc.update("""
            INSERT INTO migration_outbox_events
              (id, aggregate_type, aggregate_id, event_type, event_key, payload)
            VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB))
            ON CONFLICT (event_key) DO NOTHING
            """, UUID.randomUUID(), aggregateType, aggregateId, eventType,
            eventKey, json(payload));
    }

    private Object readJson(String value) {
        try {
            return mapper.readTree(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Stored JSON is invalid.", exception);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                "Request could not be normalized.", exception);
        }
    }

    private String canonical(Map<String, String> values) {
        return values.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .reduce((left, right) -> left + "\u001f" + right)
            .orElse("");
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable.", exception);
        }
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private boolean containsNull(byte[] value) {
        for (byte item : value) {
            if (item == 0) {
                return true;
            }
        }
        return false;
    }

    private String csvCell(String value) {
        String safe = formulaSafe(value == null ? "" : value)
            .replace("\"", "\"\"");
        return "\"" + safe + "\"";
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first
            : second != null && !second.isBlank() ? second : null;
    }

    public record Download(String filename, String mediaType, byte[] content) {
    }

    private record JobContext(
        UUID id,
        UUID sourceFileId,
        UUID engagementId,
        UUID organizationId,
        UUID monthId,
        String templateCode,
        String state,
        long version,
        boolean partialCommit,
        int invalidCount
    ) {
    }

    private record RowContext(
        UUID id,
        int rowNumber,
        String naturalKeyHash,
        String contentHash,
        String payload,
        OffsetDateTime representedAt,
        String sourceType,
        String confidence,
        String limitations
    ) {
    }

    private record SourceScan(
        UUID sourceId,
        String status,
        String sha256,
        byte[] content
    ) {
    }

    private record RetryState(
        int count,
        boolean deadLettered
    ) {
    }

    private record CompensableEffect(
        UUID provenanceId,
        String table,
        UUID recordId,
        int version,
        MigrationDomainAdapter.EffectKind kind,
        Map<String, Object> beforeState
    ) {
    }

    private record Finding(
        String severity,
        String code,
        String field,
        String safeMessage,
        String dependencyTemplate,
        String dependencyKeyHash
    ) {
    }
}
