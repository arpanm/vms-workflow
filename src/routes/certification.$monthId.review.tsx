import { Link, createFileRoute } from "@tanstack/react-router";
import { MessageCircleQuestion, ShieldCheck } from "lucide-react";
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
  Field,
  NotificationHistory,
  ValidationSummary,
  VersionNotice,
  type ValidationError,
} from "@/features/certification/components";
import type {
  CertificationDecision,
  CriterionDecision,
  DeliverableCertificationView,
  MonthCertificationView,
  MonthlyDecision,
} from "@/features/certification/contracts";
import { formatDateTime, formatElapsedSeconds } from "@/features/certification/formatting";
import {
  useCertificationDecision,
  useCertificationMonth,
  useClarification,
  useCreateSummary,
} from "@/features/certification/hooks";
import {
  certificationDecisionOptions,
  certificationRequiresComment,
  certificationRequiresObservations,
  criterionDecisionOptions,
} from "@/features/certification/presentation";
import {
  CertificationMutationError,
  CertificationQueryBoundary,
} from "@/features/certification/query-boundary";
import { focusValidationSummary } from "@/features/certification/validation";

export const Route = createFileRoute("/certification/$monthId/review")({
  head: () => ({ meta: [{ title: "Product-owner certification — Cadence" }] }),
  component: CertificationReviewPage,
});

function CertificationReviewPage() {
  const { monthId } = Route.useParams();
  const query = useCertificationMonth(monthId);
  return (
    <div>
      <PageHeader
        title={
          query.data ? `${query.data.monthLabel} certification review` : "Certification review"
        }
        description="Compare frozen scope, vendor claims, criteria, and supporting evidence before recording an independent product-owner decision."
      >
        <Link
          to="/certification/$monthId"
          params={{ monthId }}
          className="rounded-md border px-3 py-2 text-sm font-medium hover:bg-muted"
        >
          Vendor submission
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
        {query.data && <ReviewWorkspace month={query.data} />}
      </CertificationQueryBoundary>
    </div>
  );
}

function ReviewWorkspace({ month }: { month: MonthCertificationView }) {
  const assignedDeliverables = month.deliverables.filter(
    (deliverable) => deliverable.assignedToCurrentActor,
  );
  const terminal = month.deliverables.filter(
    (deliverable) => deliverable.certification?.terminal,
  ).length;
  const assignedTerminal = assignedDeliverables.filter(
    (deliverable) => deliverable.certification?.terminal,
  ).length;
  return (
    <div className="space-y-6 p-6">
      <AuthorityNotice />
      <VersionNotice
        version={month.version}
        stale={month.stale}
        locked={month.locked}
        updatedAt={month.lastEvaluatedAt}
      />
      <div className="grid gap-4 md:grid-cols-4">
        <Metric label="Assigned items" value={assignedDeliverables.length} />
        <Metric label="Assigned terminal decisions" value={assignedTerminal} />
        <Metric
          label="Open clarifications"
          value={month.clarifications.filter((item) => item.status === "OPEN").length}
        />
        <Metric label="Submission" value={month.submission?.status ?? "NOT_SUBMITTED"} />
      </div>

      {!month.submission ? (
        <EmptyState
          title="No submitted delivery to review"
          detail="A product-owner decision cannot be inferred from the frozen plan or Linear state."
        />
      ) : assignedDeliverables.length === 0 ? (
        <EmptyState
          title="No assigned certification items"
          detail="The server returned no frozen product-owner assignment for the current actor. Browser roles and cached project state are not used to infer one."
        />
      ) : (
        <section className="space-y-4" aria-labelledby="assigned-review-title">
          <h2 id="assigned-review-title" className="text-lg font-semibold">
            Assigned certification inbox
          </h2>
          {assignedDeliverables.map((deliverable) => (
            <DeliverableReview key={deliverable.id} month={month} deliverable={deliverable} />
          ))}
        </section>
      )}

      <MonthlySummary month={month} terminal={terminal} />
      <NotificationHistory notifications={month.notifications ?? []} />
    </div>
  );
}

