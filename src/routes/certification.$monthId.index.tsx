import { Link, createFileRoute } from "@tanstack/react-router";
import { useQueryClient } from "@tanstack/react-query";
import { LockKeyhole, Save, Send, Undo2 } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  AuthorityNotice,
  EmptyState,
  EvidenceTimeline,
  Field,
  LinearSnapshotPanel,
  VersionNotice,
} from "@/features/certification/components";
import { certificationApi } from "@/features/certification/api";
import type {
  DeliveryOutcome,
  MonthCertificationView,
  SafeEvidenceReference,
  SaveSubmissionRequest,
  SubmissionItemInput,
} from "@/features/certification/contracts";
import {
  certificationKeys,
  useCertificationMonth,
  useClarification,
  useSaveSubmission,
  useSubmitSubmission,
  useWithdrawSubmission,
} from "@/features/certification/hooks";
import {
  deliveryOutcomeOptions,
  requiresVendorVariance,
} from "@/features/certification/presentation";
import {
  CertificationMutationError,
  CertificationQueryBoundary,
} from "@/features/certification/query-boundary";

export const Route = createFileRoute("/certification/$monthId/")({
  head: () => ({ meta: [{ title: "Vendor submission — Cadence" }] }),
  component: VendorSubmissionPage,
});

function VendorSubmissionPage() {
  const { monthId } = Route.useParams();
  const query = useCertificationMonth(monthId);
  return (
    <div>
      <PageHeader
        title={query.data ? `${query.data.monthLabel} delivery submission` : "Delivery submission"}
        description="Record vendor outcomes against the effective frozen baseline. Submitted evidence is additive and read-only."
      >
        <Link
          to="/certification/$monthId/review"
          params={{ monthId }}
          className="rounded-md border px-3 py-2 text-sm font-medium hover:bg-muted"
        >
          Product-owner review
        </Link>
        <Link
          to="/confirmation/$monthId"
          params={{ monthId }}
          className="rounded-md border px-3 py-2 text-sm font-medium hover:bg-muted"
        >
          Readiness
        </Link>
      </PageHeader>
      <CertificationQueryBoundary queries={[query]}>
        {query.data && <VendorWorkspace month={query.data} />}
      </CertificationQueryBoundary>
    </div>
  );
}

