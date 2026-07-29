export type SubmissionStatus =
  "DRAFT" | "SUBMITTED" | "UNDER_REVIEW" | "CLARIFICATION_REQUIRED" | "WITHDRAWN" | "SUPERSEDED";

export type DeliveryOutcome =
  | "COMPLETED"
  | "PARTIALLY_COMPLETED"
  | "DEFERRED"
  | "NOT_COMPLETED"
  | "CANCELLED_BY_APPROVED_CHANGE";

export type CertificationDecision =
  | "ACCEPTED"
  | "ACCEPTED_WITH_OBSERVATIONS"
  | "PARTIALLY_ACCEPTED"
  | "CLIENT_DEPENDENCY_DEFERRED"
  | "VENDOR_DEPENDENCY_DEFERRED"
  | "REJECTED"
  | "CANCELLED_BY_APPROVED_CHANGE"
  | "MORE_INFORMATION_REQUIRED";

export type CriterionDecision = "MET" | "PARTIALLY_MET" | "NOT_MET" | "NOT_APPLICABLE";
export type MonthlyDecision =
  "CERTIFIED" | "CERTIFIED_WITH_OBSERVATIONS" | "PARTIALLY_CERTIFIED" | "NOT_CERTIFIED";
export type SnapshotStatus = "CAPTURED" | "FETCH_FAILED" | "UNAVAILABLE";
export type FreshnessStatus = "CURRENT" | "STALE" | "UNKNOWN";
export type ScanStatus = "PASSED" | "PENDING" | "QUARANTINED" | "FAILED" | "UNKNOWN";
export type ReadinessStatus = "READY" | "BLOCKED" | "ACTION_REQUIRED" | "STALE";
export type ConfirmationState =
  | "DRAFT"
  | "QUEUED"
  | "SENT"
  | "AWAITING_RESPONSE"
  | "CONFIRMED"
  | "CHANGES_REQUESTED"
  | "REJECTED"
  | "EXPIRED"
  | "CANCELLED"
  | "SUPERSEDED"
  | "CONFLICT_REVIEW";
export type ConfirmationDecision = "CONFIRM" | "REQUEST_CORRECTION" | "REJECT";
export type TransportStatus =
  | "NOT_CONFIGURED"
  | "QUEUED"
  | "SENT"
  | "DELIVERED"
  | "READ"
  | "BOUNCED"
  | "FAILED"
  | "DEAD_LETTER";

export type CertificationPermissions = {
  canEditSubmission: boolean;
  canSubmit: boolean;
  canRespondToClarification: boolean;
  canCertify: boolean;
  canRequestClarification: boolean;
  canGenerateSummary: boolean;
  canRequestConfirmation: boolean;
  canConfirm: boolean;
  canReviewInbound: boolean;
  canReopen: boolean;
};

export type SafeEvidenceReference = {
  id: string;
  displayName: string;
  classification: string;
  scanStatus: ScanStatus;
  source: "ARTIFACT" | "ALLOWLISTED_URL" | "LINEAR_SNAPSHOT" | "OTHER_REFERENCE";
  viewAllowed: boolean;
};

export type CriterionView = {
  id: string;
  sequence: number;
  statement: string;
  expectedResult: string;
  mandatory: boolean;
};

export type VendorCriterionResponse = {
  criterionId: string;
  response: string;
  evidenceReferences: SafeEvidenceReference[];
};

export type SubmissionItemView = {
  deliverableId: string;
  outcome: DeliveryOutcome;
  completionPercentage: number;
  completionDate: string | null;
  summary: string;
  varianceCause: string | null;
  varianceImpact: string | null;
  nextAction: string | null;
  carryForwardProposal: string | null;
  criterionResponses: VendorCriterionResponse[];
  evidenceReferences: SafeEvidenceReference[];
};

export type CertificationCriterionResult = {
  criterionId: string;
  decision: CriterionDecision;
  rationale: string;
  evidenceViewed: boolean;
};

export type CertificationView = {
  id: string;
  version: number;
  decision: CertificationDecision;
  comment: string | null;
  observations: string | null;
  cause: string | null;
  nextAction: string | null;
  acceptedScope: string | null;
  rejectedScope: string | null;
  carryForward: string | null;
  criterionResults: CertificationCriterionResult[];
  decidedByDisplay: string;
  decidedAt: string;
  terminal: boolean;
};

