import { ApiError } from "@/lib/api-client";

import type {
  Deliverable,
  LinearIssue,
  PlanDraftInput,
  PlanState,
  RecipientPreview,
  RevisionInput,
} from "./domain";
import type {
  CommitmentStatus,
  CreatePlanRequest,
  DeliverableInput,
  IssueLinkStatus,
  LinearConnectionStatus,
  LinkIssueRequest,
  NormalizedExecutionState,
  ProviderRegistrationStatus,
} from "./contracts";

export type DeliveryErrorKind =
  | "unauthenticated"
  | "unauthorized"
  | "conflict"
  | "validation"
  | "not-found"
  | "unavailable"
  | "unexpected";

export function classifyDeliveryError(error: unknown): DeliveryErrorKind {
  if (!(error instanceof ApiError)) return "unexpected";
  if (error.status === 0 || error.status >= 500) return "unavailable";
  if (error.status === 401) return "unauthenticated";
  if (error.status === 403) return "unauthorized";
  if (error.status === 404) return "not-found";
  if (error.status === 409 || error.status === 412) return "conflict";
  if (error.status === 400 || error.status === 422) return "validation";
  return "unexpected";
}

export function isFrozenPlan(state: PlanState) {
  return ["APPROVED", "FROZEN", "SUPERSEDED"].includes(state);
}

export function isPlanContentReadOnly(state: PlanState) {
  return state !== "DRAFT";
}

export function canSubmitPlan(state: PlanState) {
  return state === "DRAFT";
}

export function canDecidePlan(state: PlanState) {
  return state === "PENDING_APPROVAL";
}

export function canRevisePlan(state: PlanState) {
  return state === "FROZEN";
}

export function planStateNotice(state: PlanState): string {
  switch (state) {
    case "DRAFT":
      return "This current draft can be linked and submitted after all completeness blockers are resolved.";
    case "READY_FOR_REVIEW":
      return "This review version is read-only. Refresh for the server-authoritative approval state.";
    case "PENDING_APPROVAL":
      return "This exact checksum is read-only while eligible approvers decide.";
    case "APPROVED":
      return "Approval has been recorded. This version is read-only while the baseline is finalized.";
    case "FROZEN":
      return "This frozen baseline is immutable. Changes require a reasoned revision and new approval.";
    case "SUPERSEDED":
      return "This immutable version has been superseded. Review the current revision; this historical version cannot be edited or revised again.";
    case "CHANGES_REQUESTED":
      return "Changes were requested. This returned version is read-only; use the server-authorized revision workflow.";
    case "REJECTED":
      return "This rejected version is read-only and cannot receive more approval decisions.";
    case "CANCELLED":
      return "This cancelled version is read-only and cannot be submitted, linked or approved.";
  }
}

export function baselineNotice(baselineType: CreatePlanRequest["baselineType"]) {
  switch (baselineType) {
    case "ON_TIME":
      return "On-time baseline";
    case "LATE_APPROVED":
      return "Late baseline approved through the governed workflow";
    case "HISTORICAL_RECONSTRUCTED":
      return "Imported historical reconstruction; provenance and confidence must be reviewed separately";
  }
}

export function commitmentStatusPresentation(status: CommitmentStatus | null) {
  switch (status) {
    case null:
      return {
        label: "Not queued",
        detail: "No commitment message exists for this unfrozen version.",
      };
    case "PENDING":
      return {
        label: "Pending",
        detail: "The frozen commitment is queued for delivery.",
      };
    case "SENT":
      return {
        label: "Sent",
        detail: "Delivery is recorded; it is not approval, acceptance or confirmation.",
      };
    case "RETRY":
      return {
        label: "Retry",
        detail: "A delivery attempt failed and remains eligible for a controlled retry.",
      };
    case "DEAD_LETTER":
      return {
        label: "Dead letter",
        detail: "Delivery failed permanently and requires authorized operator review.",
      };
  }
}

export function issueLinkStatusPresentation(status: IssueLinkStatus) {
  switch (status) {
    case "ACTIVE":
      return {
        label: "Active",
        detail: "The recorded provider issue is linked.",
      };
    case "BROKEN":
      return {
        label: "Broken",
        detail: "The recorded link is broken; last-known evidence is retained.",
      };
    case "INACCESSIBLE":
      return {
        label: "Inaccessible",
        detail: "The provider issue is no longer accessible; last-known evidence is retained.",
      };
  }
}

export function connectionStatusPresentation(status: LinearConnectionStatus) {
  switch (status) {
    case "NOT_CONFIGURED":
      return "No Linear connection is configured for this engagement.";
    case "CONNECTED":
      return "The recorded connection is available; live provider capability is still limited to server-configured operations.";
    case "ACTION_REQUIRED":
      return "The connection requires operator action. Last-known evidence may be stale.";
  }
}

