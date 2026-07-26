export type PlanState =
  | "DRAFT"
  | "READY_FOR_REVIEW"
  | "PENDING_APPROVAL"
  | "APPROVED"
  | "FROZEN"
  | "SUPERSEDED"
  | "CHANGES_REQUESTED"
  | "REJECTED"
  | "CANCELLED";

export type BaselineType = "ON_TIME" | "LATE_APPROVED" | "HISTORICAL_RECONSTRUCTED";

export type RecipientPreview = {
  arrowFoundry: string[];
  relianceStakeholders: string[];
  procurementCc: string[];
  readiness: "READY" | "BLOCKED" | "NOT_CONFIGURED";
  blockers: string[];
};

export type PlanPermissions = {
  canEdit: boolean;
  canSubmit: boolean;
  canApprove: boolean;
  canRequestRevision: boolean;
};

export type PlanSummary = {
  id: string;
  engagementMonthId: string;
  version: number;
  state: PlanState;
  title: string;
  ownerGroup: string;
  coordinator: string;
  baselineType: BaselineType;
  deliverableCount: number;
  linkedDeliverableCount: number;
  checksum: string | null;
  emailStatus: string;
  completenessErrors: string[];
  priorVersionId: string | null;
  frozenAt: string | null;
  permissions: PlanPermissions;
};

export type AcceptanceCriterion = {
  id: string | null;
  statement: string;
  validationMethod: string;
  expectedResult: string;
  mandatory: boolean;
};

export type DeliverableDependency = {
  id: string | null;
  type: "INTERNAL" | "LINEAR" | "EXTERNAL";
  reference: string;
  owner: string;
  targetResolutionDate: string;
  blocking: boolean;
};

export type LinearState = {
  originalName: string;
  originalType: string;
  normalized: "BACKLOG" | "UNSTARTED" | "STARTED" | "COMPLETED" | "CANCELED" | "UNKNOWN";
};

export type LinearSnapshot = {
  kind: "PLAN_TIME" | "MONTH_END" | "HISTORICAL_RETRIEVAL";
  state: LinearState | null;
  fetchedAt: string | null;
  confidence: "SOURCE_EVENT_HISTORY" | "SOURCE_EXPORT" | "CURRENT_STATE_ONLY" | "UNAVAILABLE";
  fetchStatus: "CAPTURED" | "FETCH_FAILED" | "UNAVAILABLE";
  failureReason: string | null;
};

export type LinearIssue = {
  id: string;
  issueUuid: string;
  identifier: string;
  url: string;
  title: string;
  currentState: LinearState | null;
  planSnapshot: LinearSnapshot | null;
  monthEndSnapshot: LinearSnapshot | null;
  assigneeName: string | null;
  priority: string | null;
  fetchedAt: string | null;
  freshness: "FRESH" | "STALE" | "UNKNOWN";
  linkStatus: "ACTIVE" | "BROKEN" | "INACCESSIBLE";
  accessStatus: "AVAILABLE" | "INACCESSIBLE" | "BROKEN" | "ERROR";
  errorCode: string | null;
};

export type Deliverable = {
  id: string;
  deliverableCode: string;
  title: string;
  description: string;
  businessObjective: string;
  projectId: string;
  productOwner: string;
  vendorOwner: string;
  priority: string;
  targetCompletionDate: string;
  acceptanceCriteria: AcceptanceCriterion[];
  evidenceExpectations: string[];
  dependencies: DeliverableDependency[];
  riskAndAssumptions: string;
  assignedEmployeeIds: string[];
  deliveryCategory: string;
  linearIssues: LinearIssue[];
};

export type PlanDetail = PlanSummary & {
  summary: string;
  businessOutcomes: string;
  deliverables: Deliverable[];
  recipientPreview: RecipientPreview;
  revisionReason: string | null;
  revisionImpact: string | null;
  approvalStatus: string;
};

export type PlanDraftInput = {
  engagementMonthId: string;
  title: string;
  summary: string;
  businessOutcomes: string;
  ownerGroup: string;
  coordinator: string;
  baselineType: BaselineType;
  deliverables: Deliverable[];
};

export type RevisionInput = {
  reason: string;
  impact: string;
};

export type LinearIntegrationHealth = {
  status: "HEALTHY" | "DEGRADED" | "ACTION_REQUIRED" | "NOT_CONFIGURED";
  liveProviderEnabled: boolean;
  lastVerifiedDeliveryAt: string | null;
  reconciliationLagMinutes: number | null;
  linkedIssueCount: number;
  staleIssueCount: number;
  inaccessibleIssueCount: number;
  deadLetterCount: number;
  providerMessage: string;
  permissions: {
    canRefresh: boolean;
    canReplayDeadLetters: boolean;
  };
};
