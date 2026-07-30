import { Link, createFileRoute, useNavigate } from "@tanstack/react-router";
import { Check, GitCompareArrows, LockKeyhole, Send, X } from "lucide-react";
import { useState } from "react";

import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  CompletenessPanel,
  LinearIssueCard,
  RecipientPreviewCard,
} from "@/features/delivery/components";
import type {
  IssueCurrentView,
  IssueLinkView,
  IssueSnapshotView,
  LinkIssueRequest,
  PlanView,
} from "@/features/delivery/contracts";
import {
  useIssueCurrent,
  useIssueSnapshots,
  useLinkIssue,
  usePlan,
  usePlanDecision,
  useRevisionComparison,
  useRevisePlan,
  useSubmitPlan,
} from "@/features/delivery/hooks";
import { DeliveryMutationError, DeliveryQueryBoundary } from "@/features/delivery/query-boundary";
import type { LinearIssue, LinearSnapshot } from "@/features/delivery/domain";
import {
  baselineNotice,
  canDecidePlan,
  canRevisePlan,
  canSubmitPlan,
  commitmentStatusPresentation,
  isPlanContentReadOnly,
  planStateNotice,
  validateLinkIssue,
  validateRevision,
} from "@/features/delivery/presentation";
import { requireDeliveryRoute } from "@/lib/delivery-route";

export const Route = createFileRoute("/delivery/plans/$planId")({
  beforeLoad: requireDeliveryRoute,
  head: () => ({ meta: [{ title: "Plan review — Cadence" }] }),
  component: PlanDetailPage,
});

function PlanDetailPage() {
  const { planId } = Route.useParams();
  const query = usePlan(planId);
  const plan = query.data;
  return (
    <div>
      <PageHeader
        title={plan?.title ?? "Delivery plan"}
        description="Review the exact immutable version, completeness, execution evidence and commitment preview."
      />
      <DeliveryQueryBoundary queries={[query]}>
        {plan && <PlanReview plan={plan} />}
      </DeliveryQueryBoundary>
    </div>
  );
}