export function providerRegistrationPresentation(status: ProviderRegistrationStatus) {
  switch (status) {
    case "EXTERNALLY_BLOCKED":
      return "Provider registration is externally blocked";
    case "NOT_CONFIGURED":
      return "Provider registration is not configured";
    case "CONFIGURED":
      return "Provider registration is configured";
  }
}

export function normalizedStateLabel(state: NormalizedExecutionState) {
  switch (state) {
    case "BACKLOG":
      return "Backlog";
    case "UNSTARTED":
      return "Unstarted";
    case "STARTED":
      return "Started";
    case "COMPLETED":
      return "Completed";
    case "CANCELED":
      return "Canceled";
    case "UNKNOWN":
      return "Unknown";
  }
}

export function snapshotStatusPresentation(status: "CAPTURED" | "FETCH_FAILED" | "UNAVAILABLE") {
  switch (status) {
    case "CAPTURED":
      return "Captured";
    case "FETCH_FAILED":
      return "Fetch failed";
    case "UNAVAILABLE":
      return "Unavailable";
  }
}

export type FieldErrors = Record<string, string>;

export function validatePlanDraft(value: PlanDraftInput): FieldErrors {
  const errors: FieldErrors = {};
  if (!value.engagementMonthId) errors.engagementMonthId = "Select a month.";
  if (!value.title.trim()) errors.title = "Plan title is required.";
  if (!value.summary.trim()) errors.summary = "Plan summary is required.";
  if (!value.businessOutcomes.trim()) {
    errors.businessOutcomes = "Business outcomes are required.";
  }
  if (!value.ownerGroup.trim()) errors.ownerGroup = "Owner group is required.";
  if (!value.coordinator.trim()) {
    errors.coordinator = "Coordinator is required.";
  }
  if (value.deliverables.length === 0) {
    errors.deliverables =
      "Add at least one deliverable. A no-deliverables exception requires a separate server-approved workflow.";
  }
  value.deliverables.forEach((deliverable, index) => {
    Object.entries(validateDeliverable(deliverable)).forEach(([field, error]) => {
      errors[`deliverables.${index}.${field}`] = error;
    });
  });
  return errors;
}

export function validateCreatePlanRequest(value: CreatePlanRequest): FieldErrors {
  const errors: FieldErrors = {};
  if (!value.engagementMonthId) errors.engagementMonthId = "Select a month.";
  if (!value.title.trim()) errors.title = "Plan title is required.";
  if (!value.summary.trim()) errors.summary = "Plan summary is required.";
  if (!value.businessOutcomes.trim()) {
    errors.businessOutcomes = "Business outcomes are required.";
  }
  if (!value.coordinatorSubject.trim()) {
    errors.coordinatorSubject = "Coordinator subject is required.";
  }
  if (value.approverSubjects.length === 0) {
    errors.approverSubjects = "Add at least one approver subject.";
  }
  if (value.quorumMode === "N_OF_M") {
    if (value.quorumRequired < 1 || value.quorumRequired > value.approverSubjects.length) {
      errors.quorumRequired = "Required approvals must be between 1 and the approver count.";
    }
  }
  validateRecipientPreview({
    ...value.recipients,
    readiness: "READY",
    blockers: [],
  }).forEach((message, index) => {
    errors[`recipients.${index}`] = message;
  });
  if (value.deliverables.length === 0) {
    errors.deliverables = "Add at least one deliverable.";
  }
  value.deliverables.forEach((deliverable, index) => {
    Object.entries(validateDeliverableInput(deliverable)).forEach(([field, error]) => {
      errors[`deliverables.${index}.${field}`] = error;
    });
  });
  return errors;
}

export function validateDeliverableInput(value: DeliverableInput): FieldErrors {
  const errors: FieldErrors = {};
  const required: Array<[keyof DeliverableInput, string]> = [
    ["deliverableCode", "Deliverable code is required."],
    ["title", "Deliverable title is required."],
    ["description", "Description is required."],
    ["businessObjective", "Business objective is required."],
    ["projectId", "Project ID is required."],
    ["productOwnerSubject", "Reliance product owner is required."],
    ["vendorOwnerSubject", "ArrowFoundry owner is required."],
    ["priority", "Priority is required."],
    ["targetCompletionDate", "Target completion date is required."],
    ["riskAndAssumptions", "State risks/assumptions or explicitly enter None."],
    ["deliveryCategory", "Delivery category is required."],
  ];
  required.forEach(([field, message]) => {
    if (!String(value[field] ?? "").trim()) errors[field] = message;
  });
  if (!/^[A-Z][A-Z0-9_-]{1,63}$/.test(value.deliverableCode.trim())) {
    errors.deliverableCode = "Use 2–64 uppercase letters, numbers, underscores or hyphens.";
  }
  if (value.criteria.length === 0) {
    errors.criteria = "Add at least one independently testable criterion.";
  } else if (
    value.criteria.some(
      (criterion) =>
        !criterion.statement.trim() ||
        !criterion.validationMethod.trim() ||
        !criterion.expectedResult.trim(),
    )
  ) {
    errors.criteria = "Every criterion needs a statement, validation method and expected result.";
  }
  if (!value.evidenceExpectations.trim()) {
    errors.evidenceExpectations = "Add at least one evidence expectation.";
  }
  if (value.assignments.length === 0) {
    errors.assignments = "Assign at least one contributor.";
  }
  if (!value.dependencyNoneDeclared && value.dependencies.length === 0) {
    errors.dependencies = "Add a dependency or explicitly declare that there are none.";
  } else if (
    !value.dependencyNoneDeclared &&
    value.dependencies.some(
      (dependency) =>
        !dependency.type ||
        !dependency.description.trim() ||
        !dependency.ownerSubject.trim() ||
        !dependency.targetResolutionDate,
    )
  ) {
    errors.dependencies =
      "Every dependency needs a type, description, owner and target resolution date.";
  }
  if (value.assignments.some((assignment) => !assignment.employeeId || !assignment.effectiveFrom)) {
    errors.assignments = "Every assignment needs an employee and effective-from date.";
  }
  return errors;
}

