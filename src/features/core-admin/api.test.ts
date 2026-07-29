import { beforeEach, describe, expect, it, vi } from "vitest";

const apiMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  patch: vi.fn(),
}));

vi.mock("@/lib/api-client", () => ({ apiClient: apiMocks }));

import { coreAdminApi } from "./api";

describe("core administration API contract", () => {
  beforeEach(() => {
    apiMocks.get.mockReset();
    apiMocks.post.mockReset();
    apiMocks.patch.mockReset();
  });

  it("binds every collection read to the selected engagement path", async () => {
    apiMocks.get.mockResolvedValue([]);
    await coreAdminApi.contactGroups("engagement/one");
    await coreAdminApi.approvalPolicies("engagement/one");
    await coreAdminApi.delegations("engagement/one");

    expect(apiMocks.get).toHaveBeenNthCalledWith(
      1,
      "/core/engagements/engagement%2Fone/contact-groups",
    );
    expect(apiMocks.get).toHaveBeenNthCalledWith(
      2,
      "/core/engagements/engagement%2Fone/approval-policies",
    );
    expect(apiMocks.get).toHaveBeenNthCalledWith(
      3,
      "/core/engagements/engagement%2Fone/delegations",
    );
  });

  it("sends the exact governance version and target state", async () => {
    apiMocks.post.mockResolvedValue({
      id: "transition-1",
      engagementMonthId: "month-1",
      fromState: "DRAFT",
      toState: "PLANNING",
      fromVersion: 2,
      toVersion: 3,
      actorSubject: "admin",
      reason: "Planning prerequisites are complete.",
      correlationId: "00000000-0000-0000-0000-000000000001",
      transitionedAt: "2026-07-29T10:00:00Z",
    });

    await coreAdminApi.transitionMonth("month-1", {
      targetState: "PLANNING",
      reason: "Planning prerequisites are complete.",
      expectedVersion: 2,
    });

    expect(apiMocks.post).toHaveBeenCalledWith(
      "/core/engagement-months/month-1/transitions",
      {
        targetState: "PLANNING",
        reason: "Planning prerequisites are complete.",
        expectedVersion: 2,
      },
    );
  });

  it("rejects malformed Java response shapes at runtime", async () => {
    apiMocks.get.mockResolvedValue([
      {
        id: "group-1",
        engagementId: "engagement-1",
        code: "PROC",
        name: "Procurement",
        groupType: "PROCUREMENT_CC",
        status: "ACTIVE",
        version: "not-a-number",
        members: [],
      },
    ]);

    await expect(
      coreAdminApi.contactGroups("engagement-1"),
    ).rejects.toMatchObject({ name: "ZodError" });
  });

  it("publishes only the server-returned policy version", async () => {
    apiMocks.post.mockResolvedValue({
      id: "policy-1",
      engagementId: "engagement-1",
      projectId: null,
      code: "PLAN",
      name: "Plan approval",
      actionType: "PLAN_APPROVAL",
      status: "ACTIVE",
      version: 4,
      policyVersionId: "policy-version-1",
      policyVersion: 1,
      versionStatus: "PUBLISHED",
      validFrom: "2026-08-01",
      validTo: null,
      prohibitSelfApproval: true,
      evidenceRequired: true,
      rules: {},
      stages: [],
    });

    await coreAdminApi.publishApprovalPolicy("policy-1", {
      expectedPolicyVersion: 3,
    });

    expect(apiMocks.post).toHaveBeenCalledWith(
      "/core/approval-policies/policy-1/publish",
      { expectedPolicyVersion: 3 },
    );
  });

  it("binds configuration publish to the displayed engagement version", async () => {
    apiMocks.post.mockResolvedValue({
      id: "configuration-2",
      engagementId: "engagement-1",
      version: 2,
      status: "PUBLISHED",
      validFrom: "2026-09-01",
      validTo: null,
      timezone: "Asia/Kolkata",
      planningDueDay: 25,
      certificationDueDay: 5,
      confirmationDueDay: 7,
      reopenPolicy: { approvalRequired: true },
      notificationPolicy: { recipientSnapshotRequired: true },
      publishedAt: "2026-07-29T10:00:00Z",
    });
    const input = {
      validFrom: "2026-09-01",
      timezone: "Asia/Kolkata",
      planningDueDay: 25,
      certificationDueDay: 5,
      confirmationDueDay: 7,
      reopenPolicy: { approvalRequired: true },
      notificationPolicy: { recipientSnapshotRequired: true },
      expectedEngagementVersion: 4,
    };

    await coreAdminApi.publishConfiguration("engagement-1", input);

    expect(apiMocks.post).toHaveBeenCalledWith(
      "/core/engagements/engagement-1/configurations",
      input,
    );
  });

  it("creates and acts on exact-version approval requests", async () => {
    const response = {
      id: "request-1",
      policyId: "policy-1",
      policyVersionId: "policy-version-1",
      engagementId: "engagement-1",
      projectId: null,
      objectType: "MONTH_PLAN",
      objectId: "11111111-1111-1111-1111-111111111111",
      objectVersion: 7,
      objectHash: "a".repeat(64),
      requiredPermissionCode: "deliverable.plan.approve",
      currentStageOrder: 1,
      status: "PENDING",
      version: 0,
      requestedBySubject: "admin",
      requestedAt: "2026-07-29T10:00:00Z",
      evidenceRequired: true,
      stages: [],
      actions: [],
    };
    apiMocks.post.mockResolvedValue(response);
    const createInput = {
      policyId: "policy-1",
      objectId: "11111111-1111-1111-1111-111111111111",
      idempotencyKey: "approval-request:stable-retry",
    };

    await coreAdminApi.createApprovalRequest("engagement-1", createInput);
    await coreAdminApi.actOnApprovalRequest("request-1", {
      decision: "APPROVED",
      reason: "Exact evidence reviewed.",
      delegationId: "delegation-1",
      idempotencyKey: "approval-action:stable-retry",
      expectedRequestVersion: 0,
    });

    expect(apiMocks.post).toHaveBeenNthCalledWith(
      1,
      "/core/engagements/engagement-1/approval-requests",
      createInput,
    );
    expect(apiMocks.post).toHaveBeenNthCalledWith(
      2,
      "/core/approval-requests/request-1/actions",
      {
        decision: "APPROVED",
        reason: "Exact evidence reviewed.",
        delegationId: "delegation-1",
        idempotencyKey: "approval-action:stable-retry",
        expectedRequestVersion: 0,
      },
    );
  });

  it("rejects an approval response with a non-SHA-256 object hash", async () => {
    apiMocks.get.mockResolvedValue({
      id: "request-1",
      policyId: "policy-1",
      policyVersionId: "policy-version-1",
      engagementId: "engagement-1",
      projectId: null,
      objectType: "MONTH_PLAN",
      objectId: "11111111-1111-1111-1111-111111111111",
      objectVersion: 7,
      objectHash: "not-a-sha256",
      requiredPermissionCode: "deliverable.plan.approve",
      currentStageOrder: 1,
      status: "PENDING",
      version: 0,
      requestedBySubject: "admin",
      requestedAt: "2026-07-29T10:00:00Z",
      evidenceRequired: true,
      stages: [],
      actions: [],
    });

    await expect(
      coreAdminApi.approvalRequest("request-1"),
    ).rejects.toMatchObject({ name: "ZodError" });
  });
});
