import type { BaselineType, PlanState } from "./domain";

export type NormalizedExecutionState =
  "BACKLOG" | "UNSTARTED" | "STARTED" | "COMPLETED" | "CANCELED" | "UNKNOWN";

export type CommitmentStatus = "PENDING" | "SENT" | "RETRY" | "DEAD_LETTER";

export type IssueLinkStatus = "ACTIVE" | "BROKEN" | "INACCESSIBLE";
export type LinearConnectionStatus = "NOT_CONFIGURED" | "CONNECTED" | "ACTION_REQUIRED";
export type ProviderRegistrationStatus = "EXTERNALLY_BLOCKED" | "NOT_CONFIGURED" | "CONFIGURED";
export type DeliveryPriority = "P0" | "P1" | "P2" | "P3";
export type DeliveryCategory =
  | "FEATURE"
  | "PLATFORM"
  | "INTEGRATION"
  | "QUALITY"
  | "OPERATIONS"
  | "RESEARCH_POC"
  | "SUPPORT"
  | "OTHER";

export type RecipientSet = {
  arrowFoundry: string[];
  relianceStakeholders: string[];
  procurementCc: string[];
};

export type CriterionInput = {
  statement: string;
  validationMethod: string;
  expectedResult: string;
  mandatory: boolean;
};

export type DependencyInput = {
  type: "INTERNAL" | "LINEAR" | "EXTERNAL";
  dependsOnDeliverableId?: string;
  description: string;
  ownerSubject: string;
  targetResolutionDate: string;
  blocking: boolean;
};

export type AssignmentInput = {
  employeeId: string;
  effectiveFrom: string;
  effectiveTo?: string | null;
  exceptionReason?: string | null;
};

export type DeliverableInput = {
  deliverableCode: string;
  title: string;
  description: string;
  businessObjective: string;
  projectId: string;
  productOwnerSubject: string;
  vendorOwnerSubject: string;
  priority: DeliveryPriority;
  targetCompletionDate: string;
  evidenceExpectations: string;
  dependencyNoneDeclared: boolean;
  riskAndAssumptions: string;
  deliveryCategory: DeliveryCategory;
  linkExceptionReason?: string;
  criteria: CriterionInput[];
  dependencies: DependencyInput[];
  assignments: AssignmentInput[];
};

export type CreatePlanRequest = {
  engagementMonthId: string;
  title: string;
  summary: string;
  businessOutcomes: string;
  coordinatorSubject: string;
  baselineType: BaselineType;
  quorumMode: "ANY_ONE" | "ALL" | "N_OF_M";
  quorumRequired: number;
  approverSubjects: string[];
  recipients: RecipientSet;
  deliverables: DeliverableInput[];
};

export type RecipientView = RecipientSet;

export type CriterionView = CriterionInput & {
  id: string;
  sequence: number;
};
export type DependencyView = {
  id: string;
  type: DependencyInput["type"];
  dependsOnDeliverableId: string | null;
  description: string;
  ownerSubject: string;
  targetResolutionDate: string;
  blocking: boolean;
};
export type AssignmentView = {
  id: string;
  employeeId: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  exceptionReason: string | null;
};

export type DeliverableView = Omit<
  DeliverableInput,
  "criteria" | "dependencies" | "assignments" | "linkExceptionReason"
> & {
  id: string;
  deliverableVersionId: string;
  linkExceptionReason: string | null;
  criteria: CriterionView[];
  dependencies: DependencyView[];
  assignments: AssignmentView[];
  executionProjection: NormalizedExecutionState;
  linearLinks: IssueLinkView[];
};

export type ApprovalView = {
  id: string;
  approverSubject: string;
  actingSubject: string;
  delegationId: string | null;
  decision: "APPROVE" | "REJECT";
  signedChecksum: string;
  comment: string | null;
  decidedAt: string;
};

export type PlanSummaryView = {
  id: string;
  engagementMonthId: string;
  currentVersionId: string;
  version: number;
  state: PlanState;
  title: string;
  baselineType: BaselineType;
  checksum: string | null;
  deliverableCount: number;
  approvedCount: number;
  requiredApprovals: number;
  createdAt: string;
  frozenAt: string | null;
};

export type PlanView = {
  id: string;
  engagementMonthId: string;
  currentVersionId: string;
  version: number;
  editVersion?: number;
  state: PlanState;
  title: string;
  summary: string;
  businessOutcomes: string;
  coordinatorSubject: string;
  baselineType: BaselineType;
  quorumMode?: CreatePlanRequest["quorumMode"];
  quorumRequired?: number;
  approverSubjects?: string[];
  checksum: string | null;
  priorVersionId: string | null;
  revisionReason: string | null;
  revisionImpact: string | null;
  createdBySubject: string;
  createdAt: string;
  submittedAt: string | null;
  frozenAt: string | null;
  completenessBlockers: string[];
  recipients: RecipientView;
  deliverables: DeliverableView[];
  approvals: ApprovalView[];
  baselineId: string | null;
  commitmentStatus: CommitmentStatus | null;
};