export function validateDeliverable(value: Deliverable): FieldErrors {
  const errors: FieldErrors = {};
  const required: Array<[keyof Deliverable, string]> = [
    ["deliverableCode", "Deliverable code is required."],
    ["title", "Title is required."],
    ["description", "Description is required."],
    ["businessObjective", "Business objective is required."],
    ["projectId", "Project is required."],
    ["productOwner", "Reliance product owner is required."],
    ["vendorOwner", "ArrowFoundry owner is required."],
    ["priority", "Priority is required."],
    ["targetCompletionDate", "Target completion date is required."],
    ["riskAndAssumptions", "State risks/assumptions or explicitly enter None."],
    ["deliveryCategory", "Delivery category is required."],
  ];
  required.forEach(([field, message]) => {
    if (!String(value[field] ?? "").trim()) errors[field] = message;
  });
  if (value.acceptanceCriteria.length === 0) {
    errors.acceptanceCriteria = "Add at least one independently testable acceptance criterion.";
  } else if (
    value.acceptanceCriteria.some(
      (criterion) =>
        !criterion.statement.trim() ||
        !criterion.validationMethod.trim() ||
        !criterion.expectedResult.trim(),
    )
  ) {
    errors.acceptanceCriteria =
      "Every criterion needs a statement, validation method and expected result.";
  }
  if (value.evidenceExpectations.length === 0) {
    errors.evidenceExpectations = "Select at least one evidence expectation.";
  }
  if (value.assignedEmployeeIds.length === 0) {
    errors.assignedEmployeeIds = "Assign at least one contributor.";
  }
  if (value.dependencies.length === 0) {
    errors.dependencies = "State dependencies or add an explicit non-blocking None declaration.";
  }
  if (value.linearIssues.length === 0) {
    errors.linearIssues = "Link at least one issue or obtain an authorized link exception.";
  } else if (
    value.linearIssues.some((issue) =>
      ["INACCESSIBLE", "DELETED", "ERROR"].includes(issue.accessStatus),
    )
  ) {
    errors.linearIssues =
      "Resolve inaccessible or invalid issues, or obtain an authorized exception.";
  }
  return errors;
}

export function validateRecipientPreview(preview: RecipientPreview) {
  const errors: string[] = [];
  if (preview.arrowFoundry.length === 0) {
    errors.push("ArrowFoundry recipient group is missing.");
  }
  if (preview.relianceStakeholders.length === 0) {
    errors.push("Reliance product stakeholder group is missing.");
  }
  if (preview.procurementCc.length === 0) {
    errors.push("Central Procurement CC is missing.");
  }
  return errors;
}

export function validateRevision(value: RevisionInput): FieldErrors {
  const errors: FieldErrors = {};
  if (value.reason.trim().length < 5) {
    errors.reason = "Revision reason must be at least 5 characters.";
  }
  if (value.impact.trim().length < 10) {
    errors.impact = "Describe the revision impact in at least 10 characters.";
  }
  return errors;
}

export function validateLinkIssue(value: LinkIssueRequest): FieldErrors {
  const errors: FieldErrors = {};
  if (!value.connectionId.trim()) {
    errors.connectionId = "Connection ID is required.";
  }
  if (!value.issueUuid.trim()) errors.issueUuid = "Issue UUID is required.";
  if ((value.rationale?.length ?? 0) > 2_000) {
    errors.rationale = "Rationale must be 2,000 characters or fewer.";
  }
  return errors;
}

export function linearEvidenceNotice(issue: LinearIssue) {
  const completed = issue.currentState?.normalized === "COMPLETED";
  return completed
    ? "Linear Done is execution evidence only. Acceptance and certification require a separate authorized decision."
    : "Linear state is execution evidence and does not determine acceptance or certification.";
}

export function planCoverage(deliverables: Deliverable[]) {
  if (deliverables.length === 0) return 0;
  return Math.round(
    (deliverables.filter((item) => item.linearIssues.length > 0).length / deliverables.length) *
      100,
  );
}
