import { Link, createFileRoute, useNavigate } from "@tanstack/react-router";
import { AlertTriangle, MailCheck, RotateCcw, Send } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  AuthorityNotice,
  EmptyState,
  Field,
  NotificationHistory,
  SafeActionLink,
  ValidationSummary,
  VersionNotice,
  type ValidationError,
} from "@/features/certification/components";
import type {
  InboundMessageReviewRequest,
  InboundReviewView,
  ManualEvidenceReviewRequest,
  MonthCertificationView,
  ReadinessView,
  ReopenRequestInput,
} from "@/features/certification/contracts";
import {
  browserTimeZoneLabel,
  formatDateTime,
  formatElapsedSeconds,
  formatLabel,
  localDateTimeToInstant,
  toDateTimeLocalValue,
} from "@/features/certification/formatting";
import {
  useCertificationMonth,
  useEvidenceReview,
  useReadiness,
  useRequestConfirmation,
  useRequestReopen,
} from "@/features/certification/hooks";
import { readinessTone } from "@/features/certification/presentation";
import {
  CertificationMutationError,
  CertificationQueryBoundary,
} from "@/features/certification/query-boundary";
import { focusValidationSummary } from "@/features/certification/validation";

export const Route = createFileRoute("/confirmation/$monthId")({
  head: () => ({ meta: [{ title: "Readiness and confirmation — Cadence" }] }),
  component: ConfirmationGovernancePage,
});

function ConfirmationGovernancePage() {
  const { monthId } = Route.useParams();
  const monthQuery = useCertificationMonth(monthId);
  const readinessQuery = useReadiness(monthId);
  return (
    <div>
      <PageHeader
        title={
          monthQuery.data
            ? `${monthQuery.data.monthLabel} confirmation control`
            : "Confirmation control"
        }
        description="Review all five server-evaluated pillars, exact request scope, communication state, and correction impact."
      >
        <Link
          to="/certification/$monthId"
          params={{ monthId }}
          className="rounded-md border px-3 py-2 text-sm font-medium hover:bg-muted"
        >
          Vendor submission
        </Link>
        <Link
          to="/certification/$monthId/review"
          params={{ monthId }}
          className="rounded-md border px-3 py-2 text-sm font-medium hover:bg-muted"
        >
          Certification review
        </Link>
      </PageHeader>
      <CertificationQueryBoundary queries={[monthQuery, readinessQuery]}>
        {monthQuery.data && readinessQuery.data && (
          <GovernanceWorkspace month={monthQuery.data} readiness={readinessQuery.data} />
        )}
      </CertificationQueryBoundary>
    </div>
  );
}

function GovernanceWorkspace({
  month,
  readiness,
}: {
  month: MonthCertificationView;
  readiness: ReadinessView;
}) {
  return (
    <div className="space-y-6 p-6">
      <AuthorityNotice />
      <VersionNotice
        version={month.version}
        stale={month.stale || readiness.stale}
        locked={month.locked}
        updatedAt={readiness.evaluatedAt}
      />
      <ReadinessPanel readiness={readiness} />
      <ConfirmationPreview month={month} readiness={readiness} />
      <ConfirmationHistory month={month} />
      <InboundReviewPanel month={month} />
      <NotificationHistory notifications={month.notifications ?? []} />
      <ReopenPanel month={month} />
    </div>
  );
}

