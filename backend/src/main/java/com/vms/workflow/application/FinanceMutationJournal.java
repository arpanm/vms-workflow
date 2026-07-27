package com.vms.workflow.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.vms.workflow.infrastructure.CorrelationIdFilter;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional F05 idempotency, domain-event/outbox and audit journal.
 */
@Service
public class FinanceMutationJournal {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final FinanceCanonicalJson canonical;

    public FinanceMutationJournal(
        JdbcTemplate jdbc,
        ObjectMapper json,
        FinanceCanonicalJson canonical
    ) {
        this.jdbc = jdbc;
        this.json = json;
        this.canonical = canonical;
    }

    public UUID replay(
        String actor,
        String operation,
        UUID scopeId,
        String key,
        Object request
    ) {
        requireKey(key);
        String hash = canonical.sha256(request);
        Idempotency row = jdbc.query("""
            SELECT request_hash, result_id
            FROM f05_idempotency_keys
            WHERE actor_subject = ? AND operation = ?
              AND scope_id = ? AND idempotency_key = ?
            """, rs -> rs.next()
                ? new Idempotency(rs.getString(1), rs.getObject(2, UUID.class))
                : null, actor, operation, scopeId, key);
        if (row == null) {
            return null;
        }
        if (!row.requestHash().equals(hash)) {
            throw new IllegalStateException(
                "Idempotency-Key was already used with a different request.");
        }
        return row.resultId();
    }

    public void remember(
        String actor,
        String operation,
        UUID scopeId,
        String key,
        Object request,
        String resultType,
        UUID resultId
    ) {
        try {
            jdbc.update("""
                INSERT INTO f05_idempotency_keys(
                    actor_subject, operation, scope_id, idempotency_key,
                    request_hash, result_type, result_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, actor, operation, scopeId, key, canonical.sha256(request),
                resultType, resultId);
        } catch (DuplicateKeyException exception) {
            UUID replay = replay(actor, operation, scopeId, key, request);
            if (!resultId.equals(replay)) {
                throw new IllegalStateException(
                    "Concurrent idempotent mutation produced a different result.", exception);
            }
        }
    }

    public UUID event(
        UUID monthId,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        long aggregateVersion,
        Map<String, Object> payload,
        String actor
    ) {
        UUID eventId = UUID.randomUUID();
        UUID correlationId = correlationId();
        jdbc.update("""
            INSERT INTO f05_domain_events(
                id, engagement_month_id, event_type, aggregate_type,
                aggregate_id, aggregate_version, payload, actor_subject,
                correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """, eventId, monthId, eventType, aggregateType, aggregateId,
            aggregateVersion, json(payload), actor, correlationId);
        jdbc.update("""
            INSERT INTO f05_outbox(id, event_id, status, next_attempt_at)
            VALUES (?, ?, 'PENDING', CURRENT_TIMESTAMP)
            """, UUID.randomUUID(), eventId);
        return eventId;
    }

    public void audit(
        UUID monthId,
        String action,
        String objectType,
        UUID objectId,
        Long objectVersion,
        String result,
        String reason,
        String actor,
        Map<String, Object> authority,
        List<Map<String, Object>> evidence
    ) {
        jdbc.update("""
            INSERT INTO f05_audit_events(
                id, engagement_month_id, action, object_type, object_id,
                object_version, result, reason_code, authority_snapshot,
                evidence_references, actor_subject, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
            """, UUID.randomUUID(), monthId, action, objectType, objectId,
            objectVersion, result, reason, json(authority), json(evidence),
            actor, correlationId());
    }

    public UUID correlationId() {
        return CorrelationIdFilter.currentOrNew();
    }

    private String json(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize finance journal data.", exception);
        }
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 160) {
            throw new IllegalArgumentException(
                "Idempotency-Key is required and must not exceed 160 characters.");
        }
    }

    private record Idempotency(String requestHash, UUID resultId) {
    }
}
