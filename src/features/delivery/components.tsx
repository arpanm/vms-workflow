import { AlertTriangle, CheckCircle2, ExternalLink, Link2Off } from "lucide-react";

import { StatusBadge } from "@/components/status-badge";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

import type { LinearIssue, RecipientPreview } from "./domain";
import {
  issueLinkStatusPresentation,
  linearEvidenceNotice,
  normalizedStateLabel,
  snapshotStatusPresentation,
  validateRecipientPreview,
} from "./presentation";

export function CompletenessPanel({ errors }: { errors: string[] }) {
  return (
    <Card className={errors.length ? "border-warning/40" : "border-success/40"}>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          {errors.length ? (
            <AlertTriangle className="h-4 w-4 text-warning" />
          ) : (
            <CheckCircle2 className="h-4 w-4 text-success" />
          )}
          Completeness
        </CardTitle>
      </CardHeader>
      <CardContent>
        {errors.length === 0 ? (
          <p className="text-sm text-success-foreground">
            This version has no reported completeness blockers.
          </p>
        ) : (
          <ul
            className="list-disc space-y-1 pl-5 text-sm text-warning-foreground"
            aria-label="Plan completeness errors"
          >
            {errors.map((error) => (
              <li key={error}>{error}</li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}

export function RecipientPreviewCard({ preview }: { preview: RecipientPreview }) {
  const errors = validateRecipientPreview(preview);
  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between gap-3">
          <CardTitle className="text-base">Commitment recipients</CardTitle>
          <StatusBadge status={preview.readiness} />
        </div>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        <RecipientRow label="ArrowFoundry" values={preview.arrowFoundry} />
        <RecipientRow label="Reliance product stakeholders" values={preview.relianceStakeholders} />
        <RecipientRow label="Central Procurement CC" values={preview.procurementCc} />
        {errors.length > 0 && (
          <ul className="list-disc pl-5 text-xs text-destructive" aria-label="Recipient blockers">
            {errors.map((error) => (
              <li key={error}>{error}</li>
            ))}
          </ul>
        )}
        <p className="text-xs text-muted-foreground">
          This is a server-resolved preview. Sending, delivery, reading or silence never constitutes
          approval or confirmation.
        </p>
      </CardContent>
    </Card>
  );
}

function RecipientRow({ label, values }: { label: string; values: string[] }) {
  return (
    <div>
      <p className="text-xs uppercase tracking-wide text-muted-foreground">{label}</p>
      <p className="mt-1">{values.length ? values.join(", ") : "Missing"}</p>
    </div>
  );
}

export function LinearIssueCard({ issue }: { issue: LinearIssue }) {
  const unavailable = issue.accessStatus !== "AVAILABLE";
  const linkStatus = issueLinkStatusPresentation(issue.linkStatus);
  const originalState = issue.currentState?.originalName?.trim() || "Unavailable";
  const originalType = issue.currentState?.originalType?.trim() || "Unavailable";
  const normalized = issue.currentState?.normalized ?? "UNKNOWN";
  return (
    <Card className={unavailable ? "border-destructive/30" : undefined}>
      <CardContent className="space-y-3 p-4">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <a
              href={issue.url}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1 font-medium text-primary hover:underline"
            >
              {issue.identifier}
              <ExternalLink className="h-3.5 w-3.5" />
            </a>
            <p className="mt-0.5 truncate text-sm">{issue.title}</p>
          </div>
          <div className="flex flex-wrap justify-end gap-2">
            <StatusBadge status={issue.linkStatus} />
            <StatusBadge status={issue.accessStatus} />
          </div>
        </div>
        {unavailable ? (
          <p className="flex items-start gap-2 text-xs text-destructive">
            <Link2Off className="h-3.5 w-3.5" />
            {linkStatus.detail} Current access is {issue.accessStatus.toLowerCase()}. Last-known
            evidence remains visible and submission may be blocked.
          </p>
        ) : null}
        <div className="flex flex-wrap gap-2">
          <Badge variant="outline">Provider state: {originalState}</Badge>
          <Badge variant="outline">Provider type: {originalType}</Badge>
          <Badge variant="outline">Normalized: {normalizedStateLabel(normalized)}</Badge>
          <StatusBadge status={issue.freshness} />
        </div>
        {issue.freshness === "STALE" && (
          <p className="text-xs text-warning-foreground">
            This is stale, last-known provider evidence. It must not be treated as current execution
            state.
          </p>
        )}
        <div className="grid gap-2 text-xs sm:grid-cols-2">
          <StateCell
            label="Plan snapshot"
            value={
              issue.planSnapshot?.state?.originalName ??
              (issue.planSnapshot
                ? `${snapshotStatusPresentation(issue.planSnapshot.fetchStatus)}${
                    issue.planSnapshot.failureReason ? ` — ${issue.planSnapshot.failureReason}` : ""
                  }`
                : null) ??
              "Not captured"
            }
          />
          <StateCell label="Current" value={originalState} />
        </div>
        <p className="rounded-md bg-muted/60 p-2 text-xs text-muted-foreground">
          {linearEvidenceNotice(issue)}
        </p>
      </CardContent>
    </Card>
  );
}

function StateCell({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="uppercase tracking-wide text-muted-foreground">{label}</p>
      <p className="mt-0.5 font-medium text-foreground">{value}</p>
    </div>
  );
}
