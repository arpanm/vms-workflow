package com.vms.workflow.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Provider boundary for Linear delta reconciliation. Implementations must
 * preserve GraphQL's data-plus-errors semantics instead of treating HTTP 200
 * as an unconditional success.
 */
public interface LinearReconciliationAdapter {
    ReconciliationPage fetchUpdatedIssues(
        UUID connectionId,
        ReconciliationCursor cursor,
        int limit
    );

    record ReconciliationCursor(
        OffsetDateTime updatedAt,
        UUID issueUuid
    ) {
    }

    record ReconciledIssue(
        UUID issueUuid,
        String identifier,
        String url,
        String title,
        String stateId,
        String stateName,
        String stateType,
        String stateCategory,
        OffsetDateTime providerUpdatedAt,
        String payloadHash
    ) {
    }

    record ProviderError(
        String code,
        String message,
        boolean retryable
    ) {
    }

    record ReconciliationPage(
        List<ReconciledIssue> issues,
        ReconciliationCursor nextCursor,
        boolean hasNextPage,
        List<ProviderError> errors
    ) {
        public ReconciliationPage {
            issues = List.copyOf(issues);
            errors = List.copyOf(errors);
            if (hasNextPage && nextCursor == null) {
                throw new IllegalArgumentException(
                    "A paginated provider response requires a next cursor.");
            }
        }
    }
}