export type DeliverableCertificationView = {
  id: string;
  code: string;
  title: string;
  projectName: string;
  baselineVersionId: string;
  baselineDescription: string;
  businessObjective: string;
  evidenceExpectation: string;
  assignedToCurrentActor: boolean;
  assignmentReason: string | null;
  reviewStartedAt: string | null;
  reviewDueAt: string | null;
  reviewAgeSeconds: number;
  reviewAgingStatus: "NOT_STARTED" | "NEW" | "AGING" | "OVERDUE" | "RESOLVED";
  criteria: CriterionView[];
  vendorSubmission: SubmissionItemView | null;
  certification: CertificationView | null;
};

export type ClarificationView = {
  id: string;
  round: number;
  deliverableId: string;
  questions: string[];
  requestedByDisplay: string;
  requestedAt: string;
  response: string | null;
  respondedAt: string | null;
  status: "OPEN" | "RESPONDED" | "RESOLVED";
};

export type SubmissionView = {
  id: string;
  version: number;
  status: SubmissionStatus;
  summary: string;
  declarationAccepted: boolean;
  completenessBlockers: string[];
  autosavedAt: string | null;
  submittedAt: string | null;
  locked: boolean;
  items: SubmissionItemView[];
};

export type LinearSnapshotView = {
  label: "PLAN_TIME" | "MONTH_END" | "CURRENT";
  status: SnapshotStatus;
  freshness: FreshnessStatus;
  capturedAt: string | null;
  sourceVersionId: string | null;
};

export type CertificationSummaryView = {
  id: string;
  version: number;
  decision: MonthlyDecision;
  checksum: string;
  createdAt: string;
  observations: string | null;
  terminalItemCount: number;
  totalItemCount: number;
  superseded: boolean;
};

export type NotificationView = {
  id: string;
  category: string;
  businessState: string;
  transportStatus: TransportStatus;
  recipientSummary: string;
  createdAt: string;
  lastAttemptAt: string | null;
  errorCategory: string | null;
  correlationId: string | null;
};

export type CertificationInboxItem = {
  monthId: string;
  engagementId: string;
  engagementCode: string;
  engagementName: string;
  monthStartDate: string;
  monthLabel: string;
  lifecycleState: string;
  monthVersion: number;
  submissionStatus: SubmissionStatus | null;
  deliverableCount: number;
  terminalDecisionCount: number;
  assignedReviewCount: number;
  pendingInboundReviewCount: number;
  confirmationState: ConfirmationState | null;
  confirmationDueAt: string | null;
  readinessStatus: "READY_FOR_REQUEST" | "READY_FOR_F05" | "BLOCKED" | "INVALIDATED" | null;
  overdue: boolean;
  nextAction:
    | "REVIEW_CONFIRMATION_EVIDENCE"
    | "CERTIFY_ASSIGNED_DELIVERABLES"
    | "START_DELIVERY_SUBMISSION"
    | "COMPLETE_DELIVERY_SUBMISSION"
    | "CREATE_CONFIRMATION_REQUEST"
    | "RESPOND_TO_CONFIRMATION"
    | "GENERATE_CERTIFICATION_SUMMARY"
    | "NO_ACTION";
  actionPath: string;
};

export type CertificationInboxView = {
  generatedAt: string;
  total: number;
  actionRequired: number;
  overdue: number;
  items: CertificationInboxItem[];
};

export type CertificationQueueSummary = {
  queue: "NOTIFICATION" | "SCHEDULE" | "F05_HANDOFF";
  pending: number;
  claimed: number;
  failed: number;
  deadLetter: number;
  completed: number;
  cancelled: number;
  oldestActionableAt: string | null;
};

export type CertificationOperationItem = {
  id: string;
  monthId: string;
  monthVersion: number;
  monthLabel: string;
  engagementCode: string;
  queue: "NOTIFICATION" | "SCHEDULE" | "F05_HANDOFF";
  workType: string;
  status: string;
  attemptCount: number;
  dueAt: string | null;
  lastAttemptAt: string | null;
  safeErrorCode: string | null;
  correlationId: string | null;
  replayAllowed: boolean;
};