function PlanReview({ plan }: { plan: PlanView }) {
  const navigate = useNavigate();
  const submit = useSubmitPlan(plan.id);
  const decision = usePlanDecision(plan.id);
  const revise = useRevisePlan(plan.id);
  const comparison = useRevisionComparison(plan.id);
  const [decisionComment, setDecisionComment] = useState("");
  const [onBehalfOfSubject, setOnBehalfOfSubject] = useState("");
  const [revision, setRevision] = useState({ reason: "", impact: "" });
  const [revisionErrors, setRevisionErrors] = useState<Record<string, string>>({});
  const readOnly = isPlanContentReadOnly(plan.state);
  const maySubmit = canSubmitPlan(plan.state);
  const mayDecide = canDecidePlan(plan.state);
  const mayRevise = canRevisePlan(plan.state);
  const commitment = commitmentStatusPresentation(plan.commitmentStatus);
  const linkCount = plan.deliverables.reduce((count, item) => count + item.linearLinks.length, 0);
  const coverage = plan.deliverables.length
    ? Math.round(
        (plan.deliverables.filter((item) => item.linearLinks.length > 0).length /
          plan.deliverables.length) *
          100,
      )
    : 0;

  function createRevision(event: React.FormEvent) {
    event.preventDefault();
    const errors = validateRevision(revision);
    setRevisionErrors(errors);
    if (Object.keys(errors).length) return;
    revise.mutate(revision, {
      onSuccess: (created) =>
        navigate({
          to: "/delivery/plans/$planId",
          params: { planId: created.id },
        }),
    });
  }

  return (
    <div className="space-y-6 p-6">
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <MetricCard label="State" value={<StatusBadge status={plan.state} />} />
        <MetricCard label="Version" value={`v${plan.version}`} />
        <MetricCard label="Linear coverage" value={`${coverage}% (${linkCount})`} />
        <MetricCard label="Commitment" value={commitment.label} />
      </div>

      {plan.state === "DRAFT" && (
        <div className="flex justify-end">
          <Button asChild variant="outline">
            <Link
              to="/delivery/plans/$planId/edit"
              params={{ planId: plan.id }}
            >
              Edit draft content
            </Link>
          </Button>
        </div>
      )}

      {readOnly && (
        <Card className="border-info/30 bg-info/5">
          <CardContent className="flex gap-3 py-4 text-sm">
            <LockKeyhole className="h-4 w-4 shrink-0" />
            <span>
              {plan.state === "FROZEN" && "This version is immutable. "}
              {planStateNotice(plan.state)}
            </span>
          </CardContent>
        </Card>
      )}

      <div className="grid gap-4 lg:grid-cols-2">
        <CompletenessPanel errors={plan.completenessBlockers} />
        <RecipientPreviewCard
          preview={{
            ...plan.recipients,
            readiness:
              plan.recipients.arrowFoundry.length &&
              plan.recipients.relianceStakeholders.length &&
              plan.recipients.procurementCc.length
                ? "READY"
                : "BLOCKED",
            blockers: [],
          }}
        />
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Exact review version</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4 text-sm md:grid-cols-2">
          <Field label="Summary" value={plan.summary} />
          <Field label="Business outcomes" value={plan.businessOutcomes} />
          <Field label="Coordinator" value={plan.coordinatorSubject} />
          <Field label="Created by" value={plan.createdBySubject} />
          <Field label="Baseline type" value={baselineNotice(plan.baselineType)} />
          <Field
            label="Checksum signed by approvals"
            value={plan.checksum ?? "Not calculated"}
            mono
          />
          <Field label="Baseline" value={plan.baselineId ?? "Not frozen"} mono />
        </CardContent>
      </Card>

      {(plan.priorVersionId || plan.revisionReason || plan.revisionImpact) && (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <GitCompareArrows className="h-4 w-4" /> Revision lineage
            </CardTitle>
          </CardHeader>
          <CardContent className="grid gap-4 text-sm md:grid-cols-3">
            <Field label="Prior version" value={plan.priorVersionId ?? "Original version"} mono />
            <Field label="Reason" value={plan.revisionReason ?? "Not recorded"} />
            <Field label="Impact" value={plan.revisionImpact ?? "Not recorded"} />
            {comparison.data && (
              <div className="md:col-span-3 rounded-md border bg-muted/30 p-3 text-xs">
                <p className="font-medium">
                  Server-derived comparison: v{comparison.data.priorVersion} → v{comparison.data.currentVersion}
                </p>
                <p className="mt-1 text-muted-foreground">
                  {comparison.data.changedPlanFields.length
                    ? `Changed plan fields: ${comparison.data.changedPlanFields.join(", ")}.`
                    : "No top-level commitment field changed."}{" "}
                  Deliverables: {comparison.data.addedDeliverableCount} added, {comparison.data.removedDeliverableCount} removed, {comparison.data.changedDeliverableCount} changed.
                </p>
              </div>
            )}
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Commitment email preview</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3 text-sm">
          <p>
            <strong>{plan.title}</strong> · version {plan.version} · checksum{" "}
            <code>{plan.checksum ?? "pending"}</code>
          </p>
          <p>{plan.businessOutcomes}</p>
          <p>
            {plan.deliverables.length} deliverable(s), {coverage}% with Linear execution links.
          </p>
          <p className="text-xs text-muted-foreground">
            The server renders and archives the final approved-version HTML and plain text. A sent
            or read message never constitutes approval, acceptance or confirmation.
          </p>
          <p className="text-xs text-muted-foreground">{commitment.detail}</p>
        </CardContent>
      </Card>

      <div className="space-y-4">
        <h2 className="text-lg font-semibold">Deliverables</h2>
        {plan.deliverables.map((deliverable) => (
          <Card key={deliverable.id}>
            <CardHeader>
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <CardTitle className="text-base">
                    {deliverable.deliverableCode} · {deliverable.title}
                  </CardTitle>
                  <p className="mt-1 text-xs text-muted-foreground">
                    {deliverable.deliveryCategory} · target {deliverable.targetCompletionDate}
                  </p>
                </div>
                <StatusBadge status={deliverable.executionProjection} />
              </div>
            </CardHeader>
            <CardContent className="space-y-5">
              <div className="grid gap-3 text-sm md:grid-cols-2">
                <Field label="Business objective" value={deliverable.businessObjective} />
                <Field
                  label="Owners"
                  value={`${deliverable.productOwnerSubject} / ${deliverable.vendorOwnerSubject}`}
                />
                <Field label="Risks and assumptions" value={deliverable.riskAndAssumptions} />
                <Field
                  label="Assignments"
                  value={
                    deliverable.assignments
                      .map(
                        (assignment) =>
                          `${assignment.employeeId} (${assignment.effectiveFrom}–${assignment.effectiveTo ?? "open-ended"}${assignment.exceptionReason ? `; exception: ${assignment.exceptionReason}` : ""})`,
                      )
                      .join(", ") || "None"
                  }
                />
                <Field
                  label="Link exception"
                  value={deliverable.linkExceptionReason ?? "None recorded"}
                />
              </div>
              <div>
                <h3 className="text-sm font-medium">Acceptance criteria</h3>
                <ol className="mt-2 list-decimal space-y-2 pl-5 text-sm">
                  {deliverable.criteria.map((criterion) => (
                    <li key={criterion.id}>
                      {criterion.statement}
                      <span className="block text-xs text-muted-foreground">
                        {criterion.validationMethod} → {criterion.expectedResult}
                      </span>
                    </li>
                  ))}
                </ol>
              </div>
              <div>
                <h3 className="mb-2 text-sm font-medium">Linear evidence</h3>
                {deliverable.linearLinks.length === 0 ? (
                  <p className="rounded-md border border-dashed p-5 text-center text-sm text-muted-foreground">
                    No Linear issue is linked. Submission remains subject to the server completeness
                    gate or an authorized exception.
                  </p>
                ) : (
                  <div className="grid gap-3 lg:grid-cols-2">
                    {deliverable.linearLinks.map((link) => (
                      <IssueEvidence link={link} key={link.id} />
                    ))}
                  </div>
                )}
              </div>
              {plan.state === "DRAFT" && (
                <LinkIssueForm
                  planId={plan.id}
                  deliverableVersionId={deliverable.deliverableVersionId}
                />
              )}
            </CardContent>
          </Card>
        ))}
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Approval actions</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {plan.approvals.length > 0 && (
            <div className="space-y-2">
              {plan.approvals.map((approval) => (
                <div
                  key={approval.id}
                  className="flex flex-wrap justify-between gap-2 rounded-md border p-3 text-sm"
                >
                  <span>
                    {approval.actingSubject}
                    {approval.delegationId
                      ? ` for ${approval.approverSubject}`
                      : ""}{" "}
                    · {approval.comment ?? "No comment"}
                  </span>
                  <StatusBadge status={approval.decision} />
                </div>
              ))}
            </div>
          )}
          <Label htmlFor="decision-comment">Decision comment</Label>
          <Textarea
            id="decision-comment"
            value={decisionComment}
            onChange={(event) => setDecisionComment(event.target.value)}
            disabled={!mayDecide}
          />
          <Label htmlFor="decision-authority-holder">
            Authority holder subject (delegated action only)
          </Label>
          <Input
            id="decision-authority-holder"
            value={onBehalfOfSubject}
            onChange={(event) => setOnBehalfOfSubject(event.target.value)}
            placeholder="Leave blank when acting with direct authority"
            disabled={!mayDecide}
          />
          <div className="flex flex-wrap gap-2">
            {maySubmit && (
              <Button onClick={() => submit.mutate()} disabled={submit.isPending}>
                <Send className="mr-2 h-4 w-4" />
                Submit exact version
              </Button>
            )}
            {mayDecide && (
              <>
                <Button
                  onClick={() =>
                    decision.mutate({
                      decision: "APPROVE",
                      comment: decisionComment || undefined,
                      onBehalfOfSubject: onBehalfOfSubject.trim() || undefined,
                    })
                  }
                  disabled={decision.isPending}
                >
                  <Check className="mr-2 h-4 w-4" /> Approve checksum
                </Button>
                <Button
                  variant="destructive"
                  onClick={() =>
                    decision.mutate({
                      decision: "REJECT",
                      comment: decisionComment || undefined,
                      onBehalfOfSubject: onBehalfOfSubject.trim() || undefined,
                    })
                  }
                  disabled={decision.isPending || !decisionComment.trim()}
                >
                  <X className="mr-2 h-4 w-4" /> Reject
                </Button>
              </>
            )}
          </div>
          <DeliveryMutationError error={submit.error ?? decision.error} />
          <p className="text-xs text-muted-foreground">
            Buttons do not grant authority. The backend verifies current scoped permission, quorum,
            separation of duties, state and exact checksum atomically.
          </p>
        </CardContent>
      </Card>

      {mayRevise && (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <GitCompareArrows className="h-4 w-4" /> Create revision
            </CardTitle>
          </CardHeader>
          <CardContent>
            <form className="space-y-4" onSubmit={createRevision}>
              <FormArea
                label="Revision reason"
                value={revision.reason}
                onChange={(reason) => setRevision((v) => ({ ...v, reason }))}
                error={revisionErrors.reason}
              />
              <FormArea
                label="Impact on scope, dates, owners and evidence"
                value={revision.impact}
                onChange={(impact) => setRevision((v) => ({ ...v, impact }))}
                error={revisionErrors.impact}
              />
              <DeliveryMutationError error={revise.error} />
              <Button type="submit" disabled={revise.isPending}>
                Clone into revision
              </Button>
              <p className="text-xs text-muted-foreground">
                The new version records reason, impact and prior-version lineage, and requires a new
                approval. This action never edits the frozen baseline.
              </p>
            </form>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

function LinkIssueForm({
  planId,
  deliverableVersionId,
}: {
  planId: string;
  deliverableVersionId: string;
}) {
  const mutation = useLinkIssue(planId);
  const [expanded, setExpanded] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [value, setValue] = useState<LinkIssueRequest>({
    deliverableVersionId,
    connectionId: "",
    issueUuid: "",
  });

  if (!expanded) {
    return (
      <Button type="button" variant="outline" onClick={() => setExpanded(true)}>
        Attach resolved Linear issue
      </Button>
    );
  }

  function submit(event: React.FormEvent) {
    event.preventDefault();
    const nextErrors = validateLinkIssue(value);
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length) return;
    mutation.mutate(value, {
      onSuccess: () => {
        setExpanded(false);
        setErrors({});
      },
    });
  }

  const set = (field: keyof LinkIssueRequest, next: string) =>
    setValue((current) => ({ ...current, [field]: next }));

  return (
    <form className="space-y-3 rounded-md border p-4" onSubmit={submit}>
      <div>
        <p className="font-medium">Attach recorded provider issue</p>
        <p className="mt-1 text-xs text-muted-foreground">
          Identify an issue already recorded for the authorized connection. The server resolves and
          validates its identifier, URL, title and provider state. This browser never calls Linear
          or supplies provider metadata or credentials.
        </p>
      </div>
      <div className="grid gap-3 md:grid-cols-2">
        <LinkField
          label="Connection ID"
          value={value.connectionId}
          onChange={(next) => set("connectionId", next)}
          error={errors.connectionId}
        />
        <LinkField
          label="Issue UUID"
          value={value.issueUuid}
          onChange={(next) => set("issueUuid", next)}
          error={errors.issueUuid}
        />
        <LinkField
          label="Multi-link rationale (when required)"
          value={value.rationale ?? ""}
          onChange={(next) => set("rationale", next)}
        />
      </div>
      <DeliveryMutationError error={mutation.error} />
      <div className="flex gap-2">
        <Button type="submit" disabled={mutation.isPending}>
          Attach issue
        </Button>
        <Button type="button" variant="ghost" onClick={() => setExpanded(false)}>
          Cancel
        </Button>
      </div>
      <p className="text-xs text-muted-foreground">
        The server validates connection, workspace and team scope, rejects forged or inaccessible
        metadata, and decides whether rationale is sufficient.
      </p>
    </form>
  );
}

function LinkField({
  label,
  value,
  onChange,
  error,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  error?: string;
}) {
  return (
    <div className="space-y-1.5">
      <Label>{label}</Label>
      <Input
        value={value}
        onChange={(event) => onChange(event.target.value)}
        aria-invalid={Boolean(error)}
      />
      {error && (
        <p className="text-xs text-destructive" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

function IssueEvidence({ link }: { link: IssueLinkView }) {
  const currentQuery = useIssueCurrent(link.id);
  const snapshotsQuery = useIssueSnapshots(link.id);
  const current = currentQuery.data;
  const snapshots = snapshotsQuery.data ?? [];
  return (
    <DeliveryQueryBoundary queries={[currentQuery, snapshotsQuery]}>
      {current && <LinearIssueCard issue={toLinearIssue(link, current, snapshots)} />}
    </DeliveryQueryBoundary>
  );
}

function toLinearIssue(
  link: IssueLinkView,
  current: IssueCurrentView,
  snapshots: IssueSnapshotView[],
): LinearIssue {
  const snapshot = (kind: IssueSnapshotView["snapshotType"]) => {
    const value = snapshots.find((item) => item.snapshotType === kind);
    if (!value) return null;
    return {
      kind: value.snapshotType,
      state: value.providerStateName
        ? {
            originalName: value.providerStateName,
            originalType: value.providerStateType ?? "unknown",
            normalized: value.normalizedState ?? "UNKNOWN",
          }
        : null,
      fetchedAt: value.fetchedAt,
      confidence: value.confidence,
      fetchStatus: value.status,
      failureReason: value.failureReason,
    } satisfies LinearSnapshot;
  };
  return {
    id: link.id,
    issueUuid: current.issueUuid,
    identifier: current.identifier,
    url: current.url,
    title: current.title,
    currentState:
      current.providerStateName || current.providerStateType
        ? {
            originalName: current.providerStateName ?? "Unavailable",
            originalType: current.providerStateType ?? "Unavailable",
            normalized: current.normalizedState,
          }
        : null,
    planSnapshot: snapshot("PLAN_TIME"),
    monthEndSnapshot: snapshot("MONTH_END"),
    assigneeName: null,
    priority: null,
    fetchedAt: current.fetchedAt,
    freshness: current.stale ? "STALE" : "FRESH",
    linkStatus: link.status,
    accessStatus:
      link.status === "BROKEN"
        ? "BROKEN"
        : current.inaccessible || link.status === "INACCESSIBLE"
          ? "INACCESSIBLE"
          : "AVAILABLE",
    errorCode: current.inaccessible ? "LINEAR_INACCESSIBLE" : null,
  };
}

function MetricCard({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <Card>
      <CardContent className="p-4">
        <p className="text-xs uppercase tracking-wide text-muted-foreground">{label}</p>
        <div className="mt-2 font-semibold">{value}</div>
      </CardContent>
    </Card>
  );
}

function Field({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div>
      <p className="text-xs uppercase tracking-wide text-muted-foreground">{label}</p>
      <p className={mono ? "mt-1 break-all font-mono text-xs" : "mt-1"}>{value}</p>
    </div>
  );
}

function FormArea({
  label,
  value,
  onChange,
  error,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  error?: string;
}) {
  return (
    <div className="space-y-1.5">
      <Label>{label}</Label>
      <Textarea
        value={value}
        onChange={(event) => onChange(event.target.value)}
        aria-invalid={Boolean(error)}
      />
      {error && (
        <p className="text-xs text-destructive" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