function ReadinessPanel({ readiness }: { readiness: ReadinessView }) {
  return (
    <section aria-labelledby="readiness-title" className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 id="readiness-title" className="text-lg font-semibold">
            Five-pillar readiness
          </h2>
          <p className="text-sm text-muted-foreground">
            Evaluation v{readiness.version} · manifest {readiness.inputManifestVersion} ·{" "}
            {formatDateTime(readiness.evaluatedAt)}
          </p>
        </div>
        <div className="flex gap-2">
          <StatusBadge status={readiness.status} />
          <StatusBadge status={readiness.f05HandoffStatus} />
        </div>
      </div>
      <div className="grid gap-4 lg:grid-cols-2 xl:grid-cols-3">
        {(readiness.pillars ?? []).map((pillar) => (
          <Card key={pillar.key} className={readinessTone(pillar.status)}>
            <CardHeader>
              <div className="flex items-center justify-between gap-2">
                <CardTitle className="text-base">{pillar.label}</CardTitle>
                <StatusBadge status={pillar.status} />
              </div>
            </CardHeader>
            <CardContent className="space-y-3">
              <dl className="grid gap-3 sm:grid-cols-2">
                <Field label="Source version" value={pillar.sourceVersionId} mono />
                <Field label="Freshness" value={pillar.freshness} />
              </dl>
              {pillar.blockers.length === 0 ? (
                <p className="text-sm text-muted-foreground">
                  No blocker was returned for this pillar.
                </p>
              ) : (
                <ul className="space-y-2">
                  {pillar.blockers.map((blocker) => (
                    <li
                      key={blocker.code}
                      className="rounded-md border bg-background/70 p-3 text-sm"
                    >
                      <div className="flex flex-wrap gap-2">
                        <StatusBadge status={blocker.severity} />
                        <span className="font-medium">{blocker.message}</span>
                      </div>
                      <p className="mt-1 text-xs text-muted-foreground">
                        Owner: {blocker.owner} ·{" "}
                        <SafeActionLink path={blocker.actionPath} label={blocker.actionLabel} />
                      </p>
                    </li>
                  ))}
                </ul>
              )}
            </CardContent>
          </Card>
        ))}
      </div>
      {readiness.stale && (
        <p className="flex items-center gap-2 text-sm text-warning-foreground" role="alert">
          <AlertTriangle className="h-4 w-4" aria-hidden="true" />
          Inputs changed after this evaluation. Re-evaluation is required before confirmation.
        </p>
      )}
      <p className="text-xs text-muted-foreground">
        Eligible handoff is a versioned F04 fact only. This workflow does not create an invoice,
        procurement package, or business approval.
      </p>
    </section>
  );
}

function ConfirmationPreview({
  month,
  readiness,
}: {
  month: MonthCertificationView;
  readiness: ReadinessView;
}) {
  const navigate = useNavigate();
  const preview = month.confirmationPreview;
  const [dueAt, setDueAt] = useState(preview ? toDateTimeLocalValue(preview.defaultDueAt) : "");
  const [validationErrors, setValidationErrors] = useState<ValidationError[]>([]);
  const errorSummaryRef = useRef<HTMLDivElement>(null);
  const reconciledVersion = useRef(`${month.version}:${preview?.defaultDueAt ?? "unavailable"}`);
  const request = useRequestConfirmation(month.monthId);

  useEffect(() => {
    const versionKey = `${month.version}:${preview?.defaultDueAt ?? "unavailable"}`;
    if (reconciledVersion.current === versionKey) return;
    reconciledVersion.current = versionKey;
    setDueAt(preview ? toDateTimeLocalValue(preview.defaultDueAt) : "");
    setValidationErrors([]);
  }, [month.version, preview]);

  function createRequest() {
    const dueInstant = localDateTimeToInstant(dueAt);
    const errors: ValidationError[] = [];
    if (!dueInstant) {
      errors.push({
        fieldId: "confirmation-due-at",
        message: "Enter a valid confirmation due date and time.",
      });
    } else if (new Date(dueInstant).getTime() <= Date.now()) {
      errors.push({
        fieldId: "confirmation-due-at",
        message: "Confirmation due date and time must be in the future.",
      });
    }
    setValidationErrors(errors);
    if (errors.length > 0 || !dueInstant) {
      focusValidationSummary(errorSummaryRef);
      return;
    }
    request.mutate(
      {
        expectedMonthVersion: month.version,
        dueAt: dueInstant,
      },
      {
        onSuccess: (created) =>
          navigate({
            to: "/confirmation/requests/$requestId",
            params: { requestId: created.id },
          }),
      },
    );
  }

  const dueInvalid = validationErrors.some((error) => error.fieldId === "confirmation-due-at");
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Exact confirmation request preview</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {!preview ? (
          <EmptyState
            title="No request preview available"
            detail="The server has not resolved an exact recipient, eligible-confirmer, quorum, and source-version snapshot."
          />
        ) : (
          <>
            <div className="grid gap-4 lg:grid-cols-3">
              <RecipientColumn title="To" values={preview.toRecipients} />
              <RecipientColumn title="CC" values={preview.ccRecipients} />
              <RecipientColumn title="Eligible confirmers" values={preview.eligibleConfirmers} />
            </div>
            <dl className="grid gap-4 md:grid-cols-2">
              <Field label="Quorum" value={preview.quorumDescription} />
              <Field label="Source versions" value={preview.sourceVersionIds.join(", ")} mono />
            </dl>
            {preview.blockers.length > 0 && (
              <div
                className="rounded-md border border-destructive/30 bg-destructive/5 p-3"
                role="alert"
              >
                <p className="font-medium">Preview blockers</p>
                <ul className="mt-2 list-disc pl-5 text-sm">
                  {preview.blockers.map((blocker) => (
                    <li key={blocker}>{blocker}</li>
                  ))}
                </ul>
              </div>
            )}
            <div className="max-w-sm">
              <Label htmlFor="confirmation-due-at">Due date and time</Label>
              <Input
                id="confirmation-due-at"
                className="mt-1"
                type="datetime-local"
                value={dueAt}
                onChange={(event) => {
                  setDueAt(event.target.value);
                  setValidationErrors([]);
                }}
                disabled={!month.permissions.canRequestConfirmation}
                aria-invalid={dueInvalid}
                aria-describedby="confirmation-due-help"
              />
              <p id="confirmation-due-help" className="mt-1 text-xs text-muted-foreground">
                Displayed in {browserTimeZoneLabel()}; the same instant is submitted with an
                explicit UTC offset.
              </p>
            </div>
            <ValidationSummary
              id="confirmation-request-errors"
              title="Confirmation request errors"
              errors={validationErrors}
              summaryRef={errorSummaryRef}
            />
            <CertificationMutationError error={request.error} />
            <Button
              type="button"
              disabled={
                !preview.ready ||
                readiness.status !== "READY" ||
                readiness.stale ||
                month.stale ||
                !month.permissions.canRequestConfirmation ||
                request.isPending
              }
              onClick={createRequest}
            >
              <Send className="mr-2 h-4 w-4" aria-hidden="true" />
              Create and queue exact request
            </Button>
            <p className="text-xs text-muted-foreground">
              Recipient categories, eligibility, quorum, and final rendered communication are
              resolved and archived by the server. Browser-provided authority is ignored.
            </p>
          </>
        )}
      </CardContent>
    </Card>
  );
}