export type CertificationOperationsView = {
  generatedAt: string;
  providerConfiguration: "NOT_CONFIGURED" | "ACTION_REQUIRED" | "CONFIGURED";
  queues: CertificationQueueSummary[];
  actionableItems: CertificationOperationItem[];
};

export type NotificationReplayRequest = {
  notificationId: string;
  expectedMonthVersion: number;
  reason: string;
};

export type NotificationReplayView = {
  id: string;
  notificationId: string;
  monthId: string;
  transportStatus: TransportStatus;
  replayNumber: number;
  totalAttemptCount: number;
  replayedAt: string;
  correlationId: string;
};

export type TimelineEventView = {
  id: string;
  label: string;
  state: string;
  actorDisplay: string;
  recordedAt: string;
  representedAt: string | null;
  correlationId: string | null;
};

export type ConfirmationHistoryItem = {
  id: string;
  version: number;
  state: ConfirmationState;
  dueAt: string;
  createdAt: string;
  supersedesRequestId: string | null;
};

export type ConfirmationPreviewView = {
  sourceVersionIds: string[];
  toRecipients: Array<{ display: string; roleReason: string }>;
  ccRecipients: Array<{ display: string; roleReason: string }>;
  eligibleConfirmers: Array<{ display: string; roleReason: string }>;
  quorumDescription: string;
  defaultDueAt: string;
  ready: boolean;
  blockers: string[];
};

export type InboundReviewView = {
  id: string;
  reviewKind: "INBOUND_MESSAGE" | "MANUAL_EVIDENCE";
  source: "VERIFIED_REPLY" | "AMBIGUOUS_REPLY" | "QUARANTINED" | "MANUAL_EVIDENCE";
  authenticationConfidence: "VERIFIED" | "PARTIAL" | "UNAVAILABLE" | "FAILED";
  reviewStatus: "PENDING" | "APPROVED" | "REJECTED" | "QUARANTINED";
  senderEligibility: "ELIGIBLE" | "INELIGIBLE" | "UNKNOWN";
  version: number;
  assignedToCurrentActor: boolean;
  assignmentReason: string;
  representedAt: string | null;
  recordedAt: string;
  ageSeconds: number;
  agingStatus: "NEW" | "AGING" | "OVERDUE" | "RESOLVED";
  safeSummary: string;
  reason: string | null;
  auditReference: string | null;
};

export type MonthCertificationView = {
  monthId: string;
  engagementId: string;
  monthLabel: string;
  lifecycleState: string;
  version: number;
  stale: boolean;
  locked: boolean;
  lastEvaluatedAt: string;
  baseline: {
    id: string;
    versionId: string;
    checksum: string;
    frozen: boolean;
  };
  permissions: CertificationPermissions;
  evidenceChoices: SafeEvidenceReference[];
  submission: SubmissionView | null;
  deliverables: DeliverableCertificationView[];
  clarifications: ClarificationView[];
  summary: CertificationSummaryView | null;
  linearSnapshots: LinearSnapshotView[];
  confirmationPreview: ConfirmationPreviewView | null;
  confirmationHistory: ConfirmationHistoryItem[];
  notifications: NotificationView[];
  timeline: TimelineEventView[];
  inboundReviews: InboundReviewView[];
};

export type ReadinessBlocker = {
  code: string;
  message: string;
  severity: "BLOCKING" | "WARNING" | "INFORMATION";
  owner: string;
  actionLabel: string;
  actionPath: string | null;
};

export type ReadinessPillar = {
  key: "ROSTER" | "ATTENDANCE" | "PLAN_LINEAR" | "CERTIFICATION" | "CONFIRMATION_HANDOFF";
  label: string;
  status: ReadinessStatus;
  sourceVersionId: string | null;
  freshness: FreshnessStatus;
  checkedAt: string;
  blockers: ReadinessBlocker[];
};

export type ReadinessView = {
  monthId: string;
  version: number;
  inputManifestVersion: string;
  status: ReadinessStatus;
  evaluatedAt: string;
  stale: boolean;
  pillars: ReadinessPillar[];
  blockers: ReadinessBlocker[];
  f05HandoffStatus: "NOT_ELIGIBLE" | "ELIGIBLE" | "INVALIDATED";
};