function VendorWorkspace({ month }: { month: MonthCertificationView }) {
  const queryClient = useQueryClient();
  const initialDraft = draftFromMonth(month);
  const [summary, setSummary] = useState(initialDraft.summary);
  const [declarationAccepted, setDeclarationAccepted] = useState(initialDraft.declarationAccepted);
  const [items, setItems] = useState<SubmissionItemInput[]>(initialDraft.items);
  const [dirty, setDirty] = useState(false);
  const serverVersionKey = `${month.version}:${month.submission?.version ?? "new"}`;
  const reconciledVersion = useRef(serverVersionKey);
  const save = useSaveSubmission(month.monthId);
  const submit = useSubmitSubmission(month.monthId);
  const withdraw = useWithdrawSubmission(month.monthId, month.submission?.id ?? "");
  const artifactInput = useRef<HTMLInputElement>(null);
  const artifactClassification = useRef<HTMLSelectElement>(null);
  const [artifactPending, setArtifactPending] = useState(false);
  const [artifactError, setArtifactError] = useState<Error | null>(null);
  const [artifactStatus, setArtifactStatus] = useState("");
  const readOnly =
    month.locked ||
    month.stale ||
    Boolean(month.submission?.locked) ||
    !month.permissions.canEditSubmission;
  const blockers = month.submission?.completenessBlockers ?? [];

  useEffect(() => {
    if (reconciledVersion.current === serverVersionKey) return;
    reconciledVersion.current = serverVersionKey;
    const current = draftFromMonth(month);
    setSummary(current.summary);
    setDeclarationAccepted(current.declarationAccepted);
    setItems(current.items);
    setDirty(false);
  }, [month, serverVersionKey]);

  function updateItem(index: number, patch: Partial<SubmissionItemInput>) {
    setDirty(true);
    setItems((current) =>
      current.map((item, itemIndex) => (itemIndex === index ? { ...item, ...patch } : item)),
    );
  }

  function currentDraft(): SaveSubmissionRequest {
    return {
      expectedMonthVersion: month.version,
      summary,
      declarationAccepted,
      items,
    };
  }

  function saveDraft(event: React.FormEvent) {
    event.preventDefault();
    save.mutate(currentDraft(), {
      onSuccess: () => setDirty(false),
    });
  }

  function submitSavedVersion(submission: NonNullable<MonthCertificationView["submission"]>) {
    submit.mutate({
      submissionId: submission.id,
      expectedVersion: submission.version,
    });
  }

  function submitExactVisibleDraft() {
    if (!month.submission) return;
    if (!dirty) {
      submitSavedVersion(month.submission);
      return;
    }

    save.mutate(currentDraft(), {
      onSuccess: (savedMonth) => {
        setDirty(false);
        if (savedMonth.submission && savedMonth.submission.completenessBlockers.length === 0) {
          submitSavedVersion(savedMonth.submission);
        }
      },
    });
  }

  return (
    <form onSubmit={saveDraft} noValidate className="space-y-6 p-6">
      <AuthorityNotice />
      <VersionNotice
        version={month.version}
        stale={month.stale}
        locked={readOnly}
        updatedAt={month.lastEvaluatedAt}
      />

      {blockers.length > 0 && (
        <div
          className="rounded-lg border border-destructive/30 bg-destructive/5 p-4"
          role="alert"
          aria-labelledby="submission-errors-title"
        >
          <p id="submission-errors-title" className="font-medium">
            Submission completeness blockers
          </p>
          <ul className="mt-2 list-disc space-y-1 pl-5 text-sm">
            {blockers.map((blocker) => (
              <li key={blocker}>{blocker}</li>
            ))}
          </ul>
        </div>
      )}

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Effective frozen scope</CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="grid gap-4 md:grid-cols-4">
            <Field label="Lifecycle" value={month.lifecycleState} />
            <Field label="Baseline" value={month.baseline.id} mono />
            <Field label="Baseline version" value={month.baseline.versionId} mono />
            <Field label="Baseline checksum" value={month.baseline.checksum} mono />
          </dl>
        </CardContent>
      </Card>

      <LinearSnapshotPanel snapshots={month.linearSnapshots ?? []} />

      <Card>
        <CardHeader><CardTitle className="text-base">Governed evidence upload</CardTitle></CardHeader>
        <CardContent className="space-y-3">
          <div className="flex flex-wrap items-end gap-3">
            <div className="grid gap-1"><Label htmlFor="evidence-file">Evidence file</Label><Input ref={artifactInput} id="evidence-file" name="evidenceFile" type="file" disabled={readOnly} /></div>
            <div className="grid gap-1"><Label htmlFor="evidence-classification">Classification</Label>
              <select ref={artifactClassification} id="evidence-classification" name="classification" defaultValue="CONFIDENTIAL" className="h-9 rounded-md border bg-background px-3 text-sm" disabled={readOnly}>
                <option value="INTERNAL">Internal</option><option value="CONFIDENTIAL">Confidential</option><option value="RESTRICTED">Restricted</option>
              </select>
            </div>
            <Button type="button" variant="outline" disabled={readOnly || artifactPending} onClick={async () => {
              const file = artifactInput.current?.files?.[0];
              if (!file) {
                setArtifactError(new Error("Choose an evidence file first."));
                return;
              }
              setArtifactPending(true);
              setArtifactError(null);
              setArtifactStatus("Uploading evidence…");
              try {
                // File remains an event-local value. It is never stored in React or
                // TanStack Query mutation state.
                const uploaded = await certificationApi.uploadArtifact(
                  month.monthId,
                  file,
                  (artifactClassification.current?.value ?? "CONFIDENTIAL") as
                    | "PUBLIC"
                    | "INTERNAL"
                    | "CONFIDENTIAL"
                    | "RESTRICTED",
                );
                setArtifactStatus("Scanning uploaded evidence…");
                const scanned = await certificationApi.scanArtifact(uploaded.id);
                setArtifactStatus(`Scan ${scanned.scanStatus.toLowerCase()}.`);
                if (artifactInput.current) artifactInput.current.value = "";
                await queryClient.invalidateQueries({
                  queryKey: certificationKeys.month(month.monthId),
                });
              } catch (error) {
                setArtifactError(error instanceof Error ? error : new Error("Evidence upload failed."));
                setArtifactStatus("");
              } finally {
                setArtifactPending(false);
              }
            }}>
              {artifactPending ? "Uploading and scanning…" : "Upload and initiate scan"}
            </Button>
          </div>
          <p className="text-xs text-muted-foreground">The browser sends bytes once. Object paths remain server-side; only scan-passed safe metadata becomes selectable.</p>
          {artifactStatus && <p role="status" className="text-xs text-muted-foreground">{artifactStatus}</p>}
          <CertificationMutationError error={artifactError} />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Vendor month summary</CardTitle>
        </CardHeader>
        <CardContent>
          <Label htmlFor="vendor-month-summary">Delivery summary</Label>
          <Textarea
            id="vendor-month-summary"
            className="mt-1"
            value={summary}
            onChange={(event) => {
              setSummary(event.target.value);
              setDirty(true);
            }}
            disabled={readOnly}
            required
          />
        </CardContent>
      </Card>

      <section className="space-y-4" aria-labelledby="deliverable-outcomes-title">
        <h2 id="deliverable-outcomes-title" className="text-lg font-semibold">
          Per-deliverable outcomes
        </h2>
        {month.deliverables.length === 0 ? (
          <EmptyState
            title="No effective deliverables"
            detail="A frozen baseline must be resolved by the server before a vendor draft can be saved."
          />
        ) : (
          month.deliverables.map((deliverable, index) => {
            const item = items[index];
            if (!item) return null;
            return (
              <Card key={deliverable.id}>
                <CardHeader>
                  <div className="flex flex-wrap items-start justify-between gap-2">
                    <div>
                      <CardTitle className="text-base">
                        {deliverable.code} · {deliverable.title}
                      </CardTitle>
                      <p className="mt-1 text-xs text-muted-foreground">
                        {deliverable.projectName} · immutable baseline{" "}
                        {deliverable.baselineVersionId}
                      </p>
                    </div>
                    {deliverable.vendorSubmission && (
                      <StatusBadge status={deliverable.vendorSubmission.outcome} />
                    )}
                  </div>
                </CardHeader>
                <CardContent className="space-y-5">
                  <dl className="grid gap-4 md:grid-cols-2">
                    <Field label="Baseline scope" value={deliverable.baselineDescription} />
                    <Field label="Business objective" value={deliverable.businessObjective} />
                    <Field label="Evidence expectation" value={deliverable.evidenceExpectation} />
                  </dl>

                  <div className="grid gap-4 md:grid-cols-3">
                    <div>
                      <Label htmlFor={`outcome-${deliverable.id}`}>Vendor outcome</Label>
                      <select
                        id={`outcome-${deliverable.id}`}
                        className="mt-1 h-9 w-full rounded-md border bg-background px-3 text-sm"
                        value={item.outcome}
                        disabled={readOnly}
                        onChange={(event) =>
                          updateItem(index, {
                            outcome: event.target.value as DeliveryOutcome,
                          })
                        }
                      >
                        {deliveryOutcomeOptions.map((option) => (
                          <option key={option.value} value={option.value}>
                            {option.label}
                          </option>
                        ))}
                      </select>
                    </div>
                    <div>
                      <Label htmlFor={`percentage-${deliverable.id}`}>Completion percent</Label>
                      <Input
                        id={`percentage-${deliverable.id}`}
                        className="mt-1"
                        type="number"
                        min={0}
                        max={100}
                        value={item.completionPercentage}
                        disabled={readOnly}
                        onChange={(event) =>
                          updateItem(index, {
                            completionPercentage: Number(event.target.value),
                          })
                        }
                      />
                    </div>
                    <div>
                      <Label htmlFor={`completion-date-${deliverable.id}`}>Completion date</Label>
                      <Input
                        id={`completion-date-${deliverable.id}`}
                        className="mt-1"
                        type="date"
                        value={item.completionDate ?? ""}
                        disabled={readOnly}
                        onChange={(event) =>
                          updateItem(index, { completionDate: event.target.value || undefined })
                        }
                      />
                    </div>
                  </div>

                  <div>
                    <Label htmlFor={`item-summary-${deliverable.id}`}>Outcome summary</Label>
                    <Textarea
                      id={`item-summary-${deliverable.id}`}
                      className="mt-1"
                      value={item.summary}
                      disabled={readOnly}
                      onChange={(event) => updateItem(index, { summary: event.target.value })}
                    />
                  </div>

                  {requiresVendorVariance(item.outcome) && (
                    <div className="grid gap-4 md:grid-cols-2">
                      <TextField
                        id={`cause-${deliverable.id}`}
                        label="Variance cause"
                        value={item.varianceCause ?? ""}
                        disabled={readOnly}
                        onChange={(value) => updateItem(index, { varianceCause: value })}
                      />
                      <TextField
                        id={`impact-${deliverable.id}`}
                        label="Variance impact"
                        value={item.varianceImpact ?? ""}
                        disabled={readOnly}
                        onChange={(value) => updateItem(index, { varianceImpact: value })}
                      />
                      <TextField
                        id={`next-${deliverable.id}`}
                        label="Next action"
                        value={item.nextAction ?? ""}
                        disabled={readOnly}
                        onChange={(value) => updateItem(index, { nextAction: value })}
                      />
                      <TextField
                        id={`carry-${deliverable.id}`}
                        label="Carry-forward proposal"
                        value={item.carryForwardProposal ?? ""}
                        disabled={readOnly}
                        onChange={(value) => updateItem(index, { carryForwardProposal: value })}
                      />
                    </div>
                  )}

                  <div>
                    <h3 className="text-sm font-medium">Acceptance criterion responses</h3>
                    <div className="mt-2 space-y-3">
                      {deliverable.criteria.map((criterion, criterionIndex) => (
                        <div key={criterion.id} className="rounded-md border p-3">
                          <Label htmlFor={`criterion-${criterion.id}`}>
                            {criterion.sequence}. {criterion.statement}
                            {criterion.mandatory ? " (required)" : ""}
                          </Label>
                          <p className="mt-1 text-xs text-muted-foreground">
                            Expected: {criterion.expectedResult}
                          </p>
                          <Textarea
                            id={`criterion-${criterion.id}`}
                            className="mt-2"
                            value={item.criterionResponses[criterionIndex]?.response ?? ""}
                            disabled={readOnly}
                            onChange={(event) => {
                              const criterionResponses = [...item.criterionResponses];
                              const current = criterionResponses[criterionIndex];
                              if (current) {
                                criterionResponses[criterionIndex] = {
                                  ...current,
                                  response: event.target.value,
                                };
                                updateItem(index, { criterionResponses });
                              }
                            }}
                          />
                          <EvidenceSelector
                            id={`criterion-evidence-${criterion.id}`}
                            label={`Evidence for ${criterion.sequence}. ${criterion.statement}`}
                            choices={month.evidenceChoices ?? []}
                            selected={
                              item.criterionResponses[criterionIndex]?.evidenceReferenceIds ?? []
                            }
                            disabled={readOnly}
                            onChange={(evidenceReferenceIds) => {
                              const criterionResponses = [...item.criterionResponses];
                              const current = criterionResponses[criterionIndex];
                              if (current) {
                                criterionResponses[criterionIndex] = {
                                  ...current,
                                  evidenceReferenceIds,
                                };
                                updateItem(index, { criterionResponses });
                              }
                            }}
                          />
                        </div>
                      ))}
                    </div>
                  </div>

                  <div>
                    <EvidenceSelector
                      id={`evidence-${deliverable.id}`}
                      label="Server-managed evidence references"
                      choices={month.evidenceChoices ?? []}
                      selected={item.evidenceReferenceIds}
                      disabled={readOnly}
                      onChange={(evidenceReferenceIds) =>
                        updateItem(index, { evidenceReferenceIds })
                      }
                    />
                    <p
                      id={`evidence-help-${deliverable.id}`}
                      className="mt-1 text-xs text-muted-foreground"
                    >
                      References are resolved, authorized, and scan-gated by the server. This page
                      never stores file content, signed URLs, MIME, or provider credentials.
                    </p>
                    {(deliverable.vendorSubmission?.evidenceReferences ?? []).map((reference) => (
                      <div
                        key={reference.id}
                        className="mt-2 flex flex-wrap items-center gap-2 text-xs"
                      >
                        <span>{reference.displayName}</span>
                        <StatusBadge status={reference.scanStatus} />
                        <span>{reference.classification}</span>
                      </div>
                    ))}
                  </div>
                </CardContent>
              </Card>
            );
          })
        )}
      </section>

      <Card>
        <CardContent className="space-y-4 py-5">
          <div className="flex items-start gap-2">
            <Checkbox
              id="vendor-declaration"
              checked={declarationAccepted}
              disabled={readOnly}
              onCheckedChange={(checked) => {
                setDeclarationAccepted(checked === true);
                setDirty(true);
              }}
            />
            <Label htmlFor="vendor-declaration" className="leading-5">
              I declare that every effective deliverable and criterion is represented accurately for
              this exact baseline version.
            </Label>
          </div>
          {readOnly && (
            <p className="flex items-center gap-2 text-sm text-muted-foreground">
              <LockKeyhole className="h-4 w-4" aria-hidden="true" />
              Submitted content cannot be overwritten. Respond to an open clarification below.
            </p>
          )}
          <CertificationMutationError error={save.error ?? submit.error ?? withdraw.error} />
          <div className="flex flex-wrap gap-2">
            <Button type="submit" disabled={readOnly || save.isPending}>
              <Save className="mr-2 h-4 w-4" aria-hidden="true" />
              {save.isPending ? "Saving…" : "Save draft"}
            </Button>
            <Button
              type="button"
              disabled={
                !month.submission ||
                !month.permissions.canSubmit ||
                month.stale ||
                month.submission.locked ||
                blockers.length > 0 ||
                save.isPending ||
                submit.isPending
              }
              onClick={submitExactVisibleDraft}
              aria-describedby="submit-exact-help"
            >
              <Send className="mr-2 h-4 w-4" aria-hidden="true" />
              {save.isPending && dirty
                ? "Saving visible edits…"
                : submit.isPending
                  ? "Submitting exact version…"
                  : "Submit exact version"}
            </Button>
            <Button
              type="button"
              variant="outline"
              disabled={
                !month.submission ||
                month.submission.status !== "DRAFT" ||
                dirty ||
                withdraw.isPending
              }
              onClick={() => {
                if (!month.submission) return;
                const reason = window.prompt("Why is this draft being withdrawn?");
                if (reason?.trim()) {
                  withdraw.mutate({
                    expectedSubmissionVersion: month.submission.version,
                    reason: reason.trim(),
                  });
                }
              }}
            >
              <Undo2 className="mr-2 h-4 w-4" aria-hidden="true" />
              {withdraw.isPending ? "Withdrawing…" : "Withdraw draft"}
            </Button>
          </div>
          <p id="submit-exact-help" className="text-xs text-muted-foreground">
            {dirty
              ? "Visible edits are not yet server-versioned. Submission saves them first, then submits only the returned exact version."
              : "Submission locks the latest saved server version; later correction is additive."}
          </p>
          <p className="text-xs text-muted-foreground" role="status" aria-live="polite">
            {save.isPending
              ? "Saving the current draft…"
              : save.isSuccess
                ? "Draft saved to the server."
                : month.submission?.autosavedAt
                  ? `Last server save: ${month.submission.autosavedAt}`
                  : "This draft has not been saved yet."}
          </p>
          {!month.permissions.canSubmit && (
            <p className="text-xs text-muted-foreground">
              The current authenticated authority may view this scope but cannot submit it.
            </p>
          )}
        </CardContent>
      </Card>

      <ClarificationResponses month={month} />
      <EvidenceTimeline events={month.timeline ?? []} />
    </form>
  );
}