export type ApprovalRequest = {
  decision: "APPROVE" | "REJECT";
  comment?: string;
  onBehalfOfSubject?: string;
};

export type RevisionRequest = {
  reason: string;
  impact: string;
};

export type RevisionComparisonView = {
  planId: string;
  priorVersionId: string | null;
  currentVersionId: string;
  priorVersion: number;
  currentVersion: number;
  changedPlanFields: string[];
  addedDeliverableCount: number;
  removedDeliverableCount: number;
  changedDeliverableCount: number;
};

export type CommitmentDeadLetterView = {
  outboxId: string;
  planId: string;
  planVersionId: string;
  planVersion: number;
  messageType: "INITIAL" | "REVISION";
  attemptCount: number;
  lastErrorCode: string | null;
  deadLetteredAt: string;
  createdAt: string;
  replayCount: number;
};

export type CommitmentReplayRequest = {
  reason: string;
};

export type CommitmentReplayView = {
  replayId: string;
  originalOutboxId: string;
  replayOutboxId: string;
  status: "PENDING" | "RETRY" | "SENDING" | "SENT" | "DEAD_LETTER";
  replayNumber: number;
  replay: boolean;
};

export type LinkIssueRequest = {
  deliverableVersionId: string;
  connectionId: string;
  issueUuid: string;
  rationale?: string;
};

export type IssueLinkView = {
  id: string;
  deliverableVersionId: string;
  connectionId: string;
  issueUuid: string;
  identifier: string;
  url: string;
  status: IssueLinkStatus;
  rationale: string | null;
  currentNormalizedState: NormalizedExecutionState | null;
  lastFetchedAt: string | null;
};

export type IssueCurrentView = {
  issueUuid: string;
  identifier: string;
  url: string;
  title: string;
  providerStateId: string | null;
  providerStateName: string | null;
  providerStateType: string | null;
  providerStateCategory: string | null;
  normalizedState: NormalizedExecutionState;
  updatedAt: string | null;
  fetchedAt: string;
  payloadHash: string;
  stale: boolean;
  inaccessible: boolean;
  executionProjection: NormalizedExecutionState;
};

export type IssueSnapshotView = {
  id: string;
  snapshotType: "PLAN_TIME" | "MONTH_END" | "HISTORICAL_RETRIEVAL";
  status: "CAPTURED" | "FETCH_FAILED" | "UNAVAILABLE";
  normalizedState: NormalizedExecutionState | null;
  providerStateId: string | null;
  providerStateName: string | null;
  providerStateType: string | null;
  providerStateCategory: string | null;
  fetchedAt: string | null;
  payloadHash: string | null;
  confidence: "SOURCE_EVENT_HISTORY" | "SOURCE_EXPORT" | "CURRENT_STATE_ONLY" | "UNAVAILABLE";
  failureReason: string | null;
};

export type LinearHealthView = {
  connectionId: string | null;
  status: LinearConnectionStatus;
  providerRegistrationStatus: ProviderRegistrationStatus;
  lastVerifiedDeliveryAt: string | null;
  lastReconciledAt: string | null;
  linkedIssueCount: number;
  staleIssueCount: number;
  queuedCount: number;
  deadLetterCount: number;
  lastError: string | null;
};

export type LinearReconciliationRequest = {
  outcome: "AVAILABLE" | "UNAVAILABLE";
  errorCode?:
    | "PROVIDER_UNAVAILABLE"
    | "AUTHENTICATION_FAILED"
    | "RATE_LIMITED"
    | "SCHEMA_INVALID"
    | "PROVIDER_TIMEOUT";
  reason: string;
};

export type LinearReconciliationView = {
  jobId: string;
  connectionId: string;
  jobStatus: "SUCCEEDED" | "FAILED";
  connectionStatus: LinearConnectionStatus;
  staleIssueCount: number;
  recordedAt: string;
  errorCode: string | null;
  commandChecksum: string;
  correlationId: string;
  causationId: string;
  replay: boolean;
};

export type ConnectionMetadataView = {
  id: string;
  engagementId: string;
  providerOrganizationId: string;
  displayName: string;
  status: LinearConnectionStatus;
  providerRegistrationStatus: ProviderRegistrationStatus;
  credentialReferenceConfigured: boolean;
  webhookReferenceConfigured: boolean;
};
