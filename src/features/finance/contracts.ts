export type FinancePermission =
  | "EVIDENCE_PACKAGE_VIEW"
  | "EVIDENCE_PACKAGE_GENERATE"
  | "EVIDENCE_PACKAGE_DOWNLOAD"
  | "EVIDENCE_PACKAGE_ACCESS_AUDIT"
  | "INVOICE_VIEW"
  | "INVOICE_CREATE"
  | "INVOICE_UPLOAD"
  | "INVOICE_REPLACE"
  | "INVOICE_SUBMIT"
  | "PROCUREMENT_REVIEW"
  | "PROCUREMENT_QUERY"
  | "PROCUREMENT_EXCEPTION"
  | "PAYMENT_VIEW"
  | "PAYMENT_UPDATE"
  | "REPORT_VIEW"
  | "REPORT_EXPORT";

export type FreshnessStatus = "CURRENT" | "STALE" | "UNKNOWN";
export type TemporalMode = "LIVE" | "SNAPSHOT" | "RECONSTRUCTED";
export type ScanStatus =
  | "PENDING"
  | "PASSED"
  | "UNKNOWN"
  | "FAILED"
  | "QUARANTINED"
  | "DISPOSED";
export type ConfigurationStatus = "CONFIGURED" | "NOT_CONFIGURED" | "ACTION_REQUIRED";
export type InvoiceState =
  | "DRAFT"
  | "UPLOADED"
  | "EVIDENCE_PENDING"
  | "READY_FOR_VENDOR_SUBMISSION"
  | "SUBMITTED_TO_PROCUREMENT"
  | "PROCUREMENT_REVIEW"
  | "APPROVED_FOR_PROCESSING"
  | "CHANGES_REQUESTED"
  | "ON_HOLD"
  | "REJECTED"
  | "PAYMENT_INITIATED"
  | "PAID"
  | "CLOSED"
  | "SUPERSEDED"
  | "CANCELLED"
  | "EXCEPTION_ACCEPTED";
export type InvoiceDocumentKind = "PRIMARY" | "CORRECTION" | "CREDIT_NOTE" | "DEBIT_NOTE";
export type ReadinessRuleStatus =
  | "PASS"
  | "PASS_WITH_DISCLOSED_NON_BLOCKING_EXCEPTION"
  | "BLOCKED_MISSING_EVIDENCE"
  | "BLOCKED_INVALID_VERSION"
  | "BLOCKED_CONFIRMATION_PENDING"
  | "BLOCKED_REOPEN_OR_CORRECTION"
  | "EXCEPTION_ACCEPTED_BY_PROCUREMENT";
export type PackageState =
  | "QUEUED"
  | "GENERATING"
  | "AVAILABLE"
  | "FAILED"
  | "INTEGRITY_FAILED"
  | "SUPERSEDED";
export type ProcurementDecision =
  | "APPROVED_FOR_PROCESSING"
  | "CHANGES_REQUESTED"
  | "ON_HOLD"
  | "REJECTED"
  | "EXCEPTION_ACCEPTED";
export type PaymentStatus =
  | "NOT_SUBMITTED"
  | "SUBMITTED_TO_AP"
  | "VALIDATION_IN_PROGRESS"
  | "PAYMENT_SCHEDULED"
  | "PAYMENT_INITIATED"
  | "PAID"
  | "PAYMENT_FAILED"
  | "ON_HOLD";
export type MatrixState =
  | "COMPLETE"
  | "WARNING"
  | "BLOCKING"
  | "EXCEPTION_ACCEPTED"
  | "STALE"
  | "NOT_APPLICABLE";
export type JobStatus =
  | "QUEUED"
  | "RUNNING"
  | "RETRY_SCHEDULED"
  | "READY"
  | "FAILED"
  | "DEAD_LETTER"
  | "EXPIRED";

export type Page<T> = {
  items: T[];
  nextCursor: string | null;
  totalCount: number | null;
};

export type SourceReference = {
  sourceType: string;
  sourceId: string;
  version: string;
  checksum: string | null;
  provenance: string;
  freshness: FreshnessStatus;
  temporalMode: TemporalMode;
  representedAt: string | null;
  recordedAt: string;
  superseded: boolean;
};