function EvidenceSelector({
  id,
  label,
  choices,
  selected,
  disabled,
  onChange,
}: {
  id: string;
  label: string;
  choices: SafeEvidenceReference[];
  selected: string[];
  disabled: boolean;
  onChange: (selected: string[]) => void;
}) {
  const helpId = `${id}-help`;
  return (
    <div className="mt-3">
      <Label htmlFor={id}>{label}</Label>
      {choices.length === 0 ? (
        <p id={helpId} className="mt-1 text-xs text-muted-foreground">
          No authorized scan-passed evidence choices are available for this scope. Artifact access
          remains unavailable until the server returns safe metadata.
        </p>
      ) : (
        <>
          <select
            id={id}
            multiple
            className="mt-1 min-h-20 w-full rounded-md border bg-background px-3 py-2 text-sm"
            value={selected}
            disabled={disabled}
            aria-describedby={helpId}
            onChange={(event) =>
              onChange([...event.currentTarget.selectedOptions].map((option) => option.value))
            }
          >
            {choices.map((reference) => (
              <option key={reference.id} value={reference.id}>
                {reference.displayName} · scan {reference.scanStatus.toLowerCase()}
              </option>
            ))}
          </select>
          <p id={helpId} className="mt-1 text-xs text-muted-foreground">
            Select only server-authorized safe references. The page receives no artifact bytes,
            locator, signed URL, MIME, object key, or provider credential.
          </p>
        </>
      )}
    </div>
  );
}

