import { apiClient } from "@/lib/api-client";

import type {
  CreateMigrationInput,
  MigrationAccess,
  MigrationJob,
  MigrationPage,
  Reconciliation,
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
  downloadTemplate: async (engagementId: string, code: string) => {
    const result = await apiClient.download(
      `${root}/templates/${encoded(code)}/download?engagementId=${encoded(engagementId)}`,
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
};