export type FinanceAccessView = {
  permissions: FinancePermission[];
  organizationLabel: string;
  scopeLabel: string;
  storage: ConfigurationStatus;
  scanner: ConfigurationStatus;
  renderer: ConfigurationStatus;
  erp: ConfigurationStatus;
};

export type FinanceMonthSummary = {
  monthId: string;
  version: number;
  monthLabel: string;
  engagementLabel: string;
  vendorLabel: string;
  readiness: MatrixState;
  invoiceCount: number;
  currentPackageVersion: number | null;
  refreshedAt: string;
  freshness: FreshnessStatus;
  permissions: FinancePermission[];
};

export type InvoiceDocumentMetadata = {
  documentId: string;
  fileName: string;
  mimeType: string;
  sizeBytes: number;
  sha256: string;
  objectVersion: string;
  scanStatus: ScanStatus;
  classification: string;
  retentionPolicy: string;
  uploadedAt: string;
  superseded: boolean;
};

export type InvoiceRepresentedMetadata = {
  invoiceNumber: string;
  invoiceDate: string;
  billingPeriodStart: string;
  billingPeriodEnd: string;
  currency: string;
  taxableValue: string | null;
  taxValue: string | null;
  totalValue: string | null;
  purchaseOrderReference: string;
  workOrderReference: string | null;
};

export type InvoiceVersionSummary = {
  versionId: string;
  version: number;
  kind: InvoiceDocumentKind;
  state: InvoiceState;
  createdAt: string;
  createdByDisplay: string;
  reason: string | null;
  supersedesVersionId: string | null;
  document: InvoiceDocumentMetadata | null;
};

export type InvoiceSummary = {
  invoiceId: string;
  monthId: string;
  monthLabel: string;
  engagementLabel: string;
  vendorLabel: string;
  invoiceNumber: string;
  state: InvoiceState;
  scanStatus: ScanStatus | null;
  version: number;
  updatedAt: string;
  freshness: FreshnessStatus;
  permissions: FinancePermission[];
};

export type ReadinessRule = {
  ruleId: string;
  pillar: string;
  label: string;
  mandatory: boolean;
  status: ReadinessRuleStatus;
  severity: "INFO" | "WARNING" | "BLOCKING";
  ownerDisplay: string;
  remediationLabel: string | null;
  remediationPath: string | null;
  source: SourceReference | null;
  exceptionId: string | null;
  exceptionExpiresAt: string | null;
};

export type ReadinessRun = {
  runId: string;
  version: number;
  inputHash: string;
  policyVersion: string;
  evaluatedAt: string;
  eligibleForSubmission: boolean;
  stale: boolean;
  rules: ReadinessRule[];
};

export type PackageArtifact = {
  artifactId: string;
  label: string;
  format: "PDF" | "CSV" | "XLSX" | "JSON" | "ZIP";
  sha256: string;
  sizeBytes: number;
  scanStatus: ScanStatus;
  classification: string;
  downloadAllowed: boolean;
};

export type PackageSummary = {
  packageId: string;
  monthId: string;
  version: number;
  state: PackageState;
  progressPercent: number;
  canonicalInputHash: string;
  policyVersion: string;
  templateVersion: string;
  generatedAt: string | null;
  supersedesPackageId: string | null;
  current: boolean;
  permissions: FinancePermission[];
};

export type PackageManifestItem = {
  itemId: string;
  logicalType: string;
  safeName: string;
  source: SourceReference;
  mimeType: string;
  sizeBytes: number;
  sha256: string;
  objectVersion: string;
  classification: string;
  retentionPolicy: string;
};

export type PackageView = PackageSummary & {
  engagementLabel: string;
  monthLabel: string;
  provenanceDisclosure: string | null;
  integrityVerified: boolean;
  sources: SourceReference[];
  manifestItems: PackageManifestItem[];
  artifacts: PackageArtifact[];
};

export type PackageDiff = {
  fromPackageId: string;
  toPackageId: string;
  fromVersion: number;
  toVersion: number;
  added: Array<{ logicalType: string; sourceId: string; version: string }>;
  changed: Array<{
    logicalType: string;
    sourceId: string;
    fromVersion: string;
    toVersion: string;
  }>;
  removed: Array<{ logicalType: string; sourceId: string; version: string }>;
};