function ConfirmationHistory({ month }: { month: MonthCertificationView }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Confirmation version lineage</CardTitle>
      </CardHeader>
      <CardContent>
        {(month.confirmationHistory ?? []).length === 0 ? (
          <EmptyState
            title="No confirmation requests"
            detail="Readiness and certification do not silently create a request."
          />
        ) : (
          <ol className="space-y-3">
            {month.confirmationHistory.map((request) => (
              <li
                key={request.id}
                className="flex flex-wrap items-center justify-between gap-3 rounded-md border p-3 text-sm"
              >
                <div>
                  <Link
                    to="/confirmation/requests/$requestId"
                    params={{ requestId: request.id }}
                    className="font-medium text-primary hover:underline"
                  >
                    Request v{request.version}
                  </Link>
                  <p className="text-xs text-muted-foreground">
                    Due {formatDateTime(request.dueAt)}
                    {request.supersedesRequestId
                      ? ` · supersedes ${request.supersedesRequestId}`
                      : ""}
                  </p>
                </div>
                <StatusBadge status={request.state} />
              </li>
            ))}
          </ol>
        )}
      </CardContent>
    </Card>
  );
}

function InboundReviewPanel({ month }: { month: MonthCertificationView }) {
  const reviews = month.inboundReviews;
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <MailCheck className="h-4 w-4" aria-hidden="true" />
          Restricted inbound and manual-evidence review
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {!month.permissions.canReviewInbound ? (
          <p className="text-sm text-muted-foreground">
            Restricted metadata is unavailable to the current authority. Raw messages, MIME,
            headers, and artifacts are never rendered here.
          </p>
        ) : reviews.length === 0 ? (
          <EmptyState
            title="No inbound review items"
            detail="Ambiguous, spoofed, forwarded, receipt, and manual evidence remain non-confirming until a separate authorized server workflow records a decision."
          />
        ) : (
          reviews.map((review) => (
            <EvidenceReviewItem
              key={review.id}
              monthId={month.monthId}
              review={review}
              canReview={month.permissions.canReviewInbound}
            />
          ))
        )}
        <p className="text-xs text-muted-foreground">
          Classifier suggestions, authentication gaps, and manual evidence cannot autonomously
          confirm. This safe list intentionally excludes raw restricted content.
        </p>
      </CardContent>
    </Card>
  );
}

