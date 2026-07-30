import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import {
  AlertTriangle,
  CheckCircle2,
  DatabaseZap,
  Download,
  FileClock,
  LoaderCircle,
  RotateCcw,
  ShieldCheck,
  Upload,
} from "lucide-react";
import { type FormEvent, useMemo, useState } from "react";

import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ApiError } from "@/lib/api-client";

import { migrationApi } from "./api";
import type {
  CreateMigrationInput,
  MigrationJob,
  RetroRequest,
  RetroRequestInput,
} from "./contracts";
import { commitReadiness, formatTimestamp, safeIssueMessage } from "./presentation";

const key = () => crypto.randomUUID();

function ErrorNotice({ error }: { error: unknown }) {
  if (!error) return null;
  const problem = error instanceof ApiError ? error : null;
  return (
    <div className="rounded-md border border-destructive/40 bg-destructive/5 p-4" role="alert">
      <p className="flex items-center gap-2 font-medium">
        <AlertTriangle className="h-4 w-4" aria-hidden="true" />
        Migration action could not be completed
      </p>
      <p className="mt-1 text-sm text-muted-foreground">
        {problem?.code === "VERSION_CONFLICT"
          ? "A newer server version exists. Refresh and review it before acting."
          : "Review the safe validation detail and retry from the current server state."}
        {problem?.correlationId ? ` Reference: ${problem.correlationId}.` : ""}
      </p>
    </div>
  );
}

