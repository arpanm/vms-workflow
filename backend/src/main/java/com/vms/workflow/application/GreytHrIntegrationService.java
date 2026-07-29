package com.vms.workflow.application;

import com.vms.workflow.api.DomainConflictException;
import com.vms.workflow.api.GreytHrDtos.CapabilityCertificationRequest;
import com.vms.workflow.api.GreytHrDtos.CapabilityView;
import com.vms.workflow.api.GreytHrDtos.CutoverRequest;
import com.vms.workflow.api.GreytHrDtos.CutoverView;
import com.vms.workflow.api.GreytHrDtos.HealthView;
import com.vms.workflow.api.GreytHrDtos.ReconciliationDecisionRequest;
import com.vms.workflow.api.GreytHrDtos.ReconciliationView;
import com.vms.workflow.api.GreytHrDtos.SyncRequest;
import com.vms.workflow.api.GreytHrDtos.SyncRunView;
import com.vms.workflow.application.GreytHrProviderPayloadValidator.AttendanceRecord;
import com.vms.workflow.application.GreytHrProviderPayloadValidator.EmployeeRecord;
import com.vms.workflow.application.GreytHrProviderPayloadValidator.LeaveRecord;
import com.vms.workflow.application.GreytHrProviderPayloadValidator.ValidatedPage;
import com.vms.workflow.application.GreytHrProviderPayloadValidator.ValidatedPayload;
import com.vms.workflow.security.WorkforceAuthorizationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class GreytHrIntegrationService {
    private static final Set<String> REQUIRED_CAPABILITIES =
        Set.of("EMPLOYEES", "ATTENDANCE", "LEAVE");
    private static final Set<String> PROVIDER_FAILURE_CODES = Set.of(
        "PROVIDER_ADAPTER_EXCEPTION",
        "PROVIDER_EMPTY_RESPONSE",
        "PROVIDER_MALFORMED_RESPONSE",
        "PROVIDER_NOT_CONFIGURED",
        "PROVIDER_RATE_LIMITED",
        "PROVIDER_TIMEOUT",
        "PROVIDER_UNAVAILABLE"
    );
    private static final Duration FRESHNESS_LIMIT = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final GreytHrProviderAdapter adapter;
    private final GreytHrProviderPayloadValidator payloadValidator;
    private final WorkforceAuthorizationService authorization;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public GreytHrIntegrationService(
        JdbcTemplate jdbc,
        ObjectMapper mapper,
        GreytHrProviderAdapter adapter,
        GreytHrProviderPayloadValidator payloadValidator,
        WorkforceAuthorizationService authorization,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.adapter = adapter;
        this.payloadValidator = payloadValidator;
        this.authorization = authorization;
        this.transactions = transactions;
        this.clock = clock;
    }

    public CapabilityView capabilities(String subject, UUID connectionId) {
        Connection connection = connection(subject, connectionId, false);
        UUID certificationId = connection.certificationId();
        OffsetDateTime certifiedAt = certificationId == null ? null : jdbc.queryForObject("""
            SELECT certified_at FROM integration_capability_certifications
            WHERE id = ?
            """, OffsetDateTime.class, certificationId);
        ProbeEvidence evidence = certificationId == null ? null : jdbc.query("""
            SELECT probe.id, probe.evidence_hash, probe.probed_at,
                   probe.adapter_mode
            FROM greythr_certification_evidence certification
            JOIN greythr_capability_probe_evidence probe
              ON probe.id = certification.provider_probe_evidence_id
            WHERE certification.connection_id = ?
              AND certification.certification_id = ?
            """, result -> result.next() ? new ProbeEvidence(
                result.getObject("id", UUID.class),
                result.getString("evidence_hash"),
                result.getObject("probed_at", OffsetDateTime.class),
                result.getString("adapter_mode")
            ) : null, connectionId, certificationId);
        return new CapabilityView(
            connection.id(), "GREYTHR", connection.status(),
            List.copyOf(REQUIRED_CAPABILITIES), certificationId, certifiedAt,
            evidence == null ? null : evidence.id(),
            evidence == null ? null : evidence.hash(),
            evidence == null ? null : evidence.probedAt(),
            evidence == null ? connection.adapterMode() : evidence.adapterMode());
    }

    @Transactional
    public CapabilityView certify(
        String subject,
        UUID connectionId,
        CapabilityCertificationRequest request
    ) {
        Connection connection = connection(subject, connectionId, true);
        if (!connection.organizationId().equals(request.organizationId())) {
            throw notFound();
        }
        Set<String> requested = Set.copyOf(request.capabilities());
        if (!requested.equals(REQUIRED_CAPABILITIES)) {
            throw new DomainConflictException(
                "GREYTHR_CAPABILITY_INCOMPLETE",
                "Employee, attendance and leave capabilities must all be certified.");
        }
        if (connection.certificationId() == null) {
            GreytHrProviderAdapter.CapabilityProbeResult probe;
            try {
                probe = adapter.probe(connection.id());
            } catch (RuntimeException exception) {
                throw new DomainConflictException(
                    "GREYTHR_CAPABILITY_PROBE_FAILED",
                    "The provider capability probe failed closed.");
            }
            Set<String> attested = Set.copyOf(probe.capabilities());
            if (!"AVAILABLE".equals(probe.status())
                || !connection.adapterMode().equals(probe.adapterMode())
                || !attested.equals(REQUIRED_CAPABILITIES)) {
                throw new DomainConflictException(
                    "GREYTHR_CAPABILITY_PROBE_FAILED",
                    "The provider adapter did not attest the complete required capability set.");
            }
            OffsetDateTime probedAt = OffsetDateTime.now(clock);
            UUID probeEvidenceId = UUID.randomUUID();
            Map<String, Object> evidenceManifest = new LinkedHashMap<>();
            evidenceManifest.put("schema", "greythr-capability-probe-v1");
            evidenceManifest.put("connectionId", connection.id().toString());
            evidenceManifest.put("organizationId",
                connection.organizationId().toString());
            evidenceManifest.put("adapterMode", probe.adapterMode());
            evidenceManifest.put("capabilities",
                probe.capabilities().stream().sorted().toList());
            evidenceManifest.put("providerEvidence", probe.evidence());
            evidenceManifest.put("probedAt", probedAt.toString());
            String evidenceJson = json(evidenceManifest);
            String evidenceHash = sha256(evidenceJson);
            jdbc.update("""
                INSERT INTO greythr_capability_probe_evidence
                    (id, connection_id, organization_id, adapter_mode,
                     status, capabilities, evidence_manifest, evidence_hash,
                     probed_at)
                VALUES (?, ?, ?, ?, 'PASSED', ?::jsonb, ?::jsonb, ?, ?)
                """, probeEvidenceId, connection.id(),
                connection.organizationId(), probe.adapterMode(),
                json(probe.capabilities().stream().sorted().toList()),
                evidenceJson, evidenceHash, probedAt);
            UUID certificationId = UUID.randomUUID();
            Map<String, Object> attestation = Map.of(
                "probeEvidenceId", probeEvidenceId.toString(),
                "evidenceHash", evidenceHash,
                "probedAt", probedAt.toString(),
                "adapterMode", probe.adapterMode());
            jdbc.update("""
                INSERT INTO integration_capability_certifications
                    (id, organization_id, provider, status, certified_at,
                     capability_manifest)
                VALUES (?, ?, 'GREYTHR', 'CERTIFIED', CURRENT_TIMESTAMP,
                        ?::jsonb)
                """, certificationId, connection.organizationId(),
                json(Map.of(
                    "schema", "greythr-capability-v2",
                    "capabilities", probe.capabilities().stream().sorted().toList(),
                    "providerAttestation", attestation
                )));
            jdbc.update("""
                UPDATE greythr_connections
                SET capability_certification_id = ?, status = 'ACTIVE',
                    last_error_code = NULL
                WHERE id = ?
                """, certificationId, connection.id());
        }
        return capabilities(subject, connectionId);
    }

    public SyncRunView sync(
        String subject,
        UUID connectionId,
        String idempotencyKey,
        SyncRequest request
    ) {
        payloadValidator.validateRequest(
            idempotencyKey, request.dateFrom(), request.dateTo());
        SyncStart start = transactions.execute(status ->
            beginSync(subject, connectionId, idempotencyKey, request));
        if (start.replay() != null) {
            return start.replay();
        }
        GreytHrProviderAdapter.FetchResult result;
        try {
            result = adapter.fetch(
                connectionId, request.dateFrom(), request.dateTo());
        } catch (RuntimeException exception) {
            return transactions.execute(status ->
                failSync(start.runId(), connectionId, "PROVIDER_ADAPTER_EXCEPTION"));
        }
        if (result == null || !"AVAILABLE".equals(result.status())) {
            return transactions.execute(status ->
                failSync(
                    start.runId(), connectionId,
                    providerFailureCode(
                        result == null ? null : result.errorCode())));
        }
        try {
            ValidatedPayload payload = payloadValidator.validateAndParse(
                idempotencyKey, request.dateFrom(), request.dateTo(), result);
            return transactions.execute(status ->
                completeSync(start, payload));
        } catch (RuntimeException exception) {
            transactions.executeWithoutResult(status ->
                failSync(start.runId(), connectionId, "PROVIDER_PAYLOAD_REJECTED"));
            throw exception;
        }
    }

    private SyncStart beginSync(
        String subject,
        UUID connectionId,
        String idempotencyKey,
        SyncRequest request
    ) {
        Connection connection = connection(subject, connectionId, true);
        if (connection.certificationId() == null
            || !hasProviderAttestation(
                connection.id(), connection.certificationId())
            || "DISABLED".equals(connection.status())
            || "DISCOVERED".equals(connection.status())) {
            throw new DomainConflictException(
                "GREYTHR_NOT_CERTIFIED",
                "The greytHR connection is not capability-certified.");
        }
        jdbc.queryForObject(
            "SELECT id FROM greythr_connections WHERE id = ? FOR UPDATE",
            UUID.class, connectionId);
        String requestHash = sha256(
            connectionId + "|" + request.dateFrom() + "|" + request.dateTo());
        List<SyncRow> prior = syncRows("""
            WHERE run.connection_id = ? AND run.idempotency_key = ?
            """, connectionId, idempotencyKey);
        if (!prior.isEmpty()) {
            if (!prior.getFirst().requestHash().equals(requestHash)) {
                throw new DomainConflictException(
                    "IDEMPOTENCY_KEY_REUSED",
                    "The idempotency key was already used for a different sync.");
            }
            return new SyncStart(
                prior.getFirst().id(), connection, requestHash,
                view(prior.getFirst()));
        }
        UUID runId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO greythr_sync_runs
                (id, connection_id, organization_id, idempotency_key,
                 request_hash, date_from, date_to, status, correlation_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?)
            """, runId, connectionId, connection.organizationId(),
            idempotencyKey, requestHash, request.dateFrom(), request.dateTo(),
            UUID.randomUUID());
        jdbc.update("""
            UPDATE greythr_connections SET last_attempt_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, connectionId);
        return new SyncStart(runId, connection, requestHash, null);
    }

    private SyncRunView completeSync(
        SyncStart start,
        ValidatedPayload payload
    ) {
        UUID connectionId = start.connection().id();
        jdbc.queryForObject(
            "SELECT id FROM greythr_connections WHERE id = ? FOR UPDATE",
            UUID.class, connectionId);
        int employeeCount = 0;
        int attendanceCount = 0;
        int leaveCount = 0;
        int conflictCount = 0;
        for (ValidatedPage page : payload.pages()) {
            for (EmployeeRecord employee : page.employees()) {
                UUID employeeId = employeeId(
                    start.connection().organizationId(),
                    employee.employeeNumber());
                insertFact(
                    connectionId, start.runId(), employeeId,
                    employee.providerEmployeeId(), "EMPLOYEE", null,
                    employee.providerRecordId(),
                    page.sourceUpdatedAt(), employee.raw());
                employeeCount++;
            }
        }
        for (ValidatedPage page : payload.pages()) {
            for (AttendanceRecord attendance : page.attendance()) {
                UUID employeeId = employeeIdByProvider(
                    connectionId, attendance.providerEmployeeId());
                LocalDate workDate = attendance.workDate();
                StoredFact stored = insertFact(
                    connectionId, start.runId(), employeeId,
                    attendance.providerEmployeeId(), "ATTENDANCE",
                    workDate, attendance.providerRecordId(),
                    page.sourceUpdatedAt(), attendance.raw());
                attendanceCount++;
                if (!stored.inserted()) {
                    continue;
                }
                if (isGreytHrAuthoritative(
                    connectionId, employeeId, workDate,
                    start.connection().certificationId())) {
                    applyAttendanceFact(stored.id());
                } else if (hasInternalAttendance(employeeId, workDate)) {
                    insertReconciliation(
                        start.runId(), connectionId, employeeId, workDate,
                        "ATTENDANCE_SOURCE_CONFLICT", stored.id());
                    conflictCount++;
                }
            }
            for (LeaveRecord leave : page.leave()) {
                UUID employeeId = employeeIdByProvider(
                    connectionId, leave.providerEmployeeId());
                LocalDate workDate = leave.workDate();
                StoredFact stored = insertFact(
                    connectionId, start.runId(), employeeId,
                    leave.providerEmployeeId(), "LEAVE", workDate,
                    leave.providerRecordId(), page.sourceUpdatedAt(), leave.raw());
                leaveCount++;
                if (!stored.inserted()) {
                    continue;
                }
                if (isGreytHrAuthoritative(
                    connectionId, employeeId, workDate,
                    start.connection().certificationId())) {
                    applyLeaveFact(stored.id());
                } else if (hasInternalLeave(employeeId, workDate)) {
                    insertReconciliation(
                        start.runId(), connectionId, employeeId, workDate,
                        "LEAVE_SOURCE_CONFLICT", stored.id());
                    conflictCount++;
                }
            }
        }
        jdbc.update("""
            UPDATE greythr_sync_runs
            SET status = 'COMPLETED', employee_count = ?, attendance_count = ?,
                leave_count = ?, conflict_count = ?, page_count = ?,
                completed_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, employeeCount, attendanceCount, leaveCount, conflictCount,
            payload.pages().size(), start.runId());
        jdbc.update("""
            UPDATE greythr_connections
            SET status = 'ACTIVE', last_success_at = CURRENT_TIMESTAMP,
                last_error_code = NULL
            WHERE id = ?
            """, connectionId);
        return syncRun(start.runId());
    }

    private SyncRunView failSync(
        UUID runId,
        UUID connectionId,
        String errorCode
    ) {
        jdbc.queryForObject(
            "SELECT id FROM greythr_connections WHERE id = ? FOR UPDATE",
            UUID.class, connectionId);
        jdbc.update("""
            UPDATE greythr_sync_runs
            SET status = 'DEGRADED', error_code = ?, completed_at = CURRENT_TIMESTAMP
            WHERE id = ? AND status = 'RUNNING'
            """, errorCode, runId);
        jdbc.update("""
            UPDATE greythr_connections
            SET status = 'DEGRADED', last_error_code = ?
            WHERE id = ?
            """, errorCode, connectionId);
        return syncRun(runId);
    }

    private String providerFailureCode(String value) {
        return value != null && PROVIDER_FAILURE_CODES.contains(value)
            ? value : "PROVIDER_ADAPTER_EXCEPTION";
    }

    public SyncRunView syncRun(String subject, UUID connectionId, UUID runId) {
        connection(subject, connectionId, false);
        List<SyncRow> rows = syncRows("""
            WHERE run.id = ? AND run.connection_id = ?
            """, runId, connectionId);
        if (rows.isEmpty()) {
            throw notFound();
        }
        return view(rows.getFirst());
    }

    public List<ReconciliationView> reconciliations(
        String subject,
        UUID connectionId
    ) {
        connection(subject, connectionId, false);
        return jdbc.query("""
            SELECT item.id, item.sync_run_id, item.employee_id, item.work_date,
                   item.conflict_type, item.status, item.decision_reason,
                   item.decided_at
            FROM greythr_reconciliation_items item
            WHERE item.connection_id = ?
            ORDER BY item.work_date, item.id
            """, (result, row) -> new ReconciliationView(
                result.getObject("id", UUID.class),
                result.getObject("sync_run_id", UUID.class),
                result.getObject("employee_id", UUID.class),
                result.getObject("work_date", LocalDate.class),
                result.getString("conflict_type"),
                result.getString("status"),
                result.getString("decision_reason"),
                result.getObject("decided_at", OffsetDateTime.class)
            ), connectionId);
    }

    @Transactional
    public ReconciliationView reconcile(
        String subject,
        UUID itemId,
        ReconciliationDecisionRequest request
    ) {
        ReconciliationRow item = jdbc.query("""
            SELECT item.id, item.connection_id, item.provider_fact_id,
                   item.status, item.conflict_type
            FROM greythr_reconciliation_items item
            WHERE item.id = ?
            FOR UPDATE
            """, result -> result.next() ? new ReconciliationRow(
                result.getObject("id", UUID.class),
                result.getObject("connection_id", UUID.class),
                result.getObject("provider_fact_id", UUID.class),
                result.getString("status"),
                result.getString("conflict_type")
            ) : null, itemId);
        if (item == null) {
            throw notFound();
        }
        connection(subject, item.connectionId(), true);
        if (!"PENDING".equals(item.status())) {
            return oneReconciliation(itemId);
        }
        if ("USE_GREYTHR".equals(request.decision())
            && isFactCurrentlyAuthoritative(item.factId())) {
            if ("ATTENDANCE_SOURCE_CONFLICT".equals(item.conflictType())) {
                applyAttendanceFact(item.factId());
            } else {
                applyLeaveFact(item.factId());
            }
        }
        jdbc.update("""
            UPDATE greythr_reconciliation_items
            SET status = ?, decision_reason = ?, decided_at = CURRENT_TIMESTAMP,
                decided_by_subject = ?
            WHERE id = ?
            """, request.decision(), request.reason(), subject, itemId);
        return oneReconciliation(itemId);
    }

    @Transactional
    public CutoverView cutover(
        String subject,
        UUID connectionId,
        CutoverRequest request
    ) {
        Connection connection = connection(subject, connectionId, true);
        if (connection.certificationId() == null
            || !hasProviderAttestation(
                connection.id(), connection.certificationId())
            || !"ACTIVE".equals(connection.status())) {
            throw new DomainConflictException(
                "GREYTHR_NOT_CERTIFIED",
                "The greytHR connection is not ready for cutover.");
        }
        UUID organizationId = authorization.employeeOrganization(request.employeeId());
        if (!connection.organizationId().equals(organizationId)) {
            throw notFound();
        }
        if (jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM greythr_reconciliation_items
                WHERE connection_id = ? AND employee_id = ? AND status = 'PENDING'
            )
            """, Boolean.class, connectionId, request.employeeId())) {
            throw new DomainConflictException(
                "GREYTHR_RECONCILIATION_PENDING",
                "All source conflicts must be resolved before cutover.");
        }
        CurrentSource current = jdbc.query("""
            SELECT id, valid_from
            FROM attendance_source_mode_assignments
            WHERE employee_id = ? AND valid_to IS NULL
            FOR UPDATE
            """, result -> result.next() ? new CurrentSource(
                result.getObject("id", UUID.class),
                result.getObject("valid_from", LocalDate.class)
            ) : null, request.employeeId());
        if (current == null || !request.effectiveFrom().isAfter(current.validFrom())) {
            throw new DomainConflictException(
                "GREYTHR_CUTOVER_DATE_INVALID",
                "Cutover must start after the current source assignment.");
        }
        jdbc.update("""
            UPDATE attendance_source_mode_assignments
            SET valid_to = ?
            WHERE id = ?
            """, request.effectiveFrom().minusDays(1), current.id());
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO attendance_source_mode_assignments
                (id, employee_id, mode, authoritative_source,
                 capability_certification_id, valid_from, created_by_subject)
            VALUES (?, ?, 'GREYTHR_AUTHORITATIVE', 'GREYTHR', ?, ?, ?)
            """, assignmentId, request.employeeId(),
            connection.certificationId(), request.effectiveFrom(), subject);
        UUID cutoverId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO greythr_cutovers
                (id, connection_id, organization_id, employee_id,
                 capability_certification_id, source_assignment_id,
                 effective_from, reason, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, cutoverId, connectionId, connection.organizationId(),
            request.employeeId(),
            connection.certificationId(), assignmentId,
            request.effectiveFrom(), request.reason(), subject);
        applySelectedFactsAtCutover(
            connectionId, request.employeeId(), request.effectiveFrom());
        return new CutoverView(
            cutoverId, request.employeeId(), "GREYTHR_AUTHORITATIVE",
            "GREYTHR", request.effectiveFrom(), connection.certificationId());
    }

    public HealthView health(String subject, UUID connectionId) {
        Connection connection = connection(subject, connectionId, false);
        boolean stale = sourceIsStale(
            connection.lastSuccessAt(),
            "DEGRADED".equals(connection.status()));
        int pending = jdbc.queryForObject("""
            SELECT COUNT(*) FROM greythr_reconciliation_items
            WHERE connection_id = ? AND status = 'PENDING'
            """, Integer.class, connectionId);
        return new HealthView(
            connectionId, connection.status(), connection.lastAttemptAt(),
            connection.lastSuccessAt(), stale, connection.lastErrorCode(), pending);
    }

    private Connection connection(String subject, UUID id, boolean manage) {
        Connection connection = jdbc.query("""
            SELECT id, organization_id, status, adapter_mode,
                   capability_certification_id, last_attempt_at,
                   last_success_at, last_error_code
            FROM greythr_connections
            WHERE id = ?
            """, result -> result.next() ? new Connection(
                result.getObject("id", UUID.class),
                result.getObject("organization_id", UUID.class),
                result.getString("status"),
                result.getString("adapter_mode"),
                result.getObject("capability_certification_id", UUID.class),
                result.getObject("last_attempt_at", OffsetDateTime.class),
                result.getObject("last_success_at", OffsetDateTime.class),
                result.getString("last_error_code")
            ) : null, id);
        if (connection == null) {
            throw notFound();
        }
        if (manage) {
            authorization.requireOrganizationManage(subject, connection.organizationId());
        } else {
            authorization.requireOrganizationRead(subject, connection.organizationId());
        }
        return connection;
    }

    private String text(Map<String, Object> value, String key) {
        Object field = value.get(key);
        if (!(field instanceof String text) || text.isBlank()) {
            throw new DomainConflictException(
                "GREYTHR_PROVIDER_MALFORMED",
                "The provider response is missing a required field.");
        }
        return text;
    }

    private UUID employeeId(UUID organizationId, String employeeNumber) {
        UUID id = jdbc.query("""
            SELECT id FROM employees
            WHERE organization_id = ? AND employee_number = ?
            """, result -> result.next() ? result.getObject(1, UUID.class) : null,
            organizationId, employeeNumber);
        if (id == null) {
            throw new DomainConflictException(
                "GREYTHR_EMPLOYEE_UNMAPPED",
                "A provider employee is not mapped to a current employee.");
        }
        return id;
    }

    private UUID employeeIdByProvider(
        UUID connectionId,
        String providerEmployeeId
    ) {
        UUID id = jdbc.query("""
            SELECT employee_id FROM greythr_employee_mappings
            WHERE connection_id = ? AND provider_employee_id = ?
            ORDER BY mapping_version DESC
            LIMIT 1
            """, result -> result.next() ? result.getObject(1, UUID.class) : null,
            connectionId, providerEmployeeId);
        if (id == null) {
            throw new DomainConflictException(
                "GREYTHR_EMPLOYEE_UNMAPPED",
                "A provider fact references an unmapped employee.");
        }
        return id;
    }

    private StoredFact insertFact(
        UUID connectionId,
        UUID runId,
        UUID employeeId,
        String providerEmployeeId,
        String kind,
        LocalDate workDate,
        String providerRecordId,
        OffsetDateTime sourceUpdatedAt,
        Map<String, Object> payload
    ) {
        String body = json(payload);
        String payloadHash = sha256(body);
        UUID existing = jdbc.query("""
            SELECT id FROM greythr_imported_facts
            WHERE connection_id = ? AND fact_kind = ?
              AND provider_record_id = ? AND payload_hash = ?
            """, result -> result.next() ? result.getObject(1, UUID.class) : null,
            connectionId, kind, providerRecordId, payloadHash);
        if (existing != null) {
            return new StoredFact(existing, false);
        }
        UUID prior = jdbc.query("""
            SELECT id FROM greythr_imported_facts
            WHERE connection_id = ? AND fact_kind = ?
              AND provider_record_id = ?
            ORDER BY recorded_at DESC, id DESC
            LIMIT 1
            """, result -> result.next()
                ? result.getObject("id", UUID.class) : null,
            connectionId, kind, providerRecordId);
        UUID organizationId = jdbc.queryForObject("""
            SELECT organization_id
            FROM greythr_connections
            WHERE id = ?
            """, UUID.class, connectionId);
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO greythr_imported_facts
                (id, connection_id, sync_run_id, organization_id,
                 employee_id, provider_employee_id, fact_kind, work_date,
                 provider_record_id, payload_hash, supersedes_id,
                 source_updated_at, payload)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """, id, connectionId, runId, organizationId,
            employeeId, providerEmployeeId, kind, workDate,
            providerRecordId, payloadHash, prior, sourceUpdatedAt, body);
        return new StoredFact(id, true);
    }

    private boolean hasProviderAttestation(
        UUID connectionId,
        UUID certificationId
    ) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM greythr_certification_evidence certification
                JOIN greythr_capability_probe_evidence probe
                  ON probe.id = certification.provider_probe_evidence_id
                 AND probe.connection_id = certification.connection_id
                 AND probe.organization_id = certification.organization_id
                WHERE certification.connection_id = ?
                  AND certification.certification_id = ?
                  AND probe.status = 'PASSED'
            )
            """, Boolean.class, connectionId, certificationId));
    }

    private boolean isGreytHrAuthoritative(
        UUID connectionId,
        UUID employeeId,
        LocalDate workDate,
        UUID certificationId
    ) {
        if (certificationId == null
            || !hasProviderAttestation(connectionId, certificationId)) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM attendance_source_mode_assignments assignment
                WHERE assignment.employee_id = ?
                  AND assignment.mode = 'GREYTHR_AUTHORITATIVE'
                  AND assignment.authoritative_source = 'GREYTHR'
                  AND assignment.capability_certification_id = ?
                  AND assignment.valid_from <= ?
                  AND (
                      assignment.valid_to IS NULL
                      OR assignment.valid_to >= ?
                  )
            )
            """, Boolean.class, employeeId, certificationId,
            workDate, workDate));
    }

    private boolean isFactCurrentlyAuthoritative(UUID factId) {
        ProviderFact fact = providerFact(factId);
        UUID certificationId = jdbc.queryForObject("""
            SELECT capability_certification_id
            FROM greythr_connections
            WHERE id = ?
            """, UUID.class, fact.connectionId());
        return isGreytHrAuthoritative(
            fact.connectionId(), fact.employeeId(), fact.workDate(),
            certificationId);
    }

    private void applySelectedFactsAtCutover(
        UUID connectionId,
        UUID employeeId,
        LocalDate effectiveFrom
    ) {
        List<FactToApply> facts = jdbc.query("""
            WITH current_fact AS (
                SELECT fact.id, fact.fact_kind,
                       row_number() OVER (
                           PARTITION BY fact.employee_id, fact.fact_kind,
                                        fact.work_date
                           ORDER BY fact.source_updated_at DESC,
                                    fact.recorded_at DESC, fact.id DESC
                       ) AS recency
                FROM greythr_imported_facts fact
                LEFT JOIN greythr_reconciliation_items reconciliation
                  ON reconciliation.provider_fact_id = fact.id
                WHERE fact.connection_id = ?
                  AND fact.employee_id = ?
                  AND fact.fact_kind IN ('ATTENDANCE', 'LEAVE')
                  AND fact.work_date >= ?
                  AND (
                      reconciliation.id IS NULL
                      OR reconciliation.status = 'USE_GREYTHR'
                  )
            )
            SELECT current_fact.id, current_fact.fact_kind
            FROM current_fact
            WHERE current_fact.recency = 1
              AND NOT EXISTS (
                  SELECT 1
                  FROM greythr_fact_applications application
                  WHERE application.provider_fact_id = current_fact.id
                    AND application.action = 'APPLY'
              )
            ORDER BY current_fact.id
            """, (result, row) -> new FactToApply(
                result.getObject("id", UUID.class),
                result.getString("fact_kind")
            ), connectionId, employeeId, effectiveFrom);
        for (FactToApply fact : facts) {
            if ("ATTENDANCE".equals(fact.kind())) {
                applyAttendanceFact(fact.id());
            } else {
                applyLeaveFact(fact.id());
            }
        }
    }

    private void insertReconciliation(
        UUID runId,
        UUID connectionId,
        UUID employeeId,
        LocalDate workDate,
        String conflictType,
        UUID factId
    ) {
        jdbc.update("""
            INSERT INTO greythr_reconciliation_items
                (id, sync_run_id, connection_id, organization_id,
                 employee_id, work_date, conflict_type, provider_fact_id)
            SELECT ?, ?, ?, organization_id, ?, ?, ?, ?
            FROM greythr_connections
            WHERE id = ?
            ON CONFLICT (provider_fact_id) DO NOTHING
            """, UUID.randomUUID(), runId, connectionId, employeeId,
            workDate, conflictType, factId, connectionId);
    }

    private boolean hasInternalAttendance(UUID employeeId, LocalDate workDate) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM attendance_events
                WHERE employee_id = ? AND work_date = ? AND source <> 'GREYTHR'
            )
            """, Boolean.class, employeeId, workDate));
    }

    private boolean hasInternalLeave(UUID employeeId, LocalDate workDate) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM leave_request_days day
                JOIN leave_requests request ON request.id = day.leave_request_id
                WHERE request.employee_id = ? AND day.leave_date = ?
            )
            """, Boolean.class, employeeId, workDate));
    }

    private void applyAttendanceFact(UUID factId) {
        ProviderFact fact = providerFact(factId);
        Map<String, Object> payload = readMap(fact.payload());
        OffsetDateTime checkIn = OffsetDateTime.parse(text(payload, "checkInAt"));
        OffsetDateTime checkOut = OffsetDateTime.parse(text(payload, "checkOutAt"));
        int minutes = Math.toIntExact(Duration.between(checkIn, checkOut).toMinutes());
        PriorApplication prior = priorApplication(
            fact.supersedesId(), "ATTENDANCE_SESSION");
        if (prior != null) {
            jdbc.update("""
                UPDATE attendance_sessions
                SET status = 'SUPERSEDED'
                WHERE id = ? AND status = 'CLOSED'
                """, prior.targetRecordId());
            recordApplication(
                fact, "SUPERSEDE", "ATTENDANCE_SESSION",
                prior.targetRecordId(), prior.id(),
                "Corrected greytHR fact superseded its prior attendance session.",
                Map.of("supersededFactId", fact.supersedesId().toString()));
        }
        jdbc.update("""
            UPDATE attendance_sessions
            SET status = 'SUPERSEDED'
            WHERE employee_id = ? AND work_date = ? AND status = 'CLOSED'
            """, fact.employeeId(), fact.workDate());
        UUID checkInId = UUID.randomUUID();
        UUID checkOutId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO attendance_events
                (id, employee_id, event_type, occurred_at, work_date, source,
                 idempotency_key, recorded_by_subject)
            VALUES (?, ?, 'IMPORTED_PUNCH', ?, ?, 'GREYTHR', ?, 'service-greythr'),
                   (?, ?, 'IMPORTED_PUNCH', ?, ?, 'GREYTHR', ?, 'service-greythr')
            ON CONFLICT (employee_id, idempotency_key) DO NOTHING
            """, checkInId, fact.employeeId(), checkIn, fact.workDate(),
            "greythr:" + fact.id() + ":in",
            checkOutId, fact.employeeId(), checkOut, fact.workDate(),
            "greythr:" + fact.id() + ":out");
        UUID actualCheckIn = jdbc.queryForObject("""
            SELECT id FROM attendance_events
            WHERE employee_id = ? AND idempotency_key = ?
            """, UUID.class, fact.employeeId(),
            "greythr:" + fact.id() + ":in");
        UUID actualCheckOut = jdbc.queryForObject("""
            SELECT id FROM attendance_events
            WHERE employee_id = ? AND idempotency_key = ?
            """, UUID.class, fact.employeeId(),
            "greythr:" + fact.id() + ":out");
        UUID sessionId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO attendance_sessions
                (id, employee_id, work_date, check_in_event_id,
                 check_out_event_id, check_in_at, check_out_at,
                 net_minutes, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CLOSED')
            ON CONFLICT (check_in_event_id) DO NOTHING
            """, sessionId, fact.employeeId(), fact.workDate(),
            actualCheckIn, actualCheckOut, checkIn, checkOut, minutes);
        UUID actualSession = jdbc.queryForObject("""
            SELECT id FROM attendance_sessions WHERE check_in_event_id = ?
            """, UUID.class, actualCheckIn);
        recordApplication(
            fact, "APPLY", "ATTENDANCE_SESSION", actualSession,
            prior == null ? null : prior.id(),
            "Applied validated greytHR attendance fact.",
            Map.of(
                "payloadHash", fact.payloadHash(),
                "netMinutes", minutes));
    }

    private void applyLeaveFact(UUID factId) {
        ProviderFact fact = providerFact(factId);
        Map<String, Object> payload = readMap(fact.payload());
        UUID leaveTypeId = jdbc.queryForObject("""
            SELECT leave_type.id
            FROM leave_types leave_type
            JOIN employees employee ON employee.organization_id = leave_type.organization_id
            WHERE employee.id = ? AND leave_type.code = ?
            """, UUID.class, fact.employeeId(), text(payload, "leaveTypeCode"));
        double units = Double.parseDouble(String.valueOf(payload.get("units")));
        PriorApplication prior = priorApplication(
            fact.supersedesId(), "LEAVE_BALANCE_LEDGER");
        if (prior != null) {
            PriorLedger priorLedger = jdbc.queryForObject("""
                SELECT leave_type_id, quantity::double precision
                FROM leave_balance_ledger
                WHERE id = ?
                """, (result, row) -> new PriorLedger(
                    result.getObject("leave_type_id", UUID.class),
                    result.getDouble(2)
                ), prior.targetRecordId());
            UUID compensationId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO leave_balance_ledger
                    (id, employee_id, leave_type_id, entry_type, quantity,
                     effective_date, idempotency_key, reference_type,
                     reference_id, reason, recorded_by_subject)
                VALUES (?, ?, ?, 'MIGRATION_CORRECTION', ?, ?, ?,
                        'GREYTHR_FACT', ?,
                        'Compensate superseded greytHR leave fact',
                        'service-greythr')
                ON CONFLICT (employee_id, leave_type_id, idempotency_key)
                DO NOTHING
                """, compensationId, fact.employeeId(),
                priorLedger.leaveTypeId(), -priorLedger.quantity(),
                fact.workDate(),
                "greythr:" + fact.id() + ":compensate",
                fact.supersedesId());
            UUID actualCompensation = jdbc.queryForObject("""
                SELECT id FROM leave_balance_ledger
                WHERE employee_id = ? AND leave_type_id = ?
                  AND idempotency_key = ?
                """, UUID.class, fact.employeeId(),
                priorLedger.leaveTypeId(),
                "greythr:" + fact.id() + ":compensate");
            recordApplication(
                fact, "COMPENSATE", "LEAVE_BALANCE_LEDGER",
                actualCompensation, prior.id(),
                "Compensated superseded greytHR leave ledger effect.",
                Map.of("supersededFactId", fact.supersedesId().toString()));
        }
        UUID ledgerId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO leave_balance_ledger
                (id, employee_id, leave_type_id, entry_type, quantity,
                 effective_date, idempotency_key, reference_type,
                 reference_id, reason, recorded_by_subject)
            VALUES (?, ?, ?, 'LEAVE_CONSUMED', ?, ?, ?,
                    'GREYTHR_FACT', ?,
                    'greytHR authoritative leave import', 'service-greythr')
            ON CONFLICT (employee_id, leave_type_id, idempotency_key) DO NOTHING
            """, ledgerId, fact.employeeId(), leaveTypeId, -units,
            fact.workDate(), "greythr:" + fact.id() + ":consume", fact.id());
        UUID actualLedger = jdbc.queryForObject("""
            SELECT id FROM leave_balance_ledger
            WHERE employee_id = ? AND leave_type_id = ?
              AND idempotency_key = ?
            """, UUID.class, fact.employeeId(), leaveTypeId,
            "greythr:" + fact.id() + ":consume");
        recordApplication(
            fact, "APPLY", "LEAVE_BALANCE_LEDGER", actualLedger,
            prior == null ? null : prior.id(),
            "Applied validated greytHR leave fact.",
            Map.of("payloadHash", fact.payloadHash(), "units", units));
    }

    private ProviderFact providerFact(UUID id) {
        ProviderFact fact = jdbc.query("""
            SELECT fact.id, fact.connection_id, fact.organization_id,
                   fact.employee_id, fact.work_date, fact.provider_record_id,
                   fact.payload_hash, fact.supersedes_id,
                   run.correlation_id, fact.payload::text
            FROM greythr_imported_facts fact
            JOIN greythr_sync_runs run ON run.id = fact.sync_run_id
            WHERE fact.id = ?
            """, result -> result.next() ? new ProviderFact(
                result.getObject("id", UUID.class),
                result.getObject("connection_id", UUID.class),
                result.getObject("organization_id", UUID.class),
                result.getObject("employee_id", UUID.class),
                result.getObject("work_date", LocalDate.class),
                result.getString("provider_record_id"),
                result.getString("payload_hash"),
                result.getObject("supersedes_id", UUID.class),
                result.getObject("correlation_id", UUID.class),
                result.getString("payload")
            ) : null, id);
        if (fact == null) {
            throw notFound();
        }
        return fact;
    }

    private PriorApplication priorApplication(
        UUID supersededFactId,
        String targetKind
    ) {
        if (supersededFactId == null) {
            return null;
        }
        return jdbc.query("""
            SELECT id, target_record_id
            FROM greythr_fact_applications
            WHERE provider_fact_id = ?
              AND action = 'APPLY'
              AND target_kind = ?
            ORDER BY applied_at DESC, id DESC
            LIMIT 1
            """, result -> result.next() ? new PriorApplication(
                result.getObject("id", UUID.class),
                result.getObject("target_record_id", UUID.class)
            ) : null, supersededFactId, targetKind);
    }

    private void recordApplication(
        ProviderFact fact,
        String action,
        String targetKind,
        UUID targetRecordId,
        UUID supersedesApplicationId,
        String reason,
        Map<String, Object> metadata
    ) {
        jdbc.update("""
            INSERT INTO greythr_fact_applications(
                id, connection_id, organization_id, provider_fact_id,
                action, target_kind, target_record_id,
                supersedes_application_id, correlation_id, reason,
                metadata, applied_by_subject
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb,
                    'service-greythr')
            ON CONFLICT (
                provider_fact_id, action, target_kind, target_record_id
            ) DO NOTHING
            """, UUID.randomUUID(), fact.connectionId(),
            fact.organizationId(), fact.id(), action, targetKind,
            targetRecordId, supersedesApplicationId, fact.correlationId(),
            reason, json(metadata));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        return mapper.readValue(json, Map.class);
    }

    private ReconciliationView oneReconciliation(UUID id) {
        return jdbc.queryForObject("""
            SELECT id, sync_run_id, employee_id, work_date, conflict_type,
                   status, decision_reason, decided_at
            FROM greythr_reconciliation_items
            WHERE id = ?
            """, (result, row) -> new ReconciliationView(
                result.getObject("id", UUID.class),
                result.getObject("sync_run_id", UUID.class),
                result.getObject("employee_id", UUID.class),
                result.getObject("work_date", LocalDate.class),
                result.getString("conflict_type"),
                result.getString("status"),
                result.getString("decision_reason"),
                result.getObject("decided_at", OffsetDateTime.class)
            ), id);
    }

    private List<SyncRow> syncRows(String where, Object... arguments) {
        return jdbc.query("""
            SELECT run.id, run.connection_id, run.request_hash, run.status,
                   run.date_from, run.date_to, run.employee_count,
                   run.attendance_count, run.leave_count, run.conflict_count,
                   run.page_count, run.error_code, run.started_at,
                   run.completed_at, connection.last_success_at
            FROM greythr_sync_runs run
            JOIN greythr_connections connection ON connection.id = run.connection_id
            """ + where, (result, row) -> new SyncRow(
                result.getObject("id", UUID.class),
                result.getObject("connection_id", UUID.class),
                result.getString("request_hash"),
                result.getString("status"),
                result.getObject("date_from", LocalDate.class),
                result.getObject("date_to", LocalDate.class),
                result.getInt("employee_count"),
                result.getInt("attendance_count"),
                result.getInt("leave_count"),
                result.getInt("conflict_count"),
                result.getInt("page_count"),
                result.getString("error_code"),
                result.getObject("started_at", OffsetDateTime.class),
                result.getObject("completed_at", OffsetDateTime.class),
                result.getObject("last_success_at", OffsetDateTime.class)
            ), arguments);
    }

    private SyncRunView syncRun(UUID id) {
        return view(syncRows("WHERE run.id = ?", id).getFirst());
    }

    private SyncRunView view(SyncRow row) {
        boolean stale = sourceIsStale(
            row.lastSuccessAt(),
            "DEGRADED".equals(row.status()) || "FAILED".equals(row.status()));
        return new SyncRunView(
            row.id(), row.connectionId(), row.status(), row.dateFrom(),
            row.dateTo(), row.employeeCount(), row.attendanceCount(),
            row.leaveCount(), row.conflictCount(), row.pageCount(),
            row.errorCode(), row.startedAt(), row.completedAt(),
            row.lastSuccessAt(), stale);
    }

    private boolean sourceIsStale(
        OffsetDateTime lastSuccessAt,
        boolean currentAttemptDegraded
    ) {
        return currentAttemptDegraded
            || lastSuccessAt == null
            || Duration.between(lastSuccessAt, OffsetDateTime.now(clock))
                .compareTo(FRESHNESS_LIMIT) > 0;
    }

    private String json(Object value) {
        return mapper.writeValueAsString(value);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable.", exception);
        }
    }

    private EntityNotFoundException notFound() {
        return new EntityNotFoundException("Resource not found.");
    }

    private record Connection(
        UUID id,
        UUID organizationId,
        String status,
        String adapterMode,
        UUID certificationId,
        OffsetDateTime lastAttemptAt,
        OffsetDateTime lastSuccessAt,
        String lastErrorCode
    ) {
    }

    private record SyncRow(
        UUID id,
        UUID connectionId,
        String requestHash,
        String status,
        LocalDate dateFrom,
        LocalDate dateTo,
        int employeeCount,
        int attendanceCount,
        int leaveCount,
        int conflictCount,
        int pageCount,
        String errorCode,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime lastSuccessAt
    ) {
    }

    private record ReconciliationRow(
        UUID id,
        UUID connectionId,
        UUID factId,
        String status,
        String conflictType
    ) {
    }

    private record CurrentSource(UUID id, LocalDate validFrom) {
    }

    private record SyncStart(
        UUID runId,
        Connection connection,
        String requestHash,
        SyncRunView replay
    ) {
    }


    private record ProviderFact(
        UUID id,
        UUID connectionId,
        UUID organizationId,
        UUID employeeId,
        LocalDate workDate,
        String providerRecordId,
        String payloadHash,
        UUID supersedesId,
        UUID correlationId,
        String payload
    ) {
    }

    private record PriorApplication(UUID id, UUID targetRecordId) {
    }

    private record PriorLedger(UUID leaveTypeId, double quantity) {
    }

    private record StoredFact(UUID id, boolean inserted) {
    }

    private record FactToApply(UUID id, String kind) {
    }

    private record ProbeEvidence(
        UUID id,
        String hash,
        OffsetDateTime probedAt,
        String adapterMode
    ) {
    }
}
