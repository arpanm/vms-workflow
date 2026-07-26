import { describe, expect, it } from "vitest";

import type { Deliverable, LinearIssue, PlanDraftInput, RecipientPreview } from "./domain";
import type { CreatePlanRequest } from "./contracts";
import { ApiError } from "@/lib/api-client";
import {
  baselineNotice,
  canDecidePlan,
  canRevisePlan,
  canSubmitPlan,
  classifyDeliveryError,
  commitmentStatusPresentation,
  connectionStatusPresentation,
  isFrozenPlan,
  isPlanContentReadOnly,
  issueLinkStatusPresentation,
  linearEvidenceNotice,
  normalizedStateLabel,
  planCoverage,
  planStateNotice,
  providerRegistrationPresentation,
  snapshotStatusPresentation,
  validateDeliverable,
  validateCreatePlanRequest,
  validateLinkIssue,
  validatePlanDraft,
  validateRecipientPreview,
  validateRevision,
} from "./presentation";

const issue: LinearIssue = {
  id: "link-1",
  issueUuid: "issue-uuid",
  identifier: "TEAM-123",
  url: "https://linear.app/example/issue/TEAM-123",
  title: "Fixture issue",
  currentState: {
    originalName: "Done",
    originalType: "completed",
    normalized: "COMPLETED",
  },
  planSnapshot: null,
  monthEndSnapshot: null,
  assigneeName: null,
  priority: null,
  fetchedAt: "2026-07-26T10:00:00Z",
  freshness: "FRESH",
  linkStatus: "ACTIVE",
  accessStatus: "AVAILABLE",
  errorCode: null,
};

const deliverable: Deliverable = {
  id: "deliverable-1",
  deliverableCode: "DEL-001",
  title: "Outcome",
  description: "Implementation context",
  businessObjective: "Business result",
  projectId: "project-1",
  productOwner: "Reliance owner",
  vendorOwner: "ArrowFoundry owner",
  priority: "P1",
  targetCompletionDate: "2026-08-28",
  acceptanceCriteria: [
    {
      id: null,
      statement: "Given a valid input, the expected outcome is produced.",
      validationMethod: "Automated test",
      expectedResult: "Expected outcome",
      mandatory: true,
    },
  ],
  evidenceExpectations: ["AUTOMATED_TEST"],
  dependencies: [
    {
      id: null,
      type: "EXTERNAL",
      reference: "None",
      owner: "Coordinator",
      targetResolutionDate: "2026-08-01",
      blocking: false,
    },
  ],
  riskAndAssumptions: "None",
  assignedEmployeeIds: ["employee-1"],
  deliveryCategory: "FEATURE",
  linearIssues: [issue],
};

