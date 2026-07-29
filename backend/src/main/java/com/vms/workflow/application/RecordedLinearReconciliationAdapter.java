package com.vms.workflow.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Deterministic local/provider-neutral adapter. Production can replace this
 * boundary with a least-privilege OAuth GraphQL implementation without
 * changing checkpoint, retry, or evidence semantics.
 */
@Component
public class RecordedLinearReconciliationAdapter
    implements LinearReconciliationAdapter {

    private final JdbcTemplate jdbc;

    public RecordedLinearReconciliationAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ReconciliationPage fetchUpdatedIssues(
        UUID connectionId,
        ReconciliationCursor cursor,
        int limit
    ) {
        if (limit < 1 || limit > 250) {
            throw new IllegalArgumentException(
                "Linear reconciliation page size must be between 1 and 250.");
        }
        List<ReconciledIssue> rows = jdbc.query("""
            SELECT linear_issue_uuid, identifier, issue_url, title,
                   provider_state_id, provider_state_name,
                   provider_state_type, provider_state_category,
                   provider_updated_at, payload_hash
            FROM linear_recorded_issue_metadata
            WHERE connection_id = ?
              AND (
                  ?::timestamptz IS NULL
                  OR (provider_updated_at, linear_issue_uuid)
                     > (?::timestamptz, ?::uuid)
              )
            ORDER BY provider_updated_at, linear_issue_uuid
            LIMIT ?
            """, (rs, rowNum) -> new ReconciledIssue(
                rs.getObject("linear_issue_uuid", UUID.class),
                rs.getString("identifier"),
                rs.getString("issue_url"),
                rs.getString("title"),
                rs.getString("provider_state_id"),
                rs.getString("provider_state_name"),
                rs.getString("provider_state_type"),
                rs.getString("provider_state_category"),
                rs.getObject("provider_updated_at", java.time.OffsetDateTime.class),
                rs.getString("payload_hash")),
            connectionId,
            cursor == null ? null : cursor.updatedAt(),
            cursor == null ? null : cursor.updatedAt(),
            cursor == null ? null : cursor.issueUuid(),
            limit + 1);
        boolean hasNext = rows.size() > limit;
        List<ReconciledIssue> page = hasNext
            ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        ReconciliationCursor next = page.isEmpty() ? cursor
            : new ReconciliationCursor(
                page.get(page.size() - 1).providerUpdatedAt(),
                page.get(page.size() - 1).issueUuid());
        return new ReconciliationPage(page, next, hasNext, List.of());
    }
}
