import { beforeEach, describe, expect, it, vi } from "vitest";

const apiMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}));

vi.mock("@/lib/api-client", () => ({ apiClient: apiMocks }));

import { deliveryApi } from "./api";
import type {
  AssignmentView,
  IssueCurrentView,
  IssueSnapshotView,
  LinearHealthView,
} from "./contracts";

describe("delivery API contract", () => {
  beforeEach(() => {
    apiMocks.get.mockReset();
    apiMocks.post.mockReset();
  });

  it("loads plans only through engagement-month scope", async () => {
    apiMocks.get.mockResolvedValue([]);
    await deliveryApi.plans("month/1");
    expect(apiMocks.get).toHaveBeenCalledWith("/delivery/plans?engagementMonthId=month%2F1");
  });

  it("submits and approves the exact plan resource", async () => {
    apiMocks.post.mockResolvedValue({});
    await deliveryApi.submitPlan("plan/1");
    await deliveryApi.decidePlan("plan/1", {
      decision: "APPROVE",
      comment: "Reviewed",
    });
    expect(apiMocks.post).toHaveBeenNthCalledWith(1, "/delivery/plans/plan%2F1/submit");
    expect(apiMocks.post).toHaveBeenNthCalledWith(2, "/delivery/plans/plan%2F1/approvals", {
      decision: "APPROVE",
      comment: "Reviewed",
    });
  });

  it("loads revision comparison from the exact plan resource", async () => {
    apiMocks.get.mockResolvedValue({});
    await deliveryApi.revisionComparison("plan/1");
    expect(apiMocks.get).toHaveBeenCalledWith(
      "/delivery/plans/plan%2F1/revision-comparison",
    );
  });

  it("reads a bounded engagement-scoped commitment dead-letter list", async () => {
    apiMocks.get.mockResolvedValue([]);
    await deliveryApi.commitmentDeadLetters("engagement/1", 10);
    expect(apiMocks.get).toHaveBeenCalledWith(
      "/delivery/commitment-operations?engagementId=engagement%2F1&limit=10",
    );
  });

  it("replays only one outbox identifier with a command key", async () => {
    apiMocks.post.mockResolvedValue({});
    await deliveryApi.replayCommitment(
      "outbox/1",
      { reason: "Provider incident resolved" },
      "commitment-replay-key",
    );
    expect(apiMocks.post).toHaveBeenCalledWith(
      "/delivery/commitment-operations/outbox%2F1/replays",
      { reason: "Provider incident resolved" },
      { headers: { "Idempotency-Key": "commitment-replay-key" } },
    );
  });

  it("reads provider health without any credential fields in the request", async () => {
    apiMocks.get.mockResolvedValue({});
    await deliveryApi.linearHealth("engagement-1");
    expect(apiMocks.get).toHaveBeenCalledWith(
      "/integrations/linear/health?engagementId=engagement-1",
    );
  });

  it("records a provider-neutral reconciliation with an idempotency key", async () => {
    apiMocks.post.mockResolvedValue({});
    await deliveryApi.reconcileLinear(
      "connection/1",
      { outcome: "UNAVAILABLE", errorCode: "PROVIDER_UNAVAILABLE", reason: "Timed out" },
      "reconcile-key",
    );
    expect(apiMocks.post).toHaveBeenCalledWith(
      "/integrations/linear/connections/connection%2F1/reconciliations",
      { outcome: "UNAVAILABLE", errorCode: "PROVIDER_UNAVAILABLE", reason: "Timed out" },
      { headers: { "Idempotency-Key": "reconcile-key" } },
    );
  });

  it("links only a server-recorded issue identity and never posts provider metadata", async () => {
    apiMocks.post.mockResolvedValue({});
    await deliveryApi.linkIssue({
      deliverableVersionId: "deliverable-version-1",
      connectionId: "connection-1",
      issueUuid: "issue-1",
      rationale: "Two issues are required for this outcome.",
    });
    expect(apiMocks.post).toHaveBeenCalledWith("/integrations/linear/links", {
      deliverableVersionId: "deliverable-version-1",
      connectionId: "connection-1",
      issueUuid: "issue-1",
      rationale: "Two issues are required for this outcome.",
    });
  });

  it("preserves nullable provider fields and every snapshot status at the API boundary", async () => {
    const current = {
      issueUuid: "issue-1",
      identifier: "CAD-1",
      url: "https://linear.app/cadence/issue/CAD-1",
      title: "Recorded issue",
      providerStateId: null,
      providerStateName: null,
      providerStateType: null,
      providerStateCategory: null,
      normalizedState: "UNKNOWN",
      updatedAt: null,
      fetchedAt: "2026-07-26T10:00:00Z",
      payloadHash: "hash",
      stale: true,
      inaccessible: true,
      executionProjection: "UNKNOWN",
    } satisfies IssueCurrentView;
    const snapshots = [
      {
        id: "snapshot-1",
        snapshotType: "PLAN_TIME",
        status: "CAPTURED",
        normalizedState: "STARTED",
        providerStateId: null,
        providerStateName: "In Progress",
        providerStateType: null,
        providerStateCategory: null,
        fetchedAt: "2026-07-26T10:00:00Z",
        payloadHash: "hash",
        confidence: "CURRENT_STATE_ONLY",
        failureReason: null,
      },
      {
        id: "snapshot-2",
        snapshotType: "MONTH_END",
        status: "FETCH_FAILED",
        normalizedState: null,
        providerStateId: null,
        providerStateName: null,
        providerStateType: null,
        providerStateCategory: null,
        fetchedAt: null,
        payloadHash: null,
        confidence: "UNAVAILABLE",
        failureReason: "PROVIDER_INACCESSIBLE",
      },
      {
        id: "snapshot-3",
        snapshotType: "HISTORICAL_RETRIEVAL",
        status: "UNAVAILABLE",
        normalizedState: null,
        providerStateId: null,
        providerStateName: null,
        providerStateType: null,
        providerStateCategory: null,
        fetchedAt: null,
        payloadHash: null,
        confidence: "SOURCE_EXPORT",
        failureReason: "NO_HISTORY",
      },
    ] satisfies IssueSnapshotView[];
    apiMocks.get.mockResolvedValueOnce(current).mockResolvedValueOnce(snapshots);
    await expect(deliveryApi.issueCurrent("link-1")).resolves.toEqual(current);
    await expect(deliveryApi.issueSnapshots("link-1")).resolves.toEqual(snapshots);
  });

  it("models nullable assignment end dates and exception reasons as explicit response fields", () => {
    const assignment = {
      id: "assignment-1",
      employeeId: "employee-1",
      effectiveFrom: "2026-07-01",
      effectiveTo: null,
      exceptionReason: null,
    } satisfies AssignmentView;
    expect(assignment).toMatchObject({
      effectiveTo: null,
      exceptionReason: null,
    });
  });

  it("preserves nullable no-connection health and all connection statuses", async () => {
    const values = [
      {
        connectionId: null,
        status: "NOT_CONFIGURED",
        providerRegistrationStatus: "EXTERNALLY_BLOCKED",
        lastVerifiedDeliveryAt: null,
        lastReconciledAt: null,
        linkedIssueCount: 0,
        staleIssueCount: 0,
        queuedCount: 0,
        deadLetterCount: 0,
        lastError: "PROVIDER_NOT_CONFIGURED",
      },
      {
        connectionId: "connection-1",
        status: "CONNECTED",
        providerRegistrationStatus: "CONFIGURED",
        lastVerifiedDeliveryAt: "2026-07-26T10:00:00Z",
        lastReconciledAt: null,
        linkedIssueCount: 2,
        staleIssueCount: 0,
        queuedCount: 0,
        deadLetterCount: 0,
        lastError: null,
      },
      {
        connectionId: "connection-2",
        status: "ACTION_REQUIRED",
        providerRegistrationStatus: "NOT_CONFIGURED",
        lastVerifiedDeliveryAt: null,
        lastReconciledAt: "2026-07-26T10:00:00Z",
        linkedIssueCount: 2,
        staleIssueCount: 2,
        queuedCount: 1,
        deadLetterCount: 1,
        lastError: "WEBHOOK_REAUTH_REQUIRED",
      },
    ] satisfies LinearHealthView[];
    for (const health of values) {
      apiMocks.get.mockResolvedValueOnce(health);
      await expect(deliveryApi.linearHealth("engagement-1")).resolves.toEqual(health);
    }
  });
});
