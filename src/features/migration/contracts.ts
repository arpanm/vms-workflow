export type MigrationPermission =
  | "MIGRATION_READ"
  | "MIGRATION_UPLOAD"
  | "MIGRATION_VALIDATE"
  | "MIGRATION_APPROVE"
  | "MIGRATION_COMMIT"
  | "MIGRATION_ROLLBACK"
  | "MIGRATION_RETRO";

export type MigrationJobState =
  | "UPLOADED"
  | "SCANNING"
  | "PARSING"
  | "VALIDATING"
  | "READY_TO_COMMIT"
  | "COMMITTING"
  | "COMPLETED"
  | "COMPLETED_WITH_ERRORS"
  | "FAILED"
  | "CANCELLED"
  | "ROLLED_BACK";

export type TemplateDescriptor = {
  code: string;
  filename: string;
  version: string;
  wave: number;
  referenceSampleSha256: string;
  headers: string[];
  dependencies: string[];
};

export type MigrationAccess = {
  permissions: MigrationPermission[];
  approvalRole: "MIGRATION_LEAD" | "GOVERNANCE" | null;
  scopeLabel: string;
  externalAcceptance: "ACTION_REQUIRED" | "ACCEPTED";
  engagementId: string;
  scopes: Array<{ engagementId: string }>;
};

export type MigrationRowIssue = {
  rowNumber: number;
  field: string | null;
  code: string;
  severity: "ERROR" | "WARNING";
  safeMessage: string;
  state: string;
};

export type MigrationApproval = {
  approvalId: string;
  role: "MIGRATION_LEAD" | "GOVERNANCE_REVIEWER";
  actorDisplay: string;
  recordedAt: string;
  reconciliationHash: string;
};

export type Reconciliation = {
  reconciliationId: string;
  version: number;
  sha256: string;
  sourceSha256: string;
  expectedRows: number;
  validRows: number;
  invalidRows: number;
  committedRows: number;
  lowConfidenceRows: number;
  expectedEmployeeDays: number;
  importedEmployeeDays: number;
  approvals: MigrationApproval[];
};

export type MigrationJob = {
  jobId: string;
  templateCode: string;
  templateVersion: number;
  originalFileName: string;
  safeFileName: string;
  sourceSha256: string;
  mode: "DRY_RUN" | "COMMIT" | "REPROCESS_REJECTS" | "SUPERSEDE";
  partialCommit: boolean;
  state: MigrationJobState;
  organizationId: string;
  engagementId: string | null;
  monthId: string | null;
  representedPeriod: string | null;
  totalRows: number;
  validRows: number;
  warningRows: number;
  invalidRows: number;
  committedRows: number;
  progressPercent: number;
  version: number;
  createdAt: string;
  updatedAt: string;
  permissions: MigrationPermission[];
  issues: MigrationRowIssue[];
  reconciliation: Reconciliation | null;
};

export type MigrationPage<T> = {
  items: T[];
  nextCursor: string | null;
  totalCount: number;
};

export type CreateMigrationInput = {
  templateCode: string;
  organizationId: string;
  engagementId?: string;
  monthId?: string;
  sourceType: string;
  confidence: string;
  sourceDescription: string;
  partialCommit: boolean;
  file: File;
};

export type RetroRequestInput = {
  engagementId: string;
  engagementMonthId: string;
  requestType: "CERTIFICATION" | "CONFIRMATION" | "COMMITMENT";
  representedMonth: string;
  reason: string;
  originalActorUnavailable: boolean;
  delegationEvidenceReference?: string;
};

export type MigrationRow = {
  id: string;
  rowNumber: number;
  state: string;
  sourceType: string;
  confidence: string;
  representedAt: string | null;
  recordedAt: string;
  naturalKeyHash: string;
  limitations: string | null;
  findings: Array<{
    severity: string;
    code: string;
    field: string | null;
    message: string;
  }>;
};

export type RetroRequest = {
  id: string;
  engagementMonthId: string;
  requestType: RetroRequestInput["requestType"];
  state: "PENDING" | "APPROVED" | "REJECTED" | "CANCELLED";
  representedMonth: string;
  reason: string;
  requestedBy: string;
  decidedBy: string | null;
  decisionAt: string | null;
  decisionReason: string | null;
  procurementNotificationState: string;
  version: number;
  createdAt: string;
};

export type MonthReadiness = {
  monthId: string;
  engagementId: string;
  state: string;
  version: number;
  completedJobs: number;
  pendingRetroRequests: number;
  blockers: string[];
  ready: boolean;
};