type EvidenceReviewDecision =
  InboundMessageReviewRequest["decision"] | ManualEvidenceReviewRequest["decision"];

function EvidenceReviewItem({
  monthId,
  review,
  canReview,
}: {
  monthId: string;
  review: InboundReviewView;
  canReview: boolean;
}) {
  const [reasoning, setReasoning] = useState("");
  const [validationErrors, setValidationErrors] = useState<ValidationError[]>([]);
  const errorSummaryRef = useRef<HTMLDivElement>(null);
  const reconciledVersion = useRef(review.version);
  const mutation = useEvidenceReview(monthId);
  const reasonId = `evidence-review-reason-${review.id}`;
  const summaryId = `evidence-review-errors-${review.id}`;
  const reasonError = validationErrors.find((error) => error.fieldId === reasonId)?.message;
  const actionable =
    canReview && review.assignedToCurrentActor && review.reviewStatus === "PENDING";
  const actions: Array<{ decision: EvidenceReviewDecision; label: string }> =
    review.reviewKind === "INBOUND_MESSAGE"
      ? [
          { decision: "ACCEPT_INTERPRETATION", label: "Accept inbound interpretation" },
          { decision: "REJECT_INTERPRETATION", label: "Reject inbound interpretation" },
          { decision: "QUARANTINE", label: "Quarantine inbound item" },
        ]
      : [
          { decision: "APPROVE", label: "Approve manual evidence" },
          { decision: "REJECT", label: "Reject manual evidence" },
        ];

  useEffect(() => {
    if (reconciledVersion.current === review.version) return;
    reconciledVersion.current = review.version;
    setReasoning("");
    setValidationErrors([]);
  }, [review.version]);

  function recordReview(decision: EvidenceReviewDecision) {
    if (!reasoning.trim()) {
      setValidationErrors([
        {
          fieldId: reasonId,
          message: "Reviewer reasoning is required for this immutable decision.",
        },
      ]);
      focusValidationSummary(errorSummaryRef);
      return;
    }
    setValidationErrors([]);
    if (review.reviewKind === "INBOUND_MESSAGE") {
      mutation.mutate({
        reviewKind: "INBOUND_MESSAGE",
        reviewId: review.id,
        request: {
          expectedReviewVersion: review.version,
          decision: decision as InboundMessageReviewRequest["decision"],
          reasoning,
        },
      });
      return;
    }
    mutation.mutate({
      reviewKind: "MANUAL_EVIDENCE",
      reviewId: review.id,
      request: {
        expectedReviewVersion: review.version,
        decision: decision as ManualEvidenceReviewRequest["decision"],
        reasoning,
      },
    });
  }

  return (
    <section
      className="min-w-0 rounded-md border p-3 text-sm"
      aria-labelledby={`evidence-review-title-${review.id}`}
    >
      <div className="flex flex-wrap gap-2">
        <StatusBadge status={review.reviewKind} />
        <StatusBadge status={review.source} />
        <StatusBadge status={review.authenticationConfidence} />
        <StatusBadge status={review.reviewStatus} />
        <StatusBadge status={review.agingStatus} />
      </div>
      <h3 id={`evidence-review-title-${review.id}`} className="mt-2 font-medium">
        {review.safeSummary}
      </h3>
      <p className="mt-1 text-xs text-muted-foreground">
        Sender eligibility: {formatLabel(review.senderEligibility)} · recorded{" "}
        {formatDateTime(review.recordedAt)}
        {review.representedAt ? ` · represented ${formatDateTime(review.representedAt)}` : ""}
      </p>
      <p className="mt-1 text-xs text-muted-foreground">
        Review age: {formatElapsedSeconds(review.ageSeconds)} · {review.assignmentReason}
      </p>
      {review.reason && <p className="mt-1 text-xs">{review.reason}</p>}
      {review.auditReference && (
        <p className="mt-1 break-all font-mono text-xs text-muted-foreground">
          Audit reference {review.auditReference}
        </p>
      )}

      {actionable ? (
        <div className="mt-3 space-y-3 border-t pt-3">
          <div>
            <Label htmlFor={reasonId}>
              {review.reviewKind === "INBOUND_MESSAGE"
                ? "Inbound review reason"
                : "Manual evidence review reason"}
            </Label>
            <Textarea
              id={reasonId}
              className="mt-1"
              value={reasoning}
              onChange={(event) => {
                setReasoning(event.target.value);
                setValidationErrors([]);
              }}
              aria-invalid={Boolean(reasonError)}
              aria-describedby={reasonError ? `${reasonId}-error` : undefined}
            />
            {reasonError && (
              <p id={`${reasonId}-error`} className="mt-1 text-xs text-destructive">
                {reasonError}
              </p>
            )}
          </div>
          <ValidationSummary
            id={summaryId}
            title="Evidence review errors"
            errors={validationErrors}
            summaryRef={errorSummaryRef}
          />
          <CertificationMutationError error={mutation.error} />
          <div className="flex flex-wrap gap-2">
            {actions.map((action) => (
              <Button
                key={action.decision}
                type="button"
                variant={action.decision === "QUARANTINE" ? "destructive" : "outline"}
                disabled={mutation.isPending}
                onClick={() => recordReview(action.decision)}
              >
                {action.label}
              </Button>
            ))}
          </div>
        </div>
      ) : (
        <p className="mt-3 border-t pt-3 text-xs text-muted-foreground">
          {review.reviewStatus !== "PENDING"
            ? "This immutable review already has a terminal status."
            : "The server did not assign this restricted review to the current actor."}
        </p>
      )}
    </section>
  );
}