function DeliverableReview({
  month,
  deliverable,
}: {
  month: MonthCertificationView;
  deliverable: DeliverableCertificationView;
}) {
  const submission = deliverable.vendorSubmission;
  const [decision, setDecision] = useState<CertificationDecision>(
    deliverable.certification?.decision ?? "ACCEPTED",
  );
  const [comment, setComment] = useState(deliverable.certification?.comment ?? "");
  const [observations, setObservations] = useState(deliverable.certification?.observations ?? "");
  const [cause, setCause] = useState(deliverable.certification?.cause ?? "");
  const [nextAction, setNextAction] = useState(deliverable.certification?.nextAction ?? "");
  const [acceptedScope, setAcceptedScope] = useState(
    deliverable.certification?.acceptedScope ?? "",
  );
  const [rejectedScope, setRejectedScope] = useState(
    deliverable.certification?.rejectedScope ?? "",
  );
  const [carryForward, setCarryForward] = useState(deliverable.certification?.carryForward ?? "");
  const [criterionResults, setCriterionResults] = useState(() =>
    deliverable.criteria.map((criterion) => {
      const current = deliverable.certification?.criterionResults.find(
        (result) => result.criterionId === criterion.id,
      );
      return {
        criterionId: criterion.id,
        decision: current?.decision ?? ("MET" as CriterionDecision),
        rationale: current?.rationale ?? "",
        evidenceViewed: current?.evidenceViewed ?? false,
      };
    }),
  );
  const [questions, setQuestions] = useState("");
  const [validationErrors, setValidationErrors] = useState<ValidationError[]>([]);
  const errorSummaryRef = useRef<HTMLDivElement>(null);
  const [clarificationErrors, setClarificationErrors] = useState<ValidationError[]>([]);
  const clarificationErrorRef = useRef<HTMLDivElement>(null);
  const reconciledVersion = useRef(
    `${month.version}:${deliverable.certification?.version ?? "unreviewed"}`,
  );
  const decide = useCertificationDecision(month.monthId, month.submission?.id ?? "");
  const clarify = useClarification(month.monthId, month.submission?.id ?? "");
  const readOnly =
    month.stale ||
    month.locked ||
    !month.permissions.canCertify ||
    Boolean(deliverable.certification?.terminal);

  useEffect(() => {
    const versionKey = `${month.version}:${deliverable.certification?.version ?? "unreviewed"}`;
    if (reconciledVersion.current === versionKey) return;
    reconciledVersion.current = versionKey;
    setDecision(deliverable.certification?.decision ?? "ACCEPTED");
    setComment(deliverable.certification?.comment ?? "");
    setObservations(deliverable.certification?.observations ?? "");
    setCause(deliverable.certification?.cause ?? "");
    setNextAction(deliverable.certification?.nextAction ?? "");
    setAcceptedScope(deliverable.certification?.acceptedScope ?? "");
    setRejectedScope(deliverable.certification?.rejectedScope ?? "");
    setCarryForward(deliverable.certification?.carryForward ?? "");
    setCriterionResults(
      deliverable.criteria.map((criterion) => {
        const current = deliverable.certification?.criterionResults.find(
          (result) => result.criterionId === criterion.id,
        );
        return {
          criterionId: criterion.id,
          decision: current?.decision ?? ("MET" as CriterionDecision),
          rationale: current?.rationale ?? "",
          evidenceViewed: current?.evidenceViewed ?? false,
        };
      }),
    );
    setQuestions("");
    setValidationErrors([]);
    setClarificationErrors([]);
  }, [deliverable, month.version]);

  function recordDecision() {
    if (!month.submission) return;
    const errors: ValidationError[] = [];
    if (certificationRequiresComment(decision) && !comment.trim()) {
      errors.push({
        fieldId: `decision-comment-${deliverable.id}`,
        message: "Decision comment is required for this decision.",
      });
    }
    if (certificationRequiresObservations(decision) && !observations.trim()) {
      errors.push({
        fieldId: `observations-${deliverable.id}`,
        message: "Observations are required for accepted with observations.",
      });
    }
    if (certificationRequiresComment(decision) && !cause.trim()) {
      errors.push({
        fieldId: `cause-${deliverable.id}`,
        message: "Cause is required for this decision.",
      });
    }
    if (certificationRequiresComment(decision) && !nextAction.trim()) {
      errors.push({
        fieldId: `next-action-${deliverable.id}`,
        message: "Next action is required for this decision.",
      });
    }
    if (decision === "PARTIALLY_ACCEPTED" && !acceptedScope.trim()) {
      errors.push({
        fieldId: `accepted-scope-${deliverable.id}`,
        message: "Accepted scope is required for a partial decision.",
      });
    }
    if (decision === "PARTIALLY_ACCEPTED" && !rejectedScope.trim()) {
      errors.push({
        fieldId: `rejected-scope-${deliverable.id}`,
        message: "Unaccepted scope is required for a partial decision.",
      });
    }
    if (decision === "PARTIALLY_ACCEPTED" && !carryForward.trim()) {
      errors.push({
        fieldId: `carry-forward-${deliverable.id}`,
        message: "Carry-forward owner and next action are required.",
      });
    }
    criterionResults.forEach((criterion) => {
      if (!criterion.rationale.trim()) {
        errors.push({
          fieldId: `criterion-rationale-${criterion.criterionId}`,
          message: "Every criterion decision requires a rationale.",
        });
      }
    });
    setValidationErrors(errors);
    if (errors.length > 0) {
      focusValidationSummary(errorSummaryRef);
      return;
    }
    decide.mutate({
      expectedSubmissionVersion: month.submission.version,
      deliverableId: deliverable.id,
      decision,
      comment: comment || undefined,
      observations: observations || undefined,
      cause: cause || undefined,
      nextAction: nextAction || undefined,
      acceptedScope: acceptedScope || undefined,
      rejectedScope: rejectedScope || undefined,
      carryForward: carryForward || undefined,
      criterionResults,
    });
  }

  const errorFor = (fieldId: string) =>
    validationErrors.find((error) => error.fieldId === fieldId)?.message;
  const clearError = (fieldId: string) =>
    setValidationErrors((current) => current.filter((error) => error.fieldId !== fieldId));

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <CardTitle className="text-base">
              {deliverable.code} · {deliverable.title}
            </CardTitle>
            <p className="mt-1 text-xs text-muted-foreground">
              {deliverable.projectName} · baseline {deliverable.baselineVersionId}
            </p>
            <div
              className="mt-2 flex flex-wrap items-center gap-2 text-xs text-muted-foreground"
              aria-label={`Assignment for ${deliverable.code}`}
            >
              <StatusBadge status={deliverable.reviewAgingStatus} />
              <span>Assignment age: {formatElapsedSeconds(deliverable.reviewAgeSeconds)}</span>
              <span>{deliverable.assignmentReason ?? "No assignment reason returned"}</span>
            </div>
            <p className="mt-1 text-xs text-muted-foreground">
              {deliverable.reviewStartedAt
                ? `Assigned ${formatDateTime(deliverable.reviewStartedAt)}`
                : "Review has not started"}
              {deliverable.reviewDueAt ? ` · due ${formatDateTime(deliverable.reviewDueAt)}` : ""}
            </p>
          </div>
          <StatusBadge status={deliverable.certification?.decision ?? "AWAITING_DECISION"} />
        </div>
      </CardHeader>
      <CardContent className="space-y-5">
        <div className="grid gap-4 lg:grid-cols-3">
          <ContextColumn title="Frozen baseline">
            <Field label="Scope" value={deliverable.baselineDescription} />
            <Field label="Objective" value={deliverable.businessObjective} />
            <Field label="Evidence expected" value={deliverable.evidenceExpectation} />
          </ContextColumn>
          <ContextColumn title="Vendor submission">
            {submission ? (
              <>
                <Field label="Outcome" value={submission.outcome} />
                <Field label="Completion" value={`${submission.completionPercentage}%`} />
                <Field label="Vendor summary" value={submission.summary} />
                <Field label="Variance cause" value={submission.varianceCause} />
                <Field label="Next action" value={submission.nextAction} />
              </>
            ) : (
              <p className="text-sm text-muted-foreground">No vendor item submitted.</p>
            )}
          </ContextColumn>
          <ContextColumn title="Recorded product-owner decision">
            {deliverable.certification ? (
              <>
                <Field label="Decision" value={deliverable.certification.decision} />
                <Field label="Comment" value={deliverable.certification.comment} />
                <Field label="Decided by" value={deliverable.certification.decidedByDisplay} />
                <Field label="Decision time" value={deliverable.certification.decidedAt} />
              </>
            ) : (
              <p className="text-sm text-muted-foreground">
                No independent decision has been recorded.
              </p>
            )}
          </ContextColumn>
        </div>

        <div className="rounded-md border border-info/30 bg-info/5 p-3 text-sm">
          Linear plan-time, month-end, and current projections are supporting evidence only.
          Provider “Done” is never translated into acceptance.
        </div>

        <div>
          <h3 className="text-sm font-medium">Criterion-by-criterion review</h3>
          <div className="mt-2 space-y-3">
            {deliverable.criteria.map((criterion, index) => {
              const vendorResponse = submission?.criterionResponses.find(
                (response) => response.criterionId === criterion.id,
              );
              const result = criterionResults[index];
              if (!result) return null;
              return (
                <div key={criterion.id} className="grid gap-3 rounded-md border p-3 lg:grid-cols-3">
                  <div>
                    <p className="text-sm font-medium">
                      {criterion.sequence}. {criterion.statement}
                    </p>
                    <p className="mt-1 text-xs text-muted-foreground">
                      Vendor: {vendorResponse?.response || "No response"}
                    </p>
                    {(vendorResponse?.evidenceReferences ?? []).map((reference) => (
                      <p key={reference.id} className="mt-1 text-xs">
                        {reference.displayName} · {reference.scanStatus} · Artifact access
                        unavailable
                      </p>
                    ))}
                  </div>
                  <div>
                    <Label htmlFor={`criterion-decision-${criterion.id}`}>Decision</Label>
                    <select
                      id={`criterion-decision-${criterion.id}`}
                      className="mt-1 h-9 w-full rounded-md border bg-background px-3 text-sm"
                      value={result.decision}
                      disabled={readOnly}
                      onChange={(event) =>
                        setCriterionResults((current) =>
                          current.map((candidate, resultIndex) =>
                            resultIndex === index
                              ? {
                                  ...candidate,
                                  decision: event.target.value as CriterionDecision,
                                }
                              : candidate,
                          ),
                        )
                      }
                    >
                      {criterionDecisionOptions.map((option) => (
                        <option key={option.value} value={option.value}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                    <label className="mt-2 flex items-center gap-2 text-xs">
                      <Checkbox
                        checked={result.evidenceViewed}
                        disabled={readOnly}
                        onCheckedChange={(checked) =>
                          setCriterionResults((current) =>
                            current.map((candidate, resultIndex) =>
                              resultIndex === index
                                ? { ...candidate, evidenceViewed: checked === true }
                                : candidate,
                            ),
                          )
                        }
                      />
                      Evidence viewed
                    </label>
                  </div>
                  <div>
                    <Label htmlFor={`criterion-rationale-${criterion.id}`}>Rationale</Label>
                    <Textarea
                      id={`criterion-rationale-${criterion.id}`}
                      className="mt-1"
                      value={result.rationale}
                      disabled={readOnly}
                      onChange={(event) => {
                        setCriterionResults((current) =>
                          current.map((candidate, resultIndex) =>
                            resultIndex === index
                              ? { ...candidate, rationale: event.target.value }
                              : candidate,
                          ),
                        );
                        clearError(`criterion-rationale-${criterion.id}`);
                      }}
                      aria-invalid={Boolean(errorFor(`criterion-rationale-${criterion.id}`))}
                      aria-describedby={
                        errorFor(`criterion-rationale-${criterion.id}`)
                          ? `criterion-rationale-${criterion.id}-error`
                          : undefined
                      }
                    />
                    {errorFor(`criterion-rationale-${criterion.id}`) && (
                      <p
                        id={`criterion-rationale-${criterion.id}-error`}
                        className="mt-1 text-xs text-destructive"
                      >
                        {errorFor(`criterion-rationale-${criterion.id}`)}
                      </p>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        <div className="grid gap-4 md:grid-cols-2">
          <div>
            <Label htmlFor={`aggregate-${deliverable.id}`}>Aggregate decision</Label>
            <select
              id={`aggregate-${deliverable.id}`}
              className="mt-1 h-9 w-full rounded-md border bg-background px-3 text-sm"
              value={decision}
              disabled={readOnly}
              onChange={(event) => {
                setDecision(event.target.value as CertificationDecision);
                setValidationErrors([]);
              }}
            >
              {certificationDecisionOptions.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </div>
          <ReviewText
            id={`decision-comment-${deliverable.id}`}
            label={
              certificationRequiresComment(decision) ? "Decision comment (required)" : "Comment"
            }
            value={comment}
            disabled={readOnly}
            onChange={(value) => {
              setComment(value);
              clearError(`decision-comment-${deliverable.id}`);
            }}
            error={errorFor(`decision-comment-${deliverable.id}`)}
          />
          {certificationRequiresObservations(decision) && (
            <ReviewText
              id={`observations-${deliverable.id}`}
              label="Observations (required)"
              value={observations}
              disabled={readOnly}
              onChange={(value) => {
                setObservations(value);
                clearError(`observations-${deliverable.id}`);
              }}
              error={errorFor(`observations-${deliverable.id}`)}
            />
          )}
          {certificationRequiresComment(decision) && (
            <>
              <ReviewText
                id={`cause-${deliverable.id}`}
                label="Cause"
                value={cause}
                disabled={readOnly}
                onChange={(value) => {
                  setCause(value);
                  clearError(`cause-${deliverable.id}`);
                }}
                error={errorFor(`cause-${deliverable.id}`)}
              />
              <ReviewText
                id={`next-action-${deliverable.id}`}
                label="Next action"
                value={nextAction}
                disabled={readOnly}
                onChange={(value) => {
                  setNextAction(value);
                  clearError(`next-action-${deliverable.id}`);
                }}
                error={errorFor(`next-action-${deliverable.id}`)}
              />
            </>
          )}
          {decision === "PARTIALLY_ACCEPTED" && (
            <>
              <ReviewText
                id={`accepted-scope-${deliverable.id}`}
                label="Accepted scope"
                value={acceptedScope}
                disabled={readOnly}
                onChange={(value) => {
                  setAcceptedScope(value);
                  clearError(`accepted-scope-${deliverable.id}`);
                }}
                error={errorFor(`accepted-scope-${deliverable.id}`)}
              />
              <ReviewText
                id={`rejected-scope-${deliverable.id}`}
                label="Unaccepted scope"
                value={rejectedScope}
                disabled={readOnly}
                onChange={(value) => {
                  setRejectedScope(value);
                  clearError(`rejected-scope-${deliverable.id}`);
                }}
                error={errorFor(`rejected-scope-${deliverable.id}`)}
              />
              <ReviewText
                id={`carry-forward-${deliverable.id}`}
                label="Carry-forward owner and next action"
                value={carryForward}
                disabled={readOnly}
                onChange={(value) => {
                  setCarryForward(value);
                  clearError(`carry-forward-${deliverable.id}`);
                }}
                error={errorFor(`carry-forward-${deliverable.id}`)}
              />
            </>
          )}
        </div>

        <ValidationSummary
          id={`certification-decision-errors-${deliverable.id}`}
          title="Certification decision errors"
          errors={validationErrors}
          summaryRef={errorSummaryRef}
        />
        <CertificationMutationError error={decide.error ?? clarify.error} />
        <div className="flex flex-wrap gap-2">
          <Button
            type="button"
            disabled={readOnly || decide.isPending || !month.submission}
            onClick={recordDecision}
          >
            <ShieldCheck className="mr-2 h-4 w-4" aria-hidden="true" />
            Record independent decision
          </Button>
        </div>

        <div className="rounded-md border p-3">
          <Label htmlFor={`clarification-${deliverable.id}`}>
            Specific clarification questions
          </Label>
          <Input
            id={`clarification-${deliverable.id}`}
            className="mt-1"
            placeholder="Separate questions with a semicolon"
            value={questions}
            disabled={!month.permissions.canRequestClarification || month.stale}
            onChange={(event) => {
              setQuestions(event.target.value);
              setClarificationErrors([]);
            }}
            aria-invalid={clarificationErrors.length > 0}
            aria-describedby={
              clarificationErrors.length > 0 ? `clarification-errors-${deliverable.id}` : undefined
            }
          />
          <ValidationSummary
            id={`clarification-errors-${deliverable.id}`}
            title="Clarification request errors"
            errors={clarificationErrors}
            summaryRef={clarificationErrorRef}
          />
          <Button
            className="mt-2"
            type="button"
            variant="outline"
            disabled={
              !month.permissions.canRequestClarification ||
              clarify.isPending ||
              !month.submission ||
              month.stale
            }
            onClick={() => {
              if (!questions.trim()) {
                setClarificationErrors([
                  {
                    fieldId: `clarification-${deliverable.id}`,
                    message: "At least one specific clarification question is required.",
                  },
                ]);
                focusValidationSummary(clarificationErrorRef);
                return;
              }
              if (!month.submission) return;
              clarify.mutate({
                expectedSubmissionVersion: month.submission.version,
                deliverableId: deliverable.id,
                questions: questions
                  .split(";")
                  .map((question) => question.trim())
                  .filter(Boolean),
              });
            }}
          >
            <MessageCircleQuestion className="mr-2 h-4 w-4" aria-hidden="true" />
            Request more information
          </Button>
          <p className="mt-2 text-xs text-muted-foreground">
            Clarification adds a new immutable round; it cannot revise frozen baseline scope.
          </p>
        </div>
      </CardContent>
    </Card>
  );
}

function MonthlySummary({ month, terminal }: { month: MonthCertificationView; terminal: number }) {
  const [decision, setDecision] = useState<MonthlyDecision>(month.summary?.decision ?? "CERTIFIED");
  const [observations, setObservations] = useState(month.summary?.observations ?? "");
  const reconciledVersion = useRef(`${month.version}:${month.summary?.version ?? "none"}`);
  const mutation = useCreateSummary(month.monthId);
  const allTerminal = month.deliverables.length > 0 && terminal === month.deliverables.length;

  useEffect(() => {
    const versionKey = `${month.version}:${month.summary?.version ?? "none"}`;
    if (reconciledVersion.current === versionKey) return;
    reconciledVersion.current = versionKey;
    setDecision(month.summary?.decision ?? "CERTIFIED");
    setObservations(month.summary?.observations ?? "");
  }, [month.summary, month.version]);

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Monthly certification summary</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {month.summary ? (
          <dl className="grid gap-4 md:grid-cols-4">
            <Field label="Decision" value={month.summary.decision} />
            <Field label="Version" value={`v${month.summary.version}`} />
            <Field
              label="Terminal coverage"
              value={`${month.summary.terminalItemCount}/${month.summary.totalItemCount}`}
            />
            <Field label="Checksum" value={month.summary.checksum} mono />
          </dl>
        ) : (
          <p className="text-sm text-muted-foreground">
            No summary exists. Counts and percentages do not infer a monthly certification.
          </p>
        )}
        <div className="grid gap-4 md:grid-cols-2">
          <div>
            <Label htmlFor="monthly-decision">Explicit monthly decision</Label>
            <select
              id="monthly-decision"
              className="mt-1 h-9 w-full rounded-md border bg-background px-3 text-sm"
              value={decision}
              onChange={(event) => setDecision(event.target.value as MonthlyDecision)}
              disabled={!month.permissions.canGenerateSummary || month.stale}
            >
              <option value="CERTIFIED">Certified</option>
              <option value="CERTIFIED_WITH_OBSERVATIONS">Certified with observations</option>
              <option value="PARTIALLY_CERTIFIED">Partially certified</option>
              <option value="NOT_CERTIFIED">Not certified</option>
            </select>
          </div>
          <ReviewText
            id="monthly-observations"
            label="Monthly observations"
            value={observations}
            disabled={!month.permissions.canGenerateSummary || month.stale}
            onChange={setObservations}
          />
        </div>
        <CertificationMutationError error={mutation.error} />
        <Button
          type="button"
          disabled={
            !allTerminal ||
            !month.permissions.canGenerateSummary ||
            month.stale ||
            mutation.isPending
          }
          onClick={() =>
            mutation.mutate({
              expectedMonthVersion: month.version,
              decision,
              observations: observations || undefined,
            })
          }
        >
          Generate versioned summary
        </Button>
        {!allTerminal && (
          <p className="text-xs text-muted-foreground">
            All effective items need terminal eligible decisions before summary generation.
          </p>
        )}
      </CardContent>
    </Card>
  );
}

function ContextColumn({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="space-y-3 rounded-md border p-3">
      <h3 className="text-sm font-semibold">{title}</h3>
      <dl className="space-y-3">{children}</dl>
    </div>
  );
}

function ReviewText({
  id,
  label,
  value,
  disabled,
  onChange,
  error,
}: {
  id: string;
  label: string;
  value: string;
  disabled: boolean;
  onChange: (value: string) => void;
  error?: string;
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

function Metric({ label, value }: { label: string; value: string | number }) {
  return (
    <Card>
      <CardContent className="py-4">
        <p className="text-xs uppercase tracking-wide text-muted-foreground">{label}</p>
        <p className="mt-1 font-semibold">{value}</p>
      </CardContent>
    </Card>
  );
}