export type ConfirmationActionView = {
  id: string;
  decision: ConfirmationDecision;
  actorDisplay: string;
  actorRoleReason: string;
  source: "IN_APP" | "SECURE_LINK" | "VERIFIED_REPLY" | "MANUAL_EVIDENCE";
  comment: string | null;
  recordedAt: string;
  representedAt: string | null;
  auditReference: string;
};

export type ConfirmationProjectChoice = {
  id: string | null;
  display: string;
  roleReason: string;
};

export type ConfirmationScopeSource = {
  kind:
    "ATTENDANCE_SNAPSHOT" | "DELIVERY_PLAN_VERSION" | "DELIVERY_BASELINE" | "CERTIFICATION_SUMMARY";
  id: string;
  version: number | null;
  checksum: string | null;
  freshness: string;
  display: string;
};

export type VersionDiffItem = {
  fieldLabel: string;
  previousValue: string | null;
  currentValue: string;
};

export type ConfirmationRequestView = {
  id: string;
  monthId: string;
  engagementLabel: string;
  monthLabel: string;
  version: number;
  state: ConfirmationState;
  dueAt: string;
  createdAt: string;
  locked: boolean;
  stale: boolean;
  eligible: boolean;
  eligibilityMessage: string;
  projectIdRequired: boolean;
  eligibleProjects: ConfirmationProjectChoice[];
  scopeChecksum: string;
  sourceVersionIds: string[];
  scopeSources: ConfirmationScopeSource[];
  recipients: Array<{ display: string; roleReason: string; kind: "TO" | "CC" }>;
  quorumDescription: string;
  transportStatus: TransportStatus;
  providerConfiguration: "NOT_CONFIGURED" | "ACTION_REQUIRED" | "CONFIGURED";
  diff: VersionDiffItem[];
  actions: ConfirmationActionView[];
  notifications: NotificationView[];
  lineage: ConfirmationHistoryItem[];
  permissions: CertificationPermissions;
};

export type SubmissionCriterionInput = {
  criterionId: string;
  response: string;
  evidenceReferenceIds: string[];
};

export type SubmissionItemInput = {
  deliverableId: string;
  outcome: DeliveryOutcome;
  completionPercentage: number;
  completionDate?: string;
  summary: string;
  varianceCause?: string;
  varianceImpact?: string;
  nextAction?: string;
  carryForwardProposal?: string;
  criterionResponses: SubmissionCriterionInput[];
  evidenceReferenceIds: string[];
};

export type SaveSubmissionRequest = {
  expectedMonthVersion: number;
  summary: string;
  declarationAccepted: boolean;
  items: SubmissionItemInput[];
};

export type ClarificationRequest = {
  expectedSubmissionVersion: number;
  deliverableId: string;
  questions?: string[];
  clarificationId?: string;
  response?: string;
};

export type CertificationRequest = {
  expectedSubmissionVersion: number;
  deliverableId: string;
  decision: CertificationDecision;
  comment?: string;
  observations?: string;
  cause?: string;
  nextAction?: string;
  acceptedScope?: string;
  rejectedScope?: string;
  carryForward?: string;
  overrideRationale?: string;
  criterionResults: Array<{
    criterionId: string;
    decision: CriterionDecision;
    rationale: string;
    evidenceViewed: boolean;
  }>;
};

export type SummaryRequest = {
  expectedMonthVersion: number;
  decision: MonthlyDecision;
  observations?: string;
};

export type ConfirmationRequestInput = {
  expectedMonthVersion: number;
  dueAt: string;
};

export type ConfirmationActionRequest = {
  expectedRequestVersion: number;
  decision: ConfirmationDecision;
  comment?: string;
  projectId?: string;
};

export type InboundMessageReviewRequest = {
  expectedReviewVersion: number;
  decision: "ACCEPT_INTERPRETATION" | "REJECT_INTERPRETATION" | "QUARANTINE";
  reasoning: string;
};

export type ManualEvidenceReviewRequest = {
  expectedReviewVersion: number;
  decision: "APPROVE" | "REJECT";
  reasoning: string;
};

export type ReopenRequestInput = {
  expectedMonthVersion: number;
  category: "ATTENDANCE_CORRECTION" | "CERTIFICATION_CORRECTION" | "PLAN_CORRECTION" | "OTHER";
  reason: string;
  impactedRecordIds: string[];
  packageInvoiceImpact: string;
  riskStatement: string;
};