function draftFromMonth(month: MonthCertificationView) {
  const existingItems = new Map(
    (month.submission?.items ?? []).map((item) => [item.deliverableId, item]),
  );
  return {
    summary: month.submission?.summary ?? "",
    declarationAccepted: month.submission?.declarationAccepted ?? false,
    items: month.deliverables.map((deliverable): SubmissionItemInput => {
      const existing = existingItems.get(deliverable.id);
      return {
        deliverableId: deliverable.id,
        outcome: existing?.outcome ?? "COMPLETED",
        completionPercentage: existing?.completionPercentage ?? 0,
        completionDate: existing?.completionDate ?? undefined,
        summary: existing?.summary ?? "",
        varianceCause: existing?.varianceCause ?? undefined,
        varianceImpact: existing?.varianceImpact ?? undefined,
        nextAction: existing?.nextAction ?? undefined,
        carryForwardProposal: existing?.carryForwardProposal ?? undefined,
        criterionResponses: deliverable.criteria.map((criterion) => {
          const response = existing?.criterionResponses.find(
            (candidate) => candidate.criterionId === criterion.id,
          );
          return {
            criterionId: criterion.id,
            response: response?.response ?? "",
            evidenceReferenceIds:
              response?.evidenceReferences.map((reference) => reference.id) ?? [],
          };
        }),
        evidenceReferenceIds: existing?.evidenceReferences.map((reference) => reference.id) ?? [],
      };
    }),
  };
}