describe("delivery planning presentation rules", () => {
  it("reports explicit mandatory completeness errors", () => {
    const invalid = {
      ...deliverable,
      acceptanceCriteria: [],
      dependencies: [],
      riskAndAssumptions: "",
      assignedEmployeeIds: [],
      linearIssues: [],
    };
    expect(validateDeliverable(invalid)).toMatchObject({
      acceptanceCriteria: expect.any(String),
      dependencies: expect.any(String),
      riskAndAssumptions: expect.any(String),
      assignedEmployeeIds: expect.any(String),
      linearIssues: expect.any(String),
    });
  });

  it("requires a deliverable instead of silently creating an empty plan", () => {
    const plan: PlanDraftInput = {
      engagementMonthId: "month-1",
      title: "August plan",
      summary: "Delivery summary",
      businessOutcomes: "Expected outcomes",
      ownerGroup: "Product",
      coordinator: "Coordinator",
      baselineType: "ON_TIME",
      deliverables: [],
    };
    expect(validatePlanDraft(plan)).toMatchObject({
      deliverables: expect.stringContaining("server-approved"),
    });
  });

  it("blocks recipient preview when a required audience is absent", () => {
    const preview: RecipientPreview = {
      arrowFoundry: ["vendor@example.test"],
      relianceStakeholders: ["owner@example.test"],
      procurementCc: [],
      readiness: "BLOCKED",
      blockers: [],
    };
    expect(validateRecipientPreview(preview)).toEqual(["Central Procurement CC is missing."]);
  });

  it("never presents Linear Done as acceptance or certification", () => {
    expect(linearEvidenceNotice(issue)).toContain("execution evidence only");
    expect(linearEvidenceNotice(issue)).toContain("separate authorized decision");
  });

  it("makes approved lineage immutable and calculates link coverage", () => {
    expect(isFrozenPlan("APPROVED")).toBe(true);
    expect(isFrozenPlan("FROZEN")).toBe(true);
    expect(isFrozenPlan("DRAFT")).toBe(false);
    expect(planCoverage([deliverable, { ...deliverable, linearIssues: [] }])).toBe(50);
  });

  it("makes every server plan state explicit and only enables state-valid actions", () => {
    const states = [
      "DRAFT",
      "READY_FOR_REVIEW",
      "PENDING_APPROVAL",
      "APPROVED",
      "FROZEN",
      "SUPERSEDED",
      "CHANGES_REQUESTED",
      "REJECTED",
      "CANCELLED",
    ] as const;
    expect(states.map(planStateNotice).every(Boolean)).toBe(true);
    expect(states.filter(canSubmitPlan)).toEqual(["DRAFT"]);
    expect(states.filter(canDecidePlan)).toEqual(["PENDING_APPROVAL"]);
    expect(states.filter(canRevisePlan)).toEqual(["FROZEN"]);
    expect(states.filter((state) => !isPlanContentReadOnly(state))).toEqual(["DRAFT"]);
    expect(planStateNotice("SUPERSEDED")).toContain("superseded");
    expect(planStateNotice("SUPERSEDED")).toContain("cannot be edited");
  });

  it("renders every commitment, link, connection, snapshot and normalized state", () => {
    expect(
      ([null, "PENDING", "SENT", "RETRY", "DEAD_LETTER"] as const).map(
        (status) => commitmentStatusPresentation(status).label,
      ),
    ).toEqual(["Not queued", "Pending", "Sent", "Retry", "Dead letter"]);
    expect(
      (["ACTIVE", "BROKEN", "INACCESSIBLE"] as const).map(
        (status) => issueLinkStatusPresentation(status).label,
      ),
    ).toEqual(["Active", "Broken", "Inaccessible"]);
    expect(
      (["NOT_CONFIGURED", "CONNECTED", "ACTION_REQUIRED"] as const).map(
        connectionStatusPresentation,
      ),
    ).toHaveLength(3);
    expect(
      (["EXTERNALLY_BLOCKED", "NOT_CONFIGURED", "CONFIGURED"] as const).map(
        providerRegistrationPresentation,
      ),
    ).toHaveLength(3);
    expect(
      (["CAPTURED", "FETCH_FAILED", "UNAVAILABLE"] as const).map(snapshotStatusPresentation),
    ).toEqual(["Captured", "Fetch failed", "Unavailable"]);
    expect(
      (["BACKLOG", "UNSTARTED", "STARTED", "COMPLETED", "CANCELED", "UNKNOWN"] as const).map(
        normalizedStateLabel,
      ),
    ).toEqual(["Backlog", "Unstarted", "Started", "Completed", "Canceled", "Unknown"]);
  });

  it("labels reconstructed plans as imported historical evidence", () => {
    expect(baselineNotice("HISTORICAL_RECONSTRUCTED")).toContain("Imported");
    expect(baselineNotice("HISTORICAL_RECONSTRUCTED")).toContain("confidence");
  });

  it("requires both a revision reason and impact", () => {
    expect(validateRevision({ reason: "", impact: "small" })).toMatchObject({
      reason: expect.any(String),
      impact: expect.any(String),
    });
  });

  it("rejects malformed or non-Linear issue links", () => {
    expect(
      validateLinkIssue({
        deliverableVersionId: "version-1",
        connectionId: "",
        issueUuid: "",
      }),
    ).toMatchObject({
      connectionId: expect.any(String),
      issueUuid: expect.any(String),
    });
  });

  it("bounds optional multi-link rationale to the server DTO limit", () => {
    expect(
      validateLinkIssue({
        deliverableVersionId: "version-1",
        connectionId: "connection-1",
        issueUuid: "issue-1",
        rationale: "x".repeat(2_001),
      }),
    ).toEqual({
      rationale: "Rationale must be 2,000 characters or fewer.",
    });
  });

  it("classifies structured API validation, authorization, conflict and outage payloads", () => {
    const error = (status: number, code: string) =>
      new ApiError("Server detail", {
        status,
        code,
        correlationId: "corr-123",
        details: { code, detail: "Server detail" },
      });
    expect(classifyDeliveryError(error(400, "VALIDATION_FAILED"))).toBe("validation");
    expect(classifyDeliveryError(error(403, "FORBIDDEN"))).toBe("unauthorized");
    expect(classifyDeliveryError(error(409, "STALE_VERSION"))).toBe("conflict");
    expect(classifyDeliveryError(error(503, "UNAVAILABLE"))).toBe("unavailable");
  });

  it("validates the exact create-plan contract and recipient audiences", () => {
    const request: CreatePlanRequest = {
      engagementMonthId: "month-1",
      title: "August plan",
      summary: "Summary",
      businessOutcomes: "Outcome",
      coordinatorSubject: "coordinator",
      baselineType: "ON_TIME",
      quorumMode: "ANY_ONE",
      quorumRequired: 1,
      approverSubjects: ["approver"],
      recipients: {
        arrowFoundry: ["vendor@example.test"],
        relianceStakeholders: ["owner@example.test"],
        procurementCc: [],
      },
      deliverables: [],
    };
    expect(validateCreatePlanRequest(request)).toMatchObject({
      "recipients.0": "Central Procurement CC is missing.",
      deliverables: "Add at least one deliverable.",
    });
  });
});
