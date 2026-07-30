import { apiClient } from "@/lib/api-client";

import type {
  CreateMigrationInput,
  MigrationAccess,
  MigrationJob,
  MigrationRow,
  MonthReadiness,
  MigrationPage,
  Reconciliation,
  RetroRequest,
  RetroRequestInput,
  TemplateDescriptor,
} from "./contracts";

const root = "/migrations";
const encoded = encodeURIComponent;

function mutationHeaders(version: number, key: string) {
  return { "If-Match": `"${version}"`, "Idempotency-Key": key };
}

function saveBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fileName;
  anchor.click();
  URL.revokeObjectURL(url);
}

export const migrationApi = {
  access: () => apiClient.get<MigrationAccess>(`${root}/access`),
  templates: (engagementId: string) =>
    apiClient.get<TemplateDescriptor[]>(
      `${root}/templates?engagementId=${encoded(engagementId)}`,
    ),
  jobs: (engagementId: string, cursor?: string | null) =>
    apiClient.get<MigrationPage<MigrationJob>>(
      `${root}/jobs?engagementId=${encoded(engagementId)}${
        cursor ? `&cursor=${encoded(cursor)}` : ""
      }`,
    ),
  job: (jobId: string) => apiClient.get<MigrationJob>(`${root}/jobs/${encoded(jobId)}`),
  upload: (input: CreateMigrationInput, idempotencyKey: string) => {
    const form = new FormData();
    form.set("file", input.file);
    form.set(
      "metadata",
      new Blob(
        [
          JSON.stringify({
            templateCode: input.templateCode,
            organizationId: input.organizationId,
            engagementId: input.engagementId,
            engagementMonthId: input.monthId || null,
            templateVersion: "1",
            mode: "DRY_RUN",
            partialCommit: input.partialCommit,
            sourceType: input.sourceType,
            confidence: input.confidence,
            sourceDescription: input.sourceDescription,
          }),
        ],
        { type: "application/json" },
      ),
    );
    return apiClient.postForm<MigrationJob>(`${root}/jobs`, form, {
      headers: { "Idempotency-Key": idempotencyKey },
    });
  },
  validate: (job: MigrationJob, key: string) =>
    apiClient.post<MigrationJob>(
      `${root}/jobs/${encoded(job.jobId)}/validate`,
      { expectedVersion: job.version },
      { headers: mutationHeaders(job.version, key) },
    ),
  queueValidation: (job: MigrationJob, key: string) =>
    apiClient.post<{
      jobId: string;
      state: string;
      executionState: "QUEUED";
      version: number;
    }>(
      `${root}/jobs/${encoded(job.jobId)}/validation-runs`,
      { expectedVersion: job.version },
      { headers: mutationHeaders(job.version, key) },
    ),
  approve: (
    job: MigrationJob,
    role: "MIGRATION_LEAD" | "GOVERNANCE_REVIEWER",
    reason: string,
    key: string,
  ) =>
    apiClient.post<MigrationJob>(
      `${root}/jobs/${encoded(job.jobId)}/approvals`,
      {
        expectedVersion: job.version,
        role,
        decision: "APPROVED",
        reconciliationId: job.reconciliation?.reconciliationId,
        reconciliationHash: job.reconciliation?.sha256,
        reason,
      },
      { headers: mutationHeaders(job.version, key) },
    ),
  commit: (job: MigrationJob, partialCommit: boolean, key: string) =>
    apiClient.post<MigrationJob>(
      `${root}/jobs/${encoded(job.jobId)}/commit`,
      { expectedVersion: job.version, partialCommit },
      { headers: mutationHeaders(job.version, key) },
    ),
  action: (
    job: MigrationJob,
    action: "reprocess" | "retry" | "cancel" | "rollback",
    reason: string,
    key: string,
  ) =>
    apiClient.post<MigrationJob>(
      `${root}/jobs/${encoded(job.jobId)}/${action}`,
      action === "reprocess"
        ? { expectedVersion: job.version }
        : { expectedVersion: job.version, reason },
      { headers: mutationHeaders(job.version, key) },
    ),
  reconciliation: (jobId: string) =>
    apiClient.get<Reconciliation>(`${root}/jobs/${encoded(jobId)}/reconciliation`),
  rows: (jobId: string, state?: string, afterRow = 1) =>
    apiClient.get<{ items: MigrationRow[]; hasMore: boolean; nextRow: number }>(
      `${root}/jobs/${encoded(jobId)}/rows?limit=100&afterRow=${afterRow}${
        state ? `&state=${encoded(state)}` : ""
      }`,
    ),
  correctionPlan: (jobId: string) =>
    apiClient.get<{
      jobId: string;
      monthId?: string;
      required: boolean;
      requiredAction?: string;
      packages?: Array<{
        id: string;
        version: number;
        status: string;
        supersedesId: string | null;
      }>;
      latestReopen?: { id: string; status: string } | null;
    }>(`${root}/jobs/${encoded(jobId)}/correction-plan`),
  resolveRow: (
    job: MigrationJob,
    rowId: string,
    decision: "KEEP_EXISTING" | "REJECT" | "VERSIONED_SUPERSEDE",
    reason: string,
    key: string,
  ) =>
    apiClient.post<MigrationJob>(
      `${root}/jobs/${encoded(job.jobId)}/rows/${encoded(rowId)}/resolution`,
      { expectedVersion: job.version, decision, reason },
      { headers: mutationHeaders(job.version, key) },
    ),
  downloadTemplate: async (
    engagementId: string,
    code: string,
    format: "CSV" | "XLSX" = "CSV",
  ) => {
    const result = await apiClient.download(
      `${root}/templates/${encoded(code)}/download?engagementId=${encoded(engagementId)}&format=${format}`,
    );
    saveBlob(result.blob, result.fileName);
  },
  downloadErrors: async (jobId: string) => {
    const result = await apiClient.download(`${root}/jobs/${encoded(jobId)}/errors/download`);
    saveBlob(result.blob, result.fileName);
  },
  retroRequest: (input: RetroRequestInput, key: string) =>
    apiClient.post<Record<string, unknown>>(`${root}/retro-requests`, input, {
      headers: { "Idempotency-Key": key },
    }),
  retroRequests: (engagementId: string, state?: string) =>
    apiClient.get<{ items: RetroRequest[]; count: number }>(
      `${root}/retro-requests?engagementId=${encoded(engagementId)}${
        state ? `&state=${encoded(state)}` : ""
      }`,
    ),
  decideRetro: (
    request: RetroRequest,
    decision: "APPROVED" | "REJECTED",
    reason: string,
    key: string,
  ) =>
    apiClient.post<RetroRequest>(
      `${root}/retro-requests/${encoded(request.id)}/decision`,
      { expectedVersion: request.version, decision, reason },
      { headers: mutationHeaders(request.version, key) },
    ),
  cancelRetro: (request: RetroRequest, reason: string, key: string) =>
    apiClient.post<RetroRequest>(
      `${root}/retro-requests/${encoded(request.id)}/cancel`,
      { expectedVersion: request.version, reason },
      { headers: mutationHeaders(request.version, key) },
    ),
  monthReadiness: (monthId: string) =>
    apiClient.get<MonthReadiness>(`${root}/months/${encoded(monthId)}/readiness`),
  transitionMonth: (
    readiness: MonthReadiness,
    targetState:
      | "HISTORICAL_PENDING_CERTIFICATION"
      | "HISTORICAL_PENDING_CONFIRMATION"
      | "CONFIRMED",
    reason: string,
    key: string,
  ) =>
    apiClient.post<MonthReadiness>(
      `${root}/months/${encoded(readiness.monthId)}/transitions`,
      { expectedVersion: readiness.version, targetState, reason },
      { headers: mutationHeaders(readiness.version, key) },
    ),
};