function ReopenPanel({ month }: { month: MonthCertificationView }) {
  const [category, setCategory] = useState<ReopenRequestInput["category"]>(
    "CERTIFICATION_CORRECTION",
  );
  const [reason, setReason] = useState("");
  const [impacted, setImpacted] = useState("");
  const [packageImpact, setPackageImpact] = useState("");
  const [risk, setRisk] = useState("");
  const [validationErrors, setValidationErrors] = useState<ValidationError[]>([]);
  const errorSummaryRef = useRef<HTMLDivElement>(null);
  const reconciledVersion = useRef(`${month.monthId}:${month.version}`);
  const mutation = useRequestReopen(month.monthId);

  useEffect(() => {
    const versionKey = `${month.monthId}:${month.version}`;
    if (reconciledVersion.current === versionKey) return;
    reconciledVersion.current = versionKey;
    setCategory("CERTIFICATION_CORRECTION");
    setReason("");
    setImpacted("");
    setPackageImpact("");
    setRisk("");
    setValidationErrors([]);
  }, [month.monthId, month.version]);

  function requestReopen() {
    const errors: ValidationError[] = [];
    if (!reason.trim()) {
      errors.push({ fieldId: "reopen-reason", message: "Reopen reason is required." });
    }
    const impactedIds = impacted
      .split(",")
      .map((value) => value.trim())
      .filter(Boolean);
    if (impactedIds.length === 0) {
      errors.push({
        fieldId: "reopen-impacted",
        message: "At least one impacted record ID is required.",
      });
    } else if (impactedIds.some((value) => !isUuid(value))) {
      errors.push({
        fieldId: "reopen-impacted",
        message: "Every impacted record ID must be a UUID.",
      });
    }
    if (!packageImpact.trim()) {
      errors.push({
        fieldId: "reopen-package-impact",
        message: "Package and invoice impact is required.",
      });
    }
    if (!risk.trim()) {
      errors.push({ fieldId: "reopen-risk", message: "Risk statement is required." });
    }
    setValidationErrors(errors);
    if (errors.length > 0) {
      focusValidationSummary(errorSummaryRef);
      return;
    }
    mutation.mutate({
      expectedMonthVersion: month.version,
      category,
      reason,
      impactedRecordIds: impactedIds,
      packageInvoiceImpact: packageImpact,
      riskStatement: risk,
    });
  }

  const errorFor = (fieldId: string) =>
    validationErrors.find((error) => error.fieldId === fieldId)?.message;
  const clearError = (fieldId: string) =>
    setValidationErrors((current) => current.filter((error) => error.fieldId !== fieldId));
  return (
    <Card className="border-warning/40">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <RotateCcw className="h-4 w-4" aria-hidden="true" />
          Governed reopen impact
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <p className="text-sm text-muted-foreground">
          A request preserves prior evidence and proposes selective invalidation. It does not edit,
          delete, or backdate the prior submission, certification, confirmation, or handoff.
        </p>
        <div className="grid gap-4 md:grid-cols-2">
          <div>
            <Label htmlFor="reopen-category">Correction category</Label>
            <select
              id="reopen-category"
              className="mt-1 h-9 w-full rounded-md border bg-background px-3 text-sm"
              value={category}
              onChange={(event) =>
                setCategory(event.target.value as ReopenRequestInput["category"])
              }
              disabled={!month.permissions.canReopen}
            >
              <option value="ATTENDANCE_CORRECTION">Attendance correction</option>
              <option value="CERTIFICATION_CORRECTION">Certification correction</option>
              <option value="PLAN_CORRECTION">Plan correction</option>
              <option value="OTHER">Other governed correction</option>
            </select>
          </div>
          <ReopenText
            id="reopen-reason"
            label="Reason"
            value={reason}
            onChange={(value) => {
              setReason(value);
              clearError("reopen-reason");
            }}
            disabled={!month.permissions.canReopen}
            error={errorFor("reopen-reason")}
          />
          <ReopenText
            id="reopen-impacted"
            label="Impacted record IDs (comma separated)"
            value={impacted}
            onChange={(value) => {
              setImpacted(value);
              clearError("reopen-impacted");
            }}
            disabled={!month.permissions.canReopen}
            error={errorFor("reopen-impacted")}
          />
          <ReopenText
            id="reopen-package-impact"
            label="Package / invoice impact"
            value={packageImpact}
            onChange={(value) => {
              setPackageImpact(value);
              clearError("reopen-package-impact");
            }}
            disabled={!month.permissions.canReopen}
            error={errorFor("reopen-package-impact")}
          />
          <ReopenText
            id="reopen-risk"
            label="Risk statement"
            value={risk}
            onChange={(value) => {
              setRisk(value);
              clearError("reopen-risk");
            }}
            disabled={!month.permissions.canReopen}
            error={errorFor("reopen-risk")}
          />
        </div>
        <ValidationSummary
          id="reopen-errors"
          title="Reopen request errors"
          errors={validationErrors}
          summaryRef={errorSummaryRef}
        />
        <CertificationMutationError error={mutation.error} />
        <Button
          type="button"
          variant="destructive"
          disabled={!month.permissions.canReopen || month.stale || mutation.isPending}
          onClick={requestReopen}
        >
          Request governed reopen
        </Button>
      </CardContent>
    </Card>
  );
}

function RecipientColumn({
  title,
  values,
}: {
  title: string;
  values: Array<{ display: string; roleReason: string }>;
}) {
  return (
    <div className="rounded-md border p-3">
      <h3 className="text-sm font-semibold">{title}</h3>
      {values.length === 0 ? (
        <p className="mt-2 text-xs text-destructive">Required category unresolved</p>
      ) : (
        <ul className="mt-2 space-y-2 text-sm">
          {values.map((recipient) => (
            <li key={`${recipient.display}-${recipient.roleReason}`}>
              <span>{recipient.display}</span>
              <span className="block text-xs text-muted-foreground">{recipient.roleReason}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function ReopenText({
  id,
  label,
  value,
  onChange,
  disabled,
  error,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  disabled: boolean;
  error?: string;
}) {
  return (
    <div>
      <Label htmlFor={id}>{label}</Label>
      <Textarea
        id={id}
        className="mt-1"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        disabled={disabled}
        aria-invalid={Boolean(error)}
        aria-describedby={error ? `${id}-error` : undefined}
      />
      {error && (
        <p id={`${id}-error`} className="mt-1 text-xs text-destructive">
          {error}
        </p>
      )}
    </div>
  );
}

function isUuid(value: string) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}