export type AccessEvent = {
  accessId: string;
  action: "VIEWED" | "DOWNLOADED" | "SHARED" | "REVOKED" | "DENIED";
  actorDisplay: string;
  authorityDisplay: string;
  recordedAt: string;
  expiresAt: string | null;
  revokedAt: string | null;
  correlationId: string | null;
};

export type PackageShare = {
  shareId: string;
  packageId: string;
  recipientSubject: string;
  accessScope: "VIEW" | "DOWNLOAD";
  expiresAt: string;
  revoked: boolean;
  revokedAt: string | null;
  createdByDisplay: string;
  createdAt: string;
  correlationId: string | null;
};

export type CreatePackageShareInput = {
  recipientSubject: string;
  accessScope: PackageShare["accessScope"];
  expiresAt: string;
  reason: string;
};

export type RevokePackageShareInput = {
  shareId: string;
  reason: string;
};

export type ProcurementReview = {
  reviewId: string;
  version: number;
  decision: ProcurementDecision;
  category: string | null;
  comment: string | null;
  actorDisplay: string;
  authorityDisplay: string;
  invoiceVersion: number;
  packageVersion: number;
  readinessRunId: string;
  recordedAt: string;
};

export type ProcurementQuery = {
  queryId: string;
  version: number;
  status: "OPEN" | "RESPONDED" | "CLOSED";
  category: string;
  summary: string;
  ownerDisplay: string;
  dueAt: string;
  createdAt: string;
  sourceCorrectionPath: string | null;
};

export type ProcurementException = {
  exceptionId: string;
  ruleId: string;
  status: "PENDING_SECOND_APPROVAL" | "ACCEPTED" | "EXPIRED";
  rationale: string;
  authorityDisplay: string;
  secondApproverRequired: boolean;
  requestedByDisplay: string;
  secondApproverDisplay: string | null;
  validUntil: string;
  invoiceVersion: number;
  readinessRunId: string;
  packageId: string;
  packageVersion: number;
  policyVersionId: string;
  policyVersion: number;
  createdAt: string;
  secondApprovedAt: string | null;
  expiredAt: string | null;
};

export type PaymentEvent = {
  paymentEventId: string;
  version: number;
  status: PaymentStatus;
  source: "MANUAL" | "AP" | "ERP";
  provenance: string;
  comment: string | null;
  externalReference: string | null;
  statusAt: string;
  expectedPaymentDate: string | null;
  actualPaymentDate: string | null;
  recordedAt: string;
  recordedByDisplay: string;
};

export type InvoiceView = InvoiceSummary & {
  etag: string;
  readOnly: boolean;
  representedMetadata: InvoiceRepresentedMetadata;
  currentDocument: InvoiceDocumentMetadata | null;
  versions: InvoiceVersionSummary[];
  readiness: ReadinessRun | null;
  linkedPackage: PackageSummary | null;
  reviews: ProcurementReview[];
  queries: ProcurementQuery[];
  exceptions: ProcurementException[];
  paymentTimeline: PaymentEvent[];
};

export type FinanceMonthWorkspace = {
  month: FinanceMonthSummary;
  permissions: FinancePermission[];
  sourceHandoff: {
    contractVersion: string;
    confirmationDisposition: "CONFIRMED" | "EXCEPTION_ACCEPTED" | "PENDING" | "INVALID";
    source: SourceReference;
  } | null;
  invoices: InvoiceSummary[];
  packages: PackageSummary[];
  currentReadinessRunId: string | null;
  blockers: string[];
};

export type MatrixCell = {
  key:
    | "ROSTER"
    | "ATTENDANCE"
    | "PLAN"
    | "LINEAR"
    | "CERTIFICATION"
    | "CONFIRMATION"
    | "PACKAGE"
    | "INVOICE"
    | "PAYMENT";
  label: string;
  state: MatrixState;
  ownerDisplay: string | null;
  version: string | null;
  freshness: FreshnessStatus;
  temporalMode: TemporalMode;
  sourceLabel: string;
  actionPath: string | null;
};

export type ControlTowerRow = {
  monthId: string;
  monthLabel: string;
  engagementLabel: string;
  invoiceId: string | null;
  invoiceNumber: string | null;
  queue: string;
  ageDays: number | null;
  cells: MatrixCell[];
};

export type ControlTowerView = {
  permissions: FinancePermission[];
  refreshedAt: string;
  freshness: FreshnessStatus;
  rows: Page<ControlTowerRow>;
};

