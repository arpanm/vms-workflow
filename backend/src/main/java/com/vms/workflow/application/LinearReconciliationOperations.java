package com.vms.workflow.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class LinearReconciliationOperations {
    private final JdbcTemplate jdbc;
    private final LinearReconciliationAdapter adapter;
    private final LinearReconciliationConfiguration configuration;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public LinearReconciliationOperations(
        JdbcTemplate jdbc,
        LinearReconciliationAdapter adapter,
        LinearReconciliationConfiguration configuration,
        TransactionTemplate transactions,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.adapter = adapter;
        this.configuration = configuration;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Scheduled(
        fixedDelayString = "${vms.linear.reconciliation.worker-delay:PT1M}",
        initialDelayString =
            "${vms.linear.reconciliation.worker-initial-delay:PT30S}"
    )
    public void scheduledRun() {
        if (configuration.workerEnabled()) {
            runDueOnce();
        }
    }

    public boolean runDueOnce() {
        UUID connectionId = transactions.execute(ignored -> claimDueConnection());
        if (connectionId == null) {
            return false;
        }
        reconcileConnection(connectionId);
        return true;
    }

    public UUID reconcileConnection(UUID connectionId) {
        Checkpoint checkpoint = transactions.execute(
            ignored -> begin(connectionId));
        if (checkpoint == null) {
            throw new IllegalArgumentException(
                "Only a configured connection can be reconciled.");
        }
        UUID jobId = checkpoint.jobId();
        LinearReconciliationAdapter.ReconciliationCursor cursor =
            checkpoint.cursor();
        int attempt = 0;
        try {
            while (attempt < configuration.maxPagesPerRun()) {
                attempt++;
                OffsetDateTime startedAt = OffsetDateTime.now(clock);
                LinearReconciliationAdapter.ReconciliationPage page =
                    adapter.fetchUpdatedIssues(
                        connectionId, cursor, configuration.pageSize());
                if (page.issues().size() > configuration.pageSize()) {
                    throw new IllegalStateException(
                        "Provider returned more rows than the requested page bound.");
                }
                OffsetDateTime completedAt = OffsetDateTime.now(clock);
                if (!page.errors().isEmpty()) {
                    recordPartial(
                        jobId, connectionId, attempt, cursor, page,
                        startedAt, completedAt);
                    fail(jobId, connectionId, "GRAPHQL_PARTIAL_ERROR");
                    return jobId;
                }
                applyPage(
                    jobId, connectionId, attempt, cursor, page,
                    startedAt, completedAt);
                cursor = page.nextCursor();
                if (!page.hasNextPage()) {
                    succeed(jobId, connectionId, false);
                    return jobId;
                }
            }
            succeed(jobId, connectionId, true);
            return jobId;
        } catch (RuntimeException failure) {
            recordFailure(jobId, connectionId, Math.max(1, attempt), cursor, failure);
            fail(jobId, connectionId, providerErrorCode(failure));
            return jobId;
        }
    }

    private UUID claimDueConnection() {
        jdbc.update("""
            INSERT INTO linear_reconciliation_checkpoints (connection_id)
            SELECT id FROM linear_connections
            ON CONFLICT DO NOTHING
            """);
        return jdbc.query("""
            WITH candidate AS (
                SELECT checkpoint.connection_id
                FROM linear_reconciliation_checkpoints checkpoint
                JOIN linear_connections connection
                  ON connection.id = checkpoint.connection_id
                WHERE checkpoint.next_run_at <= CURRENT_TIMESTAMP
                  AND connection.status <> 'NOT_CONFIGURED'
                ORDER BY checkpoint.next_run_at, checkpoint.connection_id
                FOR UPDATE OF checkpoint SKIP LOCKED
                LIMIT 1
            )
            UPDATE linear_reconciliation_checkpoints checkpoint
            SET next_run_at = CURRENT_TIMESTAMP + INTERVAL '2 minutes',
                last_started_at = CURRENT_TIMESTAMP,
                version = version + 1
            FROM candidate
            WHERE checkpoint.connection_id = candidate.connection_id
            RETURNING checkpoint.connection_id
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null);
    }

    private Checkpoint begin(UUID connectionId) {
        Boolean configured = jdbc.query("""
            SELECT status <> 'NOT_CONFIGURED'
            FROM linear_connections WHERE id = ?
            FOR UPDATE
            """, rs -> rs.next() ? rs.getBoolean(1) : null, connectionId);
        if (!Boolean.TRUE.equals(configured)) {
            return null;
        }
        jdbc.update("""
            INSERT INTO linear_reconciliation_checkpoints
                (connection_id, last_started_at)
            VALUES (?, CURRENT_TIMESTAMP)
            ON CONFLICT (connection_id) DO UPDATE
            SET last_started_at = CURRENT_TIMESTAMP,
                version = linear_reconciliation_checkpoints.version + 1
            """, connectionId);
        LinearReconciliationAdapter.ReconciliationCursor cursor = jdbc.query("""
            SELECT cursor_updated_at, cursor_issue_uuid
            FROM linear_reconciliation_checkpoints
            WHERE connection_id = ?
            FOR UPDATE
            """, rs -> rs.next() && rs.getObject(1) != null
                ? new LinearReconciliationAdapter.ReconciliationCursor(
                    rs.getObject(1, OffsetDateTime.class),
                    rs.getObject(2, UUID.class))
                : null, connectionId);
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO linear_sync_jobs
                (id, connection_id, job_type, status, checkpoint,
                 attempt_count, created_at)
            VALUES (?, ?, 'DELTA', 'RUNNING', ?, 0, CURRENT_TIMESTAMP)
            """, jobId, connectionId, cursorText(cursor));
        return new Checkpoint(jobId, cursor);
    }

    private void applyPage(
        UUID jobId,
        UUID connectionId,
        int attempt,
        LinearReconciliationAdapter.ReconciliationCursor requestedCursor,
        LinearReconciliationAdapter.ReconciliationPage page,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt
    ) {
        transactions.executeWithoutResult(ignored -> {
            int applied = 0;
            for (LinearReconciliationAdapter.ReconciledIssue issue : page.issues()) {
                String normalized = normalizedState(
                    connectionId, issue.stateType(), issue.stateCategory());
                applied += jdbc.update("""
                    INSERT INTO linear_issue_current
                        (connection_id, linear_issue_uuid, identifier, issue_url,
                         title, provider_state_id, provider_state_name,
                         provider_state_type, provider_state_category,
                         normalized_state, provider_updated_at, fetched_at,
                         payload_hash, stale, inaccessible)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, FALSE
                    WHERE EXISTS (
                        SELECT 1 FROM linear_issue_links
                        WHERE connection_id = ? AND linear_issue_uuid = ?
                    )
                    ON CONFLICT (connection_id, linear_issue_uuid) DO UPDATE SET
                        identifier = EXCLUDED.identifier,
                        issue_url = EXCLUDED.issue_url,
                        title = EXCLUDED.title,
                        provider_state_id = EXCLUDED.provider_state_id,
                        provider_state_name = EXCLUDED.provider_state_name,
                        provider_state_type = EXCLUDED.provider_state_type,
                        provider_state_category = EXCLUDED.provider_state_category,
                        normalized_state = EXCLUDED.normalized_state,
                        provider_updated_at = EXCLUDED.provider_updated_at,
                        fetched_at = EXCLUDED.fetched_at,
                        payload_hash = EXCLUDED.payload_hash,
                        stale = FALSE,
                        inaccessible = FALSE
                    WHERE linear_issue_current.provider_updated_at IS NULL
                       OR linear_issue_current.provider_updated_at
                          <= EXCLUDED.provider_updated_at
                    """, connectionId, issue.issueUuid(), issue.identifier(),
                    issue.url(), issue.title(), issue.stateId(), issue.stateName(),
                    issue.stateType(), issue.stateCategory(), normalized,
                    issue.providerUpdatedAt(), completedAt, issue.payloadHash(),
                    connectionId, issue.issueUuid());
            }
            insertAttempt(
                jobId, connectionId, attempt, requestedCursor, page.nextCursor(),
                page.issues().size(), applied, "SUCCEEDED", List.of(), null,
                startedAt, completedAt);
            updateCursor(connectionId, page.nextCursor());
            jdbc.update("""
                UPDATE linear_sync_jobs
                SET checkpoint = ?, attempt_count = ?
                WHERE id = ?
                """, cursorText(page.nextCursor()), attempt, jobId);
        });
    }

    private void recordPartial(
        UUID jobId,
        UUID connectionId,
        int attempt,
        LinearReconciliationAdapter.ReconciliationCursor requestedCursor,
        LinearReconciliationAdapter.ReconciliationPage page,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt
    ) {
        transactions.executeWithoutResult(ignored -> insertAttempt(
            jobId, connectionId, attempt, requestedCursor, page.nextCursor(),
            page.issues().size(), 0, "PARTIAL", page.errors(), null,
            startedAt, completedAt));
    }

    private void recordFailure(
        UUID jobId,
        UUID connectionId,
        int attempt,
        LinearReconciliationAdapter.ReconciliationCursor cursor,
        RuntimeException failure
    ) {
        OffsetDateTime at = OffsetDateTime.now(clock);
        String code = providerErrorCode(failure);
        transactions.executeWithoutResult(ignored -> insertAttempt(
            jobId, connectionId, Math.max(1, attempt), cursor, cursor,
            0, 0, "FAILED",
            List.of(new LinearReconciliationAdapter.ProviderError(
                code, safeMessage(failure), true)),
            code, at, at));
    }

    private void insertAttempt(
        UUID jobId,
        UUID connectionId,
        int attempt,
        LinearReconciliationAdapter.ReconciliationCursor requestedCursor,
        LinearReconciliationAdapter.ReconciliationCursor responseCursor,
        int fetched,
        int applied,
        String outcome,
        List<LinearReconciliationAdapter.ProviderError> errors,
        String errorCode,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt
    ) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("jobId", jobId.toString());
        evidence.put("attempt", attempt);
        evidence.put("requestedCursor", cursorText(requestedCursor));
        evidence.put("responseCursor", cursorText(responseCursor));
        evidence.put("fetched", fetched);
        evidence.put("applied", applied);
        evidence.put("outcome", outcome);
        evidence.put("errors", errors);
        jdbc.update("""
            INSERT INTO linear_reconciliation_attempts
                (id, sync_job_id, connection_id, attempt_number,
                 requested_cursor, response_cursor, requested_limit,
                 fetched_count, applied_count, partial_error_count,
                 outcome, provider_errors, error_code, evidence_checksum,
                 started_at, completed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            """, UUID.randomUUID(), jobId, connectionId, attempt,
            cursorText(requestedCursor), cursorText(responseCursor),
            configuration.pageSize(), fetched, applied, errors.size(), outcome,
            json(errors), errorCode, sha256(json(evidence)), startedAt, completedAt);
        jdbc.update("""
            UPDATE linear_sync_jobs
            SET attempt_count = GREATEST(attempt_count, ?)
            WHERE id = ?
            """, attempt, jobId);
    }

    private void updateCursor(
        UUID connectionId,
        LinearReconciliationAdapter.ReconciliationCursor cursor
    ) {
        jdbc.update("""
            UPDATE linear_reconciliation_checkpoints
            SET cursor_updated_at = ?, cursor_issue_uuid = ?, version = version + 1
            WHERE connection_id = ?
            """, cursor == null ? null : cursor.updatedAt(),
            cursor == null ? null : cursor.issueUuid(), connectionId);
    }

    private void succeed(UUID jobId, UUID connectionId, boolean morePages) {
        transactions.executeWithoutResult(ignored -> {
            OffsetDateTime completedAt = OffsetDateTime.now(clock);
            jdbc.update("""
                UPDATE linear_sync_jobs
                SET status = 'SUCCEEDED', completed_at = ?
                WHERE id = ?
                """, completedAt, jobId);
            jdbc.update("""
                UPDATE linear_reconciliation_checkpoints
                SET last_completed_at = ?, consecutive_failures = 0,
                    last_error_code = NULL,
                    next_run_at = ? + (? * INTERVAL '1 millisecond'),
                    version = version + 1
                WHERE connection_id = ?
                """, completedAt, completedAt,
                morePages ? 1 : configuration.successInterval().toMillis(),
                connectionId);
            jdbc.update("""
                UPDATE linear_connections
                SET status = 'CONNECTED', last_error_code = NULL,
                    last_reconciled_at = ?
                WHERE id = ?
                """, completedAt, connectionId);
        });
    }

    private void fail(UUID jobId, UUID connectionId, String errorCode) {
        transactions.executeWithoutResult(ignored -> {
            OffsetDateTime completedAt = OffsetDateTime.now(clock);
            jdbc.update("""
                UPDATE linear_sync_jobs
                SET status = 'FAILED', last_error_code = ?, completed_at = ?
                WHERE id = ?
                """, errorCode, completedAt, jobId);
            Integer failures = jdbc.queryForObject("""
                UPDATE linear_reconciliation_checkpoints
                SET last_completed_at = ?,
                    consecutive_failures = consecutive_failures + 1,
                    last_error_code = ?,
                    next_run_at = ? + (? * INTERVAL '1 millisecond'),
                    version = version + 1
                WHERE connection_id = ?
                RETURNING consecutive_failures
                """, Integer.class, completedAt, errorCode, completedAt,
                configuration.retryDelay().toMillis(), connectionId);
            if (failures != null && failures >= configuration.maxAttempts()) {
                jdbc.update("""
                    UPDATE linear_sync_jobs
                    SET status = 'DEAD_LETTER'
                    WHERE id = ?
                    """, jobId);
            }
            jdbc.update("""
                UPDATE linear_connections
                SET status = 'ACTION_REQUIRED', last_error_code = ?
                WHERE id = ?
                """, errorCode, connectionId);
            jdbc.update("""
                UPDATE linear_issue_current SET stale = TRUE
                WHERE connection_id = ?
                """, connectionId);
        });
    }

    private String normalizedState(
        UUID connectionId,
        String type,
        String category
    ) {
        String normalized = jdbc.query("""
            SELECT mapping.normalized_state
            FROM linear_connections connection
            JOIN linear_state_mappings mapping
              ON mapping.connection_id = connection.id
             AND mapping.mapping_version = connection.mapping_version
            WHERE connection.id = ?
              AND LOWER(mapping.provider_state_type) = LOWER(?)
              AND mapping.provider_state_category IN (?, '')
            ORDER BY CASE
                WHEN mapping.provider_state_category = ? THEN 0 ELSE 1 END
            LIMIT 1
            """, rs -> rs.next() ? rs.getString(1) : null,
            connectionId, type, category == null ? "" : category,
            category == null ? "" : category);
        return normalized == null ? "UNKNOWN" : normalized;
    }

    private static String cursorText(
        LinearReconciliationAdapter.ReconciliationCursor cursor
    ) {
        return cursor == null ? null
            : cursor.updatedAt() + "|" + cursor.issueUuid();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Cannot serialize reconciliation evidence.", exception);
        }
    }

    private static String providerErrorCode(RuntimeException failure) {
        return failure instanceof IllegalArgumentException
            ? "SCHEMA_INVALID" : "PROVIDER_UNAVAILABLE";
    }

    private static String safeMessage(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private record Checkpoint(
        UUID jobId,
        LinearReconciliationAdapter.ReconciliationCursor cursor
    ) {
    }
}
