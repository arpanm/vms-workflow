import { Link, createFileRoute } from "@tanstack/react-router";
import { CheckCircle2, GitCompareArrows, LockKeyhole, ShieldAlert } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
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
  ConfirmationDecision,
  ConfirmationRequestView,
} from "@/features/certification/contracts";
import { formatDateTime } from "@/features/certification/formatting";
import { useConfirmationAction, useConfirmationRequest } from "@/features/certification/hooks";
import {
  confirmationDecisionOptions,
  confirmationRequiresComment,
} from "@/features/certification/presentation";
import {
  CertificationMutationError,
  CertificationQueryBoundary,
} from "@/features/certification/query-boundary";
import { focusValidationSummary } from "@/features/certification/validation";

export const Route = createFileRoute("/confirmation/requests/$requestId")({
  head: () => ({ meta: [{ title: "Confirmation response — Cadence" }] }),
  component: ConfirmationResponsePage,
});

function ConfirmationResponsePage() {
  const { requestId } = Route.useParams();
  const query = useConfirmationRequest(requestId);
  return (
    <div>
      <PageHeader
        title={query.data ? `${query.data.monthLabel} confirmation` : "Confirmation response"}
        description="Review exact immutable source versions and the visible diff before recording an authenticated in-app action."
      >
        {query.data && (
          <Link
            to="/confirmation/$monthId"
            params={{ monthId: query.data.monthId }}
            className="rounded-md border px-3 py-2 text-sm font-medium hover:bg-muted"
          >
            Back to readiness
          </Link>
        )}
      </PageHeader>
      <CertificationQueryBoundary queries={[query]}>
        {query.data && <ConfirmationWorkspace request={query.data} />}
      </CertificationQueryBoundary>
    </div>
  );
}