function TemplateCatalog({
  onUploaded,
  engagementId,
}: {
  onUploaded: (job: MigrationJob) => void;
  engagementId: string;
}) {
  const templates = useQuery({
    queryKey: ["migration", "templates"],
    queryFn: () => migrationApi.templates(engagementId),
    enabled: Boolean(engagementId),
    retry: false,
  });
  const upload = useMutation({
    mutationFn: ({ input, idempotencyKey }: { input: CreateMigrationInput; idempotencyKey: string }) =>
      migrationApi.upload(input, idempotencyKey),
    onSuccess: onUploaded,
  });
  const [file, setFile] = useState<File | null>(null);
  const [templateCode, setTemplateCode] = useState("");

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!file || !templateCode) return;
    const form = new FormData(event.currentTarget);
    upload.mutate({
      idempotencyKey: key(),
      input: {
        file,
        templateCode,
        organizationId: String(form.get("organizationId") ?? ""),
        engagementId,
        monthId: String(form.get("monthId") ?? "") || undefined,
        sourceType: String(form.get("sourceType") ?? ""),
        confidence: String(form.get("confidence") ?? ""),
        sourceDescription: String(form.get("sourceDescription") ?? ""),
        partialCommit: form.get("partialCommit") === "on",
      },
    });
  };

  return (
    <Card className="min-w-0 w-full">
      <CardHeader>
        <CardTitle>1. Template and staged upload</CardTitle>
      </CardHeader>
      <CardContent className="space-y-5">
        {templates.isPending ? (
          <p className="flex items-center gap-2" role="status">
            <LoaderCircle className="h-4 w-4 animate-spin" aria-hidden="true" />
            Loading governed template registry…
          </p>
        ) : templates.isError ? (
          <ErrorNotice error={templates.error} />
        ) : (
          <div className="w-full max-w-full overflow-x-auto">
            <table className="min-w-max w-full text-sm" aria-label="Historical migration templates">
              <thead>
                <tr className="border-b text-left">
                  <th className="py-2">Order</th>
                  <th>Template</th>
                  <th>Domain</th>
                  <th>Dependencies</th>
                  <th>Action</th>
                </tr>
              </thead>
              <tbody>
                {templates.data?.map((item) => (
                  <tr key={item.code} className="border-b align-top">
                    <td className="py-2">{item.wave}</td>
                    <td>
                      <span className="font-medium">{item.filename}</span>
                      <span className="block text-xs text-muted-foreground">v{item.version}</span>
                    </td>
                    <td>{item.code.split("_").slice(1).join(" ")}</td>
                    <td>{item.dependencies.length ? item.dependencies.join(", ") : "Foundation"}</td>
                    <td>
                      <div className="flex flex-wrap gap-1">
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => void migrationApi.downloadTemplate(engagementId, item.code, "CSV")}
                        >
                          <Download className="mr-1 h-4 w-4" aria-hidden="true" />
                          CSV
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => void migrationApi.downloadTemplate(engagementId, item.code, "XLSX")}
                        >
                          <Download className="mr-1 h-4 w-4" aria-hidden="true" />
                          XLSX + active references
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <form onSubmit={submit} className="grid gap-4 rounded-md border p-4 md:grid-cols-2">
          <label className="grid gap-1 text-sm">
            Template
            <select
              required
              value={templateCode}
              onChange={(event) => setTemplateCode(event.target.value)}
              className="rounded-md border bg-background px-3 py-2"
            >
              <option value="">Select exact template</option>
              {templates.data?.map((item) => (
                <option key={item.code} value={item.code}>
                  {item.filename}
                </option>
              ))}
            </select>
          </label>
          <label className="grid gap-1 text-sm">
            CSV source file
            <input
              required
              type="file"
              accept=".csv,text/csv"
              onChange={(event) => setFile(event.target.files?.[0] ?? null)}
              className="rounded-md border px-3 py-2"
            />
          </label>
          <label className="grid gap-1 text-sm">
            Organization ID
            <input name="organizationId" required className="rounded-md border px-3 py-2" />
          </label>
          <p className="rounded-md border px-3 py-2 text-sm">
            Server-derived engagement scope: <code>{engagementId}</code>
          </p>
          <label className="grid gap-1 text-sm">
            Historical month ID
            <input name="monthId" className="rounded-md border px-3 py-2" />
          </label>
          <label className="grid gap-1 text-sm">
            Source type
            <select name="sourceType" required className="rounded-md border bg-background px-3 py-2">
              <option value="APPROVED_SPREADSHEET">Approved spreadsheet</option>
              <option value="GREYTHR_EXPORT">greytHR export</option>
              <option value="LINEAR_EXPORT">Linear export</option>
              <option value="ORIGINAL_EMAIL">Original email</option>
              <option value="MANUAL_RECONSTRUCTION">Manual reconstruction</option>
            </select>
          </label>
          <label className="grid gap-1 text-sm">
            Confidence
            <select name="confidence" required className="rounded-md border bg-background px-3 py-2">
              <option value="HIGH">High</option>
              <option value="MEDIUM">Medium</option>
              <option value="LOW">Low</option>
              <option value="UNVERIFIED">Unverified</option>
            </select>
          </label>
          <label className="grid gap-1 text-sm">
            Source description
            <input name="sourceDescription" required maxLength={300} className="rounded-md border px-3 py-2" />
          </label>
          <label className="flex items-start gap-2 text-sm md:col-span-2">
            <input name="partialCommit" type="checkbox" />
            <span>
              Use valid-rows-only commit policy. Invalid and rejected rows remain quarantined and
              must be reprocessed separately; this policy cannot be changed after upload.
            </span>
          </label>
          <div className="md:col-span-2">
            <p className="mb-3 text-xs text-muted-foreground">
              Upload creates a dry-run staging job. It never writes canonical records directly.
            </p>
            <Button type="submit" disabled={upload.isPending || !file || !templateCode}>
              <Upload className="mr-1 h-4 w-4" aria-hidden="true" />
              Upload for dry run
            </Button>
          </div>
        </form>
        <ErrorNotice error={upload.error} />
      </CardContent>
    </Card>
  );
}

function JobList({ selectedJobId, engagementId }: { selectedJobId?: string; engagementId: string }) {
  const jobs = useQuery({
    queryKey: ["migration", "jobs"],
    queryFn: () => migrationApi.jobs(engagementId),
    retry: false,
  });
  return (
    <Card className="min-w-0 w-full">
      <CardHeader>
        <CardTitle>Migration jobs</CardTitle>
      </CardHeader>
      <CardContent>
        {jobs.isPending ? (
          <p role="status">Loading scoped migration jobs…</p>
        ) : jobs.isError ? (
          <ErrorNotice error={jobs.error} />
        ) : jobs.data?.items.length ? (
          <ul className="grid gap-2" aria-label="Authorized migration jobs">
            {jobs.data.items.map((job) => (
              <li key={job.jobId}>
                <Link
                  to="/migration"
                  search={{ jobId: job.jobId }}
                  className={`block rounded-md border p-3 hover:bg-muted ${
                    selectedJobId === job.jobId ? "border-primary" : ""
                  }`}
                >
                  <span className="font-medium">{job.safeFileName}</span>
                  <span className="ml-2"><StatusBadge status={job.state} /></span>
                  <span className="mt-1 block text-xs text-muted-foreground">
                    {job.templateCode} · {job.validRows} valid · {job.invalidRows} invalid · v{job.version}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        ) : (
          <p className="rounded-md border border-dashed p-4 text-sm">No migration jobs in this scope.</p>
        )}
      </CardContent>
    </Card>
  );
}

function JobWorkspace({
  jobId,
  approvalRole,
}: {
  jobId: string;
  approvalRole: "MIGRATION_LEAD" | "GOVERNANCE" | null;
}) {
  const client = useQueryClient();
  const jobQuery = useQuery({
    queryKey: ["migration", "job", jobId],
    queryFn: () => migrationApi.job(jobId),
    retry: false,
  });
  const [reason, setReason] = useState("Reviewed exact reconciliation and provenance");
  const [allowPartial, setAllowPartial] = useState(false);
  const refresh = async (job: MigrationJob) => {
    client.setQueryData(["migration", "job", jobId], job);
    await client.invalidateQueries({ queryKey: ["migration", "jobs"] });
  };
  const mutation = useMutation({
    mutationFn: async ({
      action,
      job,
      role,
    }: {
      action: "validate" | "approve" | "commit" | "reprocess" | "retry" | "cancel" | "rollback";
      job: MigrationJob;
      role?: "MIGRATION_LEAD" | "GOVERNANCE_REVIEWER";
    }) => {
      if (action === "validate") return migrationApi.validate(job, key());
      if (action === "approve") return migrationApi.approve(job, role!, reason, key());
      if (action === "commit") {
        return migrationApi.commit(
          job,
          job.partialCommit && allowPartial,
          key(),
        );
      }
      return migrationApi.action(job, action, reason, key());
    },
    onSuccess: refresh,
  });
  const queuedValidation = useMutation({
    mutationFn: (job: MigrationJob) =>
      migrationApi.queueValidation(job, key()),
    onSuccess: async () => {
      await client.invalidateQueries({
        queryKey: ["migration", "job", jobId],
      });
      await client.invalidateQueries({ queryKey: ["migration", "jobs"] });
    },
  });
  const correctionPlan = useQuery({
    queryKey: ["migration", "correction-plan", jobId],
    queryFn: () => migrationApi.correctionPlan(jobId),
    retry: false,
  });

  if (jobQuery.isPending) return <p className="p-6" role="status">Loading exact migration job…</p>;
  if (jobQuery.isError || !jobQuery.data) return <ErrorNotice error={jobQuery.error} />;
  const job = jobQuery.data;
  const blocker = commitReadiness(job);
  const policyBlocker = job.partialCommit && !allowPartial
    ? "Reaffirm the valid-rows-only commit policy before committing."
    : null;

  return (
    <div className="min-w-0 w-full space-y-5">
      <Card className="min-w-0 w-full">
        <CardHeader>
          <CardTitle className="flex flex-wrap items-center gap-2">
            {job.safeFileName} <StatusBadge status={job.state} />
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-3 text-sm sm:grid-cols-2 lg:grid-cols-4">
            <div><span className="text-muted-foreground">Template</span><strong className="block">{job.templateCode} v{job.templateVersion}</strong></div>
            <div><span className="text-muted-foreground">Source SHA-256</span><code className="block truncate">{job.sourceSha256}</code></div>
            <div><span className="text-muted-foreground">Recorded</span><strong className="block">{formatTimestamp(job.createdAt)}</strong></div>
            <div><span className="text-muted-foreground">Progress</span><strong className="block">{job.progressPercent}%</strong></div>
          </div>
          <progress className="h-2 w-full" value={job.progressPercent} max={100}>
            {job.progressPercent}%
          </progress>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-5">
            {[
              ["Total", job.totalRows],
              ["Valid", job.validRows],
              ["Warnings", job.warningRows],
              ["Invalid", job.invalidRows],
              ["Committed", job.committedRows],
            ].map(([label, value]) => (
              <div key={String(label)} className="rounded-md border p-3 text-center">
                <strong className="block text-lg">{value}</strong>
                <span className="text-xs text-muted-foreground">{label}</span>
              </div>
            ))}
          </div>
          <div className="flex flex-wrap gap-2">
            <Button
              onClick={() => mutation.mutate({ action: "validate", job })}
              disabled={mutation.isPending || queuedValidation.isPending || !job.permissions.includes("MIGRATION_VALIDATE")}
            >
              Validate staged rows
            </Button>
            <Button
              variant="outline"
              onClick={() => queuedValidation.mutate(job)}
              disabled={mutation.isPending || queuedValidation.isPending || !job.permissions.includes("MIGRATION_VALIDATE")}
            >
              Queue durable validation
            </Button>
            <Button variant="outline" onClick={() => void migrationApi.downloadErrors(job.jobId)}>
              <Download className="mr-1 h-4 w-4" aria-hidden="true" /> Download safe errors
            </Button>
            <Button
              variant="outline"
              onClick={() => mutation.mutate({ action: "reprocess", job })}
              disabled={mutation.isPending || !job.permissions.includes("MIGRATION_VALIDATE")}
            >
              <RotateCcw className="mr-1 h-4 w-4" aria-hidden="true" /> Reprocess rejects
            </Button>
          </div>
          {queuedValidation.isSuccess && (
            <p className="text-sm text-success" role="status">
              Validation is queued for resumable background execution.
            </p>
          )}
          {correctionPlan.data?.required && correctionPlan.data.monthId && (
            <div className="rounded-md border border-warning/40 bg-warning/10 p-3 text-sm">
              <strong>Consumed evidence requires governed correction.</strong>
              <p>
                Reopen the historical month through F04, complete the corrected
                certification, then generate a new superseding F05 package
                version. Prior package versions remain immutable.
              </p>
              <Link
                className="font-medium underline"
                to="/certification/$monthId"
                params={{ monthId: correctionPlan.data.monthId }}
              >
                Open governed month correction
              </Link>
            </div>
          )}
          <ErrorNotice error={mutation.error} />
          <ErrorNotice error={queuedValidation.error} />
        </CardContent>
      </Card>

      <RowReview job={job} onResolved={refresh} />

      <Card className="min-w-0 w-full">
        <CardHeader><CardTitle>Validation issues</CardTitle></CardHeader>
        <CardContent>
          {job.issues.length ? (
            <div
              aria-label="Migration row validation issues"
              className="w-full max-w-full overflow-x-auto"
              role="region"
              tabIndex={0}
            >
              <table className="min-w-max w-full text-sm" aria-label="Migration row validation issues">
                <thead><tr className="border-b text-left"><th>Row</th><th>Severity</th><th>Code</th><th>Field</th><th>Safe detail</th></tr></thead>
                <tbody>{job.issues.map((issue) => (
                  <tr key={`${issue.rowNumber}-${issue.code}-${issue.field}`} className="border-b">
                    <td className="py-2">{issue.rowNumber}</td><td>{issue.severity}</td>
                    <td><code>{issue.code}</code></td><td>{issue.field ?? "File"}</td>
                    <td>{safeIssueMessage(issue.safeMessage)}</td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
          ) : <p className="rounded-md border border-dashed p-4">No validation issues in the current result.</p>}
        </CardContent>
      </Card>

      <Card className="min-w-0 w-full">
        <CardHeader><CardTitle>2. Exact reconciliation and dual sign-off</CardTitle></CardHeader>
        <CardContent className="space-y-4">
          {job.reconciliation ? (
            <>
              <div className="grid gap-3 text-sm md:grid-cols-3">
                <p>Reconciliation v{job.reconciliation.version}<code className="block truncate">{job.reconciliation.sha256}</code></p>
                <p>Employee-days<strong className="block">{job.reconciliation.importedEmployeeDays} / {job.reconciliation.expectedEmployeeDays}</strong></p>
                <p>Low confidence<strong className="block">{job.reconciliation.lowConfidenceRows}</strong></p>
              </div>
              <ul className="grid gap-2 sm:grid-cols-2">
                {job.reconciliation.approvals.map((approval) => (
                  <li key={approval.approvalId} className="rounded-md border p-3">
                    <CheckCircle2 className="mr-1 inline h-4 w-4 text-success" aria-hidden="true" />
                    <strong>{approval.role.replaceAll("_", " ")}</strong>
                    <span className="block text-xs text-muted-foreground">{approval.actorDisplay} · {formatTimestamp(approval.recordedAt)}</span>
                  </li>
                ))}
              </ul>
              <label className="grid gap-1 text-sm">
                Approval / operation reason
                <input value={reason} onChange={(event) => setReason(event.target.value)} maxLength={500} className="rounded-md border px-3 py-2" />
              </label>
              <div className="flex flex-wrap gap-2">
                {approvalRole ? ([approvalRole === "GOVERNANCE"
                  ? "GOVERNANCE_REVIEWER" : "MIGRATION_LEAD"] as const).map((role) => (
                  <Button
                    key={role}
                    variant="outline"
                    disabled={mutation.isPending || !job.permissions.includes("MIGRATION_APPROVE")}
                    onClick={() => mutation.mutate({ action: "approve", job, role })}
                  >
                    <ShieldCheck className="mr-1 h-4 w-4" aria-hidden="true" />
                    Approve as {role === "MIGRATION_LEAD" ? "migration lead" : "governance reviewer"}
                  </Button>
                )) : (
                  <p className="text-sm text-muted-foreground">
                    No active sign-off assignment is available for this identity.
                  </p>
                )}
              </div>
              {job.partialCommit ? (
                <label className="flex items-start gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={allowPartial}
                    onChange={(event) => setAllowPartial(event.target.checked)}
                  />
                  Reaffirm the immutable valid-rows-only commit policy; rejected rows remain
                  quarantined and reprocessable.
                </label>
              ) : (
                <p className="text-sm text-muted-foreground">
                  Commit policy: all-or-nothing. Invalid rows block commit.
                </p>
              )}
              {blocker && <p className="text-sm text-amber-700" role="status">{blocker}</p>}
              {policyBlocker && (
                <p className="text-sm text-amber-700" role="status">{policyBlocker}</p>
              )}
              <Button
                disabled={
                  Boolean(blocker)
                  || Boolean(policyBlocker)
                  || mutation.isPending
                  || !job.permissions.includes("MIGRATION_COMMIT")
                }
                onClick={() => mutation.mutate({ action: "commit", job })}
              >
                <DatabaseZap className="mr-1 h-4 w-4" aria-hidden="true" /> Commit exact approved batch
              </Button>
            </>
          ) : (
            <p className="rounded-md border border-dashed p-4">Validation must produce a versioned reconciliation before approval.</p>
          )}
        </CardContent>
      </Card>

      <RecoveryAndRetro job={job} mutation={mutation} reason={reason} setReason={setReason} />
    </div>
  );
}

function RowReview({
  job,
  onResolved,
}: {
  job: MigrationJob;
  onResolved: (job: MigrationJob) => Promise<void>;
}) {
  const [state, setState] = useState("");
  const [afterRow, setAfterRow] = useState(1);
  const [reason, setReason] = useState("Reviewed source evidence and duplicate lineage");
  const rows = useQuery({
    queryKey: ["migration", "rows", job.jobId, state, afterRow],
    queryFn: () => migrationApi.rows(job.jobId, state || undefined, afterRow),
    retry: false,
  });
  const resolve = useMutation({
    mutationFn: ({
      rowId,
      decision,
    }: {
      rowId: string;
      decision: "KEEP_EXISTING" | "REJECT" | "VERSIONED_SUPERSEDE";
    }) => migrationApi.resolveRow(job, rowId, decision, reason, key()),
    onSuccess: async (updated) => {
      await onResolved(updated);
      await rows.refetch();
    },
  });
  return (
    <Card className="min-w-0 w-full">
      <CardHeader><CardTitle>Validation rows and mapping conflicts</CardTitle></CardHeader>
      <CardContent className="space-y-3">
        <div className="flex flex-wrap items-end gap-3">
          <label className="grid gap-1 text-sm">
            Row state
            <select
              value={state}
              onChange={(event) => {
                setState(event.target.value);
                setAfterRow(1);
              }}
              className="rounded-md border bg-background px-3 py-2"
            >
              <option value="">All rows</option>
              {["VALID", "WARNING", "INVALID", "DUPLICATE_IDENTICAL", "DUPLICATE_CONFLICT", "REJECTED", "COMMITTED"].map((value) => (
                <option key={value} value={value}>{value.replaceAll("_", " ")}</option>
              ))}
            </select>
          </label>
          <label className="min-w-72 flex-1 grid gap-1 text-sm">
            Resolution reason
            <input value={reason} onChange={(event) => setReason(event.target.value)} className="rounded-md border px-3 py-2" />
          </label>
        </div>
        {rows.isPending ? <p role="status">Loading governed row page…</p>
          : rows.isError ? <ErrorNotice error={rows.error} />
            : rows.data?.items.length ? (
              <div
                aria-label="Governed migration row page"
                className="w-full max-w-full overflow-x-auto"
                role="region"
                tabIndex={0}
              >
                <table className="min-w-max w-full text-sm" aria-label="Governed migration row page">
                  <thead><tr className="border-b text-left"><th>Row</th><th>State</th><th>Source</th><th>Confidence</th><th>Findings</th><th>Resolution</th></tr></thead>
                  <tbody>{rows.data.items.map((row) => (
                    <tr key={row.id} className="border-b align-top">
                      <td className="py-2">{row.rowNumber}</td>
                      <td><StatusBadge status={row.state} /></td>
                      <td>{row.sourceType}</td>
                      <td>{row.confidence}</td>
                      <td>{row.findings.map((finding) => <p key={`${finding.code}-${finding.field}`}><code>{finding.code}</code> {finding.message}</p>)}</td>
                      <td>
                        {row.state === "DUPLICATE_CONFLICT" ? (
                          <div className="flex flex-wrap gap-1">
                            <Button size="sm" variant="outline" disabled={resolve.isPending || reason.trim().length < 3} onClick={() => resolve.mutate({ rowId: row.id, decision: "KEEP_EXISTING" })}>Keep existing</Button>
                            <Button size="sm" variant="outline" disabled={resolve.isPending || reason.trim().length < 3} onClick={() => resolve.mutate({ rowId: row.id, decision: "VERSIONED_SUPERSEDE" })}>Supersede</Button>
                            <Button size="sm" variant="destructive" disabled={resolve.isPending || reason.trim().length < 3} onClick={() => resolve.mutate({ rowId: row.id, decision: "REJECT" })}>Reject</Button>
                          </div>
                        ) : "—"}
                      </td>
                    </tr>
                  ))}</tbody>
                </table>
              </div>
            ) : <p className="rounded-md border border-dashed p-4">No rows match this filter.</p>}
        <div className="flex gap-2">
          <Button variant="outline" disabled={afterRow <= 1} onClick={() => setAfterRow(Math.max(1, afterRow - 100))}>Previous rows</Button>
          <Button variant="outline" disabled={!rows.data?.hasMore} onClick={() => setAfterRow(rows.data?.nextRow ?? afterRow)}>Next rows</Button>
        </div>
        <ErrorNotice error={resolve.error} />
      </CardContent>
    </Card>
  );
}

function RecoveryAndRetro({
  job,
  mutation,
  reason,
  setReason,
}: {
  job: MigrationJob;
  mutation: ReturnType<typeof useMutation<MigrationJob, Error, { action: "validate" | "approve" | "commit" | "reprocess" | "retry" | "cancel" | "rollback"; job: MigrationJob; role?: "MIGRATION_LEAD" | "GOVERNANCE_REVIEWER" }>>;
  reason: string;
  setReason: (value: string) => void;
}) {
  const [retroType, setRetroType] = useState<RetroRequestInput["requestType"]>("CONFIRMATION");
  const [delegated, setDelegated] = useState(false);
  const [delegationReference, setDelegationReference] = useState("");
  const retro = useMutation({
    mutationFn: () =>
      migrationApi.retroRequest(
        {
          engagementId: job.engagementId ?? "",
          engagementMonthId: job.monthId ?? "",
          requestType: retroType,
          representedMonth: `${job.representedPeriod ?? "2026-06"}-01`,
          reason,
          originalActorUnavailable: delegated,
          delegationEvidenceReference: delegated ? delegationReference : undefined,
        },
        key(),
      ),
  });
  const readiness = useQuery({
    queryKey: ["migration", "month-readiness", job.monthId],
    queryFn: () => migrationApi.monthReadiness(job.monthId ?? ""),
    enabled: Boolean(job.monthId),
    retry: false,
  });
  const transition = useMutation({
    mutationFn: (targetState:
      | "HISTORICAL_PENDING_CERTIFICATION"
      | "HISTORICAL_PENDING_CONFIRMATION"
      | "CONFIRMED") =>
      migrationApi.transitionMonth(readiness.data!, targetState, reason, key()),
    onSuccess: (value) => {
      readiness.refetch();
      return value;
    },
  });
  return (
    <Card className="min-w-0 w-full">
      <CardHeader><CardTitle>3. Recovery and explicitly retroactive approval</CardTitle></CardHeader>
      <CardContent className="space-y-4">
        <p className="text-sm text-muted-foreground">
          Retro requests label the represented historical month but record the real current decision time. This UI has no backdate control.
        </p>
        <label className="grid gap-1 text-sm">Reason
          <input value={reason} onChange={(event) => setReason(event.target.value)} className="rounded-md border px-3 py-2" />
        </label>
        <div className="flex flex-wrap gap-2">
          {(job.state === "FAILED" || job.state === "CANCELLED") ? (
            <Button
              variant="outline"
              disabled={
                mutation.isPending
                || !job.permissions.includes("MIGRATION_VALIDATE")
                || reason.trim().length < 10
              }
              onClick={() => mutation.mutate({ action: "retry", job })}
            >
              <RotateCcw className="mr-1 h-4 w-4" aria-hidden="true" />
              Retry safe recovery
            </Button>
          ) : null}
          <Button variant="outline" disabled={mutation.isPending} onClick={() => mutation.mutate({ action: "cancel", job })}>Cancel pre-commit job</Button>
          <Button className="border-destructive text-foreground hover:bg-destructive/10" variant="outline" disabled={mutation.isPending || !job.permissions.includes("MIGRATION_ROLLBACK")} onClick={() => mutation.mutate({ action: "rollback", job })}>Request governed rollback</Button>
        </div>
        <div className="grid gap-3 rounded-md border p-4 md:grid-cols-2">
          <label className="grid gap-1 text-sm">Historical action
            <select value={retroType} onChange={(event) => setRetroType(event.target.value as RetroRequestInput["requestType"])} className="rounded-md border bg-background px-3 py-2">
              <option value="CONFIRMATION">Historical confirmation</option>
              <option value="CERTIFICATION">Historical certification</option>
              <option value="COMMITMENT">Historical commitment</option>
            </select>
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={delegated} onChange={(event) => setDelegated(event.target.checked)} />
            Original approver unavailable
          </label>
          {delegated && <label className="grid gap-1 text-sm">Delegation / replacement authority reference
            <input value={delegationReference} onChange={(event) => setDelegationReference(event.target.value)} required className="rounded-md border px-3 py-2" />
          </label>}
          <div className="self-end">
            <Button disabled={retro.isPending || !job.monthId || !job.permissions.includes("MIGRATION_RETRO") || (delegated && !delegationReference)} onClick={() => retro.mutate()}>
              <FileClock className="mr-1 h-4 w-4" aria-hidden="true" /> Create retro request now
            </Button>
          </div>
        </div>
        {retro.isSuccess && <p className="text-sm text-success" role="status">Historical request recorded with the current authenticated timestamp.</p>}
        <ErrorNotice error={retro.error} />
        {readiness.data ? (
          <div className="space-y-3 rounded-md border p-4" aria-label="Historical month readiness">
            <div className="flex flex-wrap items-center gap-2">
              <strong>Month readiness</strong>
              <StatusBadge status={readiness.data.state} />
              <span>{readiness.data.completedJobs} completed batches · {readiness.data.pendingRetroRequests} pending historical decisions</span>
            </div>
            {readiness.data.blockers.length > 0 && (
              <ul className="list-disc pl-5 text-sm">
                {readiness.data.blockers.map((blocker) => <li key={blocker}>{blocker.replaceAll("_", " ")}</li>)}
              </ul>
            )}
            <div className="flex flex-wrap gap-2">
              {readiness.data.state === "HISTORICAL_REVIEW" && (
                <Button variant="outline" disabled={transition.isPending} onClick={() => transition.mutate("HISTORICAL_PENDING_CERTIFICATION")}>Advance to pending certification</Button>
              )}
              {readiness.data.state === "HISTORICAL_PENDING_CERTIFICATION" && (
                <Button variant="outline" disabled={transition.isPending} onClick={() => transition.mutate("HISTORICAL_PENDING_CONFIRMATION")}>Advance to pending confirmation</Button>
              )}
              {readiness.data.state === "HISTORICAL_PENDING_CONFIRMATION" && (
                <Button disabled={transition.isPending} onClick={() => transition.mutate("CONFIRMED")}>Confirm historical month</Button>
              )}
            </div>
            <ErrorNotice error={transition.error} />
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}

function RetroInbox({
  engagementId,
  canDecide,
}: {
  engagementId: string;
  canDecide: boolean;
}) {
  const client = useQueryClient();
  const [reason, setReason] = useState("Reviewed reconstructed evidence for the represented month");
  const requests = useQuery({
    queryKey: ["migration", "retro-inbox", engagementId],
    queryFn: () => migrationApi.retroRequests(engagementId),
    enabled: Boolean(engagementId),
    retry: false,
  });
  const act = useMutation({
    mutationFn: ({
      request,
      action,
    }: {
      request: RetroRequest;
      action: "APPROVED" | "REJECTED" | "CANCELLED";
    }) => action === "CANCELLED"
      ? migrationApi.cancelRetro(request, reason, key())
      : migrationApi.decideRetro(request, action, reason, key()),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: ["migration", "retro-inbox", engagementId] });
    },
  });
  return (
    <Card className="min-w-0 w-full">
      <CardHeader><CardTitle>Historical approval and confirmation inbox</CardTitle></CardHeader>
      <CardContent className="space-y-3">
        <label className="grid gap-1 text-sm">
          Current-time decision / cancellation reason
          <input value={reason} onChange={(event) => setReason(event.target.value)} className="rounded-md border px-3 py-2" />
        </label>
        {requests.isPending ? <p role="status">Loading historical requests…</p>
          : requests.isError ? <ErrorNotice error={requests.error} />
            : requests.data?.items.length ? (
              <div className="w-full max-w-full overflow-x-auto">
                <table className="min-w-max w-full text-sm" aria-label="Historical request inbox">
                  <thead><tr className="border-b text-left"><th>Month</th><th>Type</th><th>State</th><th>Requested by</th><th>Procurement</th><th>Current-time action</th></tr></thead>
                  <tbody>{requests.data.items.map((request) => (
                    <tr key={request.id} className="border-b align-top">
                      <td className="py-2">{request.representedMonth}</td>
                      <td>{request.requestType}</td>
                      <td><StatusBadge status={request.state} /></td>
                      <td>{request.requestedBy}</td>
                      <td>{request.procurementNotificationState}</td>
                      <td>
                        {request.state === "PENDING" ? (
                          <div className="flex flex-wrap gap-1">
                            {canDecide && <Button size="sm" disabled={act.isPending || reason.trim().length < 3} onClick={() => act.mutate({ request, action: "APPROVED" })}>Approve now</Button>}
                            {canDecide && <Button className="border-destructive text-foreground hover:bg-destructive/10" size="sm" variant="outline" disabled={act.isPending || reason.trim().length < 3} onClick={() => act.mutate({ request, action: "REJECTED" })}>Reject now</Button>}
                            <Button size="sm" variant="outline" disabled={act.isPending || reason.trim().length < 3} onClick={() => act.mutate({ request, action: "CANCELLED" })}>Cancel</Button>
                          </div>
                        ) : request.decisionAt ? formatTimestamp(request.decisionAt) : "—"}
                      </td>
                    </tr>
                  ))}</tbody>
                </table>
              </div>
            ) : <p className="rounded-md border border-dashed p-4">No historical requests in this scope.</p>}
        <ErrorNotice error={act.error} />
      </CardContent>
    </Card>
  );
}

export function MigrationWorkspace({ selectedJobId }: { selectedJobId?: string }) {
  const access = useQuery({
    queryKey: ["migration", "access"],
    queryFn: migrationApi.access,
    retry: false,
  });
  const [uploadedId, setUploadedId] = useState<string | undefined>();
  const activeJobId = selectedJobId ?? uploadedId;
  const external = useMemo(() => access.data?.externalAcceptance ?? "ACTION_REQUIRED", [access.data]);
  const engagementId = access.data?.engagementId ?? "";

  return (
    <div className="min-w-0 w-full max-w-full space-y-6 p-4 md:p-6">
      <header className="flex min-w-0 w-full flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <p className="text-sm font-medium text-primary">F06 · governed historical data</p>
          <h1 className="text-2xl font-semibold">Historical migration center</h1>
          <p className="mt-1 max-w-3xl text-sm text-muted-foreground">
            Stage, validate, reconcile, approve and commit June 2026 onward without backdated audit fiction.
          </p>
        </div>
        <div className="rounded-md border p-3 text-sm">
          External production rehearsal: <StatusBadge status={external} />
        </div>
      </header>
      {engagementId ? (
        <TemplateCatalog
          engagementId={engagementId}
          onUploaded={(job) => setUploadedId(job.jobId)}
        />
      ) : (
        <ErrorNotice error={access.error} />
      )}
      <div className="grid min-w-0 w-full gap-6 xl:grid-cols-[22rem_minmax(0,1fr)]">
        {engagementId ? (
          <div className="min-w-0">
            <JobList selectedJobId={activeJobId} engagementId={engagementId} />
          </div>
        ) : <div className="min-w-0" />}
        {activeJobId ? (
          <div className="min-w-0">
            <JobWorkspace
              jobId={activeJobId}
              approvalRole={access.data?.approvalRole ?? null}
            />
          </div>
        ) : (
          <div className="grid min-h-64 min-w-0 place-items-center rounded-lg border border-dashed p-8 text-center">
            <div>
              <DatabaseZap className="mx-auto h-8 w-8 text-muted-foreground" aria-hidden="true" />
              <h2 className="mt-3 font-medium">Select or upload a migration job</h2>
              <p className="mt-1 text-sm text-muted-foreground">Canonical data is unchanged until exact reconciliation and dual sign-off.</p>
            </div>
          </div>
        )}
      </div>
      {engagementId ? (
        <RetroInbox
          engagementId={engagementId}
          canDecide={Boolean(access.data?.approvalRole)}
        />
      ) : null}
    </div>
  );
}