function ClarificationResponses({ month }: { month: MonthCertificationView }) {
  const open = (month.clarifications ?? []).filter(
    (clarification) => clarification.status === "OPEN",
  );
  const [responses, setResponses] = useState<Record<string, string>>({});
  const reconciledVersion = useRef(`${month.version}:${month.submission?.version ?? "none"}`);
  const mutation = useClarification(month.monthId, month.submission?.id ?? "");

  useEffect(() => {
    const versionKey = `${month.version}:${month.submission?.version ?? "none"}`;
    if (reconciledVersion.current === versionKey) return;
    reconciledVersion.current = versionKey;
    setResponses({});
  }, [month.submission?.version, month.version]);

  if (open.length === 0) return null;
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Clarification responses</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {open.map((clarification) => (
          <div key={clarification.id} className="rounded-md border p-4">
            <p className="font-medium">Round {clarification.round}</p>
            <ul className="mt-2 list-disc pl-5 text-sm">
              {clarification.questions.map((question) => (
                <li key={question}>{question}</li>
              ))}
            </ul>
            <Label className="mt-3 block" htmlFor={`response-${clarification.id}`}>
              Additive response
            </Label>
            <Textarea
              id={`response-${clarification.id}`}
              className="mt-1"
              value={responses[clarification.id] ?? ""}
              onChange={(event) =>
                setResponses((current) => ({
                  ...current,
                  [clarification.id]: event.target.value,
                }))
              }
              disabled={!month.permissions.canRespondToClarification}
            />
            <Button
              className="mt-2"
              type="button"
              variant="outline"
              disabled={
                !month.permissions.canRespondToClarification ||
                !(responses[clarification.id] ?? "").trim() ||
                mutation.isPending ||
                !month.submission
              }
              onClick={() => {
                if (!month.submission) return;
                mutation.mutate(
                  {
                    expectedSubmissionVersion: month.submission.version,
                    deliverableId: clarification.deliverableId,
                    clarificationId: clarification.id,
                    response: responses[clarification.id],
                  },
                  {
                    onSuccess: () =>
                      setResponses((current) => ({
                        ...current,
                        [clarification.id]: "",
                      })),
                  },
                );
              }}
            >
              Record response
            </Button>
          </div>
        ))}
        <CertificationMutationError error={mutation.error} />
      </CardContent>
    </Card>
  );
}

function TextField({
  id,
  label,
  value,
  disabled,
  onChange,
}: {
  id: string;
  label: string;
  value: string;
  disabled: boolean;
  onChange: (value: string) => void;
}) {
  return (
    <div>
      <Label htmlFor={id}>{label}</Label>
      <Textarea
        id={id}
        className="mt-1"
        value={value}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
      />
    </div>
  );
}