function ConfirmationWorkspace({ request }: { request: ConfirmationRequestView }) {
  return (
    <div className="min-w-0 space-y-6 p-4 sm:p-6">
      <AuthorityNotice />
      <VersionNotice
        version={request.version}
        stale={request.stale}
        locked={request.locked}
        updatedAt={request.createdAt}
      />

      <Card className="min-w-0 overflow-hidden">
        <CardHeader>
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <CardTitle className="text-base">Exact confirmation scope</CardTitle>
              <p className="mt-1 text-xs text-muted-foreground">
                {request.engagementLabel} · due {formatDateTime(request.dueAt)}
              </p>
            </div>
            <StatusBadge status={request.state} />
          </div>
        </CardHeader>
        <CardContent className="min-w-0 space-y-5">
          <dl className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
            <Field label="Request ID" value={request.id} mono />
            <Field label="Scope checksum" value={request.scopeChecksum} mono />
            <Field label="Quorum" value={request.quorumDescription} />
            <Field label="Eligibility" value={request.eligibilityMessage} />
          </dl>
          <div>
            <h3 className="text-sm font-medium">Bound source versions</h3>
            {(request.scopeSources ?? []).length > 0 ? (
              <ul className="mt-2 grid min-w-0 gap-3 sm:grid-cols-2">
                {request.scopeSources.map((source) => (
                  <li key={`${source.kind}-${source.id}`} className="min-w-0 rounded-md border p-3">
                    <p className="text-sm font-medium">{source.display}</p>
                    <p className="mt-1 text-xs text-muted-foreground">
                      {source.kind.replaceAll("_", " ")} · {source.freshness}
                      {source.version === null ? "" : ` · v${source.version}`}
                    </p>
                    <p className="mt-2 break-words font-mono text-xs [overflow-wrap:anywhere]">
                      {source.id}
                    </p>
                    <p className="mt-1 break-words font-mono text-xs [overflow-wrap:anywhere]">
                      {source.checksum ?? "Checksum not supplied for this source"}
                    </p>
                  </li>
                ))}
              </ul>
            ) : (
              <ul className="mt-2 min-w-0 space-y-1 font-mono text-xs">
                {request.sourceVersionIds.map((version) => (
                  <li key={version} className="break-words [overflow-wrap:anywhere]">
                    {version}
                  </li>
                ))}
              </ul>
            )}
          </div>
          <div className="grid gap-4 md:grid-cols-2">
            <div className="rounded-md border p-3">
              <h3 className="text-sm font-medium">Recipients</h3>
              <ul className="mt-2 space-y-2 text-sm">
                {request.recipients.map((recipient) => (
                  <li key={`${recipient.kind}-${recipient.display}-${recipient.roleReason}`}>
                    <span className="font-medium">{recipient.kind}: </span>
                    <span className="break-words [overflow-wrap:anywhere]">
                      {recipient.display}
                    </span>
                    <span className="block text-xs text-muted-foreground">
                      {recipient.roleReason}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
            <div className="rounded-md border p-3">
              <h3 className="text-sm font-medium">Communication boundary</h3>
              <dl className="mt-2 space-y-3">
                <Field label="Business state" value={request.state} />
                <Field label="Transport status" value={request.transportStatus} />
                <Field label="Provider configuration" value={request.providerConfiguration} />
              </dl>
              <p className="mt-3 text-xs text-muted-foreground">
                Transport delivery, read, bounce, failure, or silence never becomes confirmation.
                Provider credentials and message internals are server-only.
              </p>
            </div>
          </div>
        </CardContent>
      </Card>

      <VersionDiff request={request} />
      <ConfirmationAction request={request} />
      <ActionHistory request={request} />
      <NotificationHistory notifications={request.notifications ?? []} />
    </div>
  );
}

function VersionDiff({ request }: { request: ConfirmationRequestView }) {
  return (
    <Card className="min-w-0 overflow-hidden">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <GitCompareArrows className="h-4 w-4" aria-hidden="true" />
          Visible version diff
        </CardTitle>
      </CardHeader>
      <CardContent>
        {(request.diff ?? []).length === 0 ? (
          <p className="text-sm text-muted-foreground">
            This is the first request version or the server reports no changed scoped fields.
          </p>
        ) : (
          <div className="max-w-full overflow-x-auto">
            <table className="w-full table-fixed border-collapse text-left text-sm">
              <caption className="sr-only">
                Changes from the superseded confirmation request
              </caption>
              <thead>
                <tr className="border-b">
                  <th className="p-2">Field</th>
                  <th className="p-2">Previous</th>
                  <th className="p-2">Current exact value</th>
                </tr>
              </thead>
              <tbody>
                {request.diff.map((item) => (
                  <tr key={item.fieldLabel} className="border-b align-top">
                    <th
                      scope="row"
                      className="break-words p-2 font-medium [overflow-wrap:anywhere]"
                    >
                      {item.fieldLabel}
                    </th>
                    <td className="break-words p-2 text-muted-foreground [overflow-wrap:anywhere]">
                      {item.previousValue ?? "Not previously in scope"}
                    </td>
                    <td className="break-words p-2 [overflow-wrap:anywhere]">
                      {item.currentValue}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function ConfirmationAction({ request }: { request: ConfirmationRequestView }) {
  const [decision, setDecision] = useState<ConfirmationDecision>("CONFIRM");
  const [comment, setComment] = useState("");
  const [impactAccepted, setImpactAccepted] = useState(false);
  const [projectId, setProjectId] = useState("");
  const [validationErrors, setValidationErrors] = useState<ValidationError[]>([]);
  const errorSummaryRef = useRef<HTMLDivElement>(null);
  const reconciledVersion = useRef(`${request.id}:${request.version}`);
  const mutation = useConfirmationAction(request.id);
  const terminal = [
    "CONFIRMED",
    "CHANGES_REQUESTED",
    "REJECTED",
    "EXPIRED",
    "CANCELLED",
    "SUPERSEDED",
  ].includes(request.state);
  const destructive = decision !== "CONFIRM";
  const cannotAct =
    request.locked ||
    request.stale ||
    terminal ||
    !request.eligible ||
    !request.permissions.canConfirm;

  useEffect(() => {
    const versionKey = `${request.id}:${request.version}`;
    if (reconciledVersion.current === versionKey) return;
    reconciledVersion.current = versionKey;
    setDecision("CONFIRM");
    setComment("");
    setImpactAccepted(false);
    setProjectId("");
    setValidationErrors([]);
  }, [request.id, request.version]);

  function recordAction() {
    const errors: ValidationError[] = [];
    if (confirmationRequiresComment(decision) && !comment.trim()) {
      errors.push({
        fieldId: "confirmation-comment",
        message: "Reason and required correction is required.",
      });
    }
    if (destructive && !impactAccepted) {
      errors.push({
        fieldId: "confirmation-impact",
        message: "Acknowledge the correction and readiness impact.",
      });
    }
    if (request.projectIdRequired && !projectId) {
      errors.push({
        fieldId: "confirmation-project",
        message: "Select the eligible project this action covers.",
      });
    }
    setValidationErrors(errors);
    if (errors.length > 0) {
      focusValidationSummary(errorSummaryRef);
      return;
    }
    mutation.mutate({
      expectedRequestVersion: request.version,
      decision,
      comment: comment || undefined,
      projectId: projectId || undefined,
    });
  }

  const commentInvalid = validationErrors.some((error) => error.fieldId === "confirmation-comment");
  const impactInvalid = validationErrors.some((error) => error.fieldId === "confirmation-impact");
  const projectInvalid = validationErrors.some((error) => error.fieldId === "confirmation-project");
  return (
    <Card
      className={`min-w-0 overflow-hidden ${
        destructive ? "border-destructive/40" : "border-success/40"
      }`}
    >
      <CardHeader>
        <CardTitle className="text-base">Authenticated in-app action</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {!request.eligible && (
          <div
            className="flex gap-2 rounded-md border border-destructive/30 bg-destructive/5 p-3 text-sm"
            role="alert"
          >
            <ShieldAlert className="h-4 w-4 shrink-0" aria-hidden="true" />
            {request.eligibilityMessage}. A link or visible route does not confer eligibility.
          </div>
        )}
        {terminal && (
          <p className="flex items-center gap-2 text-sm text-muted-foreground">
            <LockKeyhole className="h-4 w-4" aria-hidden="true" />
            This immutable request is terminal. Replays return only the prior authorized outcome;
            they do not add a new action.
          </p>
        )}
        <fieldset disabled={cannotAct}>
          <legend className="text-sm font-medium">Decision</legend>
          <div className="mt-2 grid gap-2 md:grid-cols-3">
            {confirmationDecisionOptions.map((option) => (
              <label
                key={option.value}
                className={`flex cursor-pointer items-center gap-2 rounded-md border p-3 text-sm ${
                  decision === option.value ? "border-primary bg-primary/5" : ""
                }`}
              >
                <input
                  type="radio"
                  name="confirmation-decision"
                  value={option.value}
                  checked={decision === option.value}
                  onChange={() => {
                    setDecision(option.value);
                    setImpactAccepted(false);
                    setValidationErrors([]);
                  }}
                />
                {option.label}
              </label>
            ))}
          </div>
        </fieldset>
        {(request.eligibleProjects ?? []).length > 0 && (
          <div>
            <Label htmlFor="confirmation-project">
              {request.projectIdRequired
                ? "Eligible project contribution (required)"
                : "Eligible project contribution"}
            </Label>
            {request.projectIdRequired ? (
              <select
                id="confirmation-project"
                className="mt-1 h-9 w-full rounded-md border bg-background px-3 text-sm"
                value={projectId}
                disabled={cannotAct}
                aria-invalid={projectInvalid}
                aria-describedby={
                  projectInvalid ? "confirmation-project-error" : "confirmation-project-help"
                }
                onChange={(event) => {
                  setProjectId(event.target.value);
                  setValidationErrors((current) =>
                    current.filter((error) => error.fieldId !== "confirmation-project"),
                  );
                }}
              >
                <option value="">Select an eligible captured project</option>
                {request.eligibleProjects
                  .filter(
                    (project): project is typeof project & { id: string } => project.id !== null,
                  )
                  .map((project) => (
                    <option key={project.id} value={project.id}>
                      {project.display} · {project.roleReason}
                    </option>
                  ))}
              </select>
            ) : (
              <p id="confirmation-project-help" className="mt-1 text-sm">
                {request.eligibleProjects
                  .map((project) => `${project.display} · ${project.roleReason}`)
                  .join(", ")}
              </p>
            )}
            {request.projectIdRequired && (
              <p
                id={projectInvalid ? "confirmation-project-error" : "confirmation-project-help"}
                className={`mt-1 text-xs ${
                  projectInvalid ? "text-destructive" : "text-muted-foreground"
                }`}
              >
                {projectInvalid
                  ? "Select the eligible project this action covers."
                  : "The server supplied these captured choices; the browser does not infer assignment."}
              </p>
            )}
          </div>
        )}
        <div>
          <Label htmlFor="confirmation-comment">
            {confirmationRequiresComment(decision)
              ? "Reason and required correction (required)"
              : "Confirmation comment"}
          </Label>
          <Textarea
            id="confirmation-comment"
            className="mt-1"
            value={comment}
            disabled={cannotAct}
            onChange={(event) => {
              setComment(event.target.value);
              setValidationErrors((current) =>
                current.filter((error) => error.fieldId !== "confirmation-comment"),
              );
            }}
            aria-invalid={commentInvalid}
            aria-describedby={commentInvalid ? "confirmation-comment-error" : undefined}
          />
          {commentInvalid && (
            <p id="confirmation-comment-error" className="mt-1 text-xs text-destructive">
              Reason and required correction is required.
            </p>
          )}
        </div>
        {destructive && (
          <label
            className="flex items-start gap-2 rounded-md border border-destructive/30 bg-destructive/5 p-3 text-sm"
            htmlFor="confirmation-impact"
          >
            <Checkbox
              id="confirmation-impact"
              checked={impactAccepted}
              onCheckedChange={(checked) => {
                setImpactAccepted(checked === true);
                setValidationErrors((current) =>
                  current.filter((error) => error.fieldId !== "confirmation-impact"),
                );
              }}
              disabled={cannotAct}
              aria-invalid={impactInvalid}
              aria-describedby={impactInvalid ? "confirmation-impact-error" : undefined}
            />
            <span id={impactInvalid ? "confirmation-impact-error" : undefined}>
              I understand this preserves the request and action, blocks readiness, and creates a
              governance correction/reopen task. It does not directly edit any source fact.
            </span>
          </label>
        )}
        <ValidationSummary
          id="confirmation-action-errors"
          title="Confirmation action errors"
          errors={validationErrors}
          summaryRef={errorSummaryRef}
        />
        <CertificationMutationError error={mutation.error} />
        <Button
          type="button"
          variant={destructive ? "destructive" : "default"}
          disabled={cannotAct || mutation.isPending}
          onClick={recordAction}
        >
          {decision === "CONFIRM" ? (
            <CheckCircle2 className="mr-2 h-4 w-4" aria-hidden="true" />
          ) : (
            <ShieldAlert className="mr-2 h-4 w-4" aria-hidden="true" />
          )}
          Record action for exact version
        </Button>
      </CardContent>
    </Card>
  );
}

function ActionHistory({ request }: { request: ConfirmationRequestView }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Action and request lineage</CardTitle>
      </CardHeader>
      <CardContent className="space-y-5">
        {(request.actions ?? []).length === 0 ? (
          <EmptyState
            title="No explicit actions"
            detail="Delivery, receipt, silence, reminders, and elapsed due time do not create an action."
          />
        ) : (
          <ol className="space-y-3">
            {request.actions.map((action) => (
              <li key={action.id} className="rounded-md border p-3 text-sm">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="font-medium">{action.actorDisplay}</span>
                  <StatusBadge status={action.decision} />
                </div>
                <p className="mt-1 text-xs text-muted-foreground">
                  {action.actorRoleReason} · {action.source} · {formatDateTime(action.recordedAt)}
                  {action.representedAt
                    ? ` · represented ${formatDateTime(action.representedAt)}`
                    : ""}
                </p>
                {action.comment && <p className="mt-2">{action.comment}</p>}
                <p className="mt-1 font-mono text-xs">Audit: {action.auditReference}</p>
              </li>
            ))}
          </ol>
        )}
        {(request.lineage ?? []).length > 0 && (
          <div>
            <h3 className="text-sm font-medium">Request versions</h3>
            <ol className="mt-2 space-y-2">
              {request.lineage.map((item) => (
                <li
                  key={item.id}
                  className="flex flex-wrap items-center justify-between gap-2 rounded-md border p-3 text-sm"
                >
                  <Link
                    to="/confirmation/requests/$requestId"
                    params={{ requestId: item.id }}
                    className="font-medium text-primary hover:underline"
                  >
                    Request v{item.version}
                  </Link>
                  <StatusBadge status={item.state} />
                </li>
              ))}
            </ol>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