export type DashboardMetric = {
  metricId: string;
  label: string;
  displayValue: string;
  unavailable: boolean;
  definitionVersion: string;
  policyVersion: string;
  sourceLabel: string;
  freshness: FreshnessStatus;
  temporalMode: TemporalMode;
  refreshedAt: string;
};

export type FinanceDashboard = {
  personaLabel: string;
  refreshedAt: string;
  freshness: FreshnessStatus;
  metrics: DashboardMetric[];
  queues: Array<{ key: string; label: string; count: number; actionPath: string | null }>;
  permissions: FinancePermission[];
};

export type ReportDefinition = {
  reportId: string;
  name: string;
  version: string;
  description: string;
  availableFormats: Array<"CSV" | "XLSX" | "PDF" | "JSON">;
  snapshotMode: "CURRENT" | "SNAPSHOT" | "SELECTABLE";
};

export type ExportJob = {
  exportId: string;
  reportId: string;
  reportName: string;
  reportVersion: string;
  format: "CSV" | "XLSX" | "PDF" | "JSON";
  status: JobStatus;
  progressPercent: number;
  generatedAt: string | null;
  expiresAt: string | null;
  rowCount: number | null;
  sha256: string | null;
  sourceFreshness: FreshnessStatus;
  temporalMode: TemporalMode;
  filterSummary: string;
  downloadAllowed: boolean;
  correlationId: string | null;
};

export type ReportsWorkspace = {
  permissions: FinancePermission[];
  definitions: ReportDefinition[];
  exports: Page<ExportJob>;
};

export type CreateInvoiceInput = {
  monthId: string;
  documentKind: InvoiceDocumentKind;
  relatedInvoiceId: string | null;
  representedMetadata: InvoiceRepresentedMetadata;
};

export type UploadInvoiceDocumentInput = {
  expectedVersion: number;
  file: File;
  classification: string;
  retentionPolicy: string;
  source: "VENDOR_UPLOAD" | "FINANCE_UPLOAD";
  reason: string;
};

export type SubmitInvoiceInput = {
  expectedVersion: number;
  packageId: string;
  packageVersion: number;
  readinessRunId: string;
  acknowledgment: boolean;
  reason: string;
};

export type GeneratePackageInput = {
  expectedMonthVersion: number;
  readinessRunId: string;
  reason: string;
};

export type ProcurementReviewInput = {
  expectedVersion: number;
  decision: Exclude<ProcurementDecision, "EXCEPTION_ACCEPTED">;
  category: string | null;
  comment: string | null;
  packageId: string;
  packageVersion: number;
  readinessRunId: string;
};

export type ProcurementQueryInput = {
  expectedVersion: number;
  category: string;
  summary: string;
  ownerId: string;
  dueAt: string;
  reason: string;
};

export type ProcurementExceptionInput = {
  expectedVersion: number;
  ruleId: string;
  readinessRunId: string;
  packageId: string;
  packageVersion: number;
  rationale: string;
  validUntil: string;
};

export type ProcurementExceptionApprovalInput = {
  expectedVersion: number;
  invoiceId: string;
  ruleId: string;
  readinessRunId: string;
  packageId: string;
  packageVersion: number;
  policyVersionId: string;
  policyVersion: number;
};

export type ProcurementExceptionMutation = {
  exceptionId: string;
  invoiceId: string;
  exceptionStatus: ProcurementException["status"];
  ruleId: string;
  requestedReadinessRunId: string;
  packageId: string;
  packageVersion: number;
  policyVersionId: string;
  policyVersion: number;
  validUntil: string;
  requestedBySubject: string;
  secondApproverSubject: string | null;
  acceptedReadinessRunId: string | null;
  state: InvoiceState;
  version: number;
  etag: number;
  requestedAt: string;
  secondApprovedAt: string | null;
  expiredAt: string | null;
};

export type PaymentUpdateInput = {
  expectedVersion: number;
  status: PaymentStatus;
  statusAt: string;
  expectedPaymentDate: string | null;
  actualPaymentDate: string | null;
  externalReference: string | null;
  comment: string;
};

export type CreateExportInput = {
  reportId: string;
  reportVersion: string;
  format: "CSV" | "XLSX" | "PDF" | "JSON";
  temporalMode: "CURRENT" | "SNAPSHOT";
  filters: Record<string, string | string[]>;
  reason: string;
};
