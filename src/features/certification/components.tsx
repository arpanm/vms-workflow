import { Link } from "@tanstack/react-router";
import type { RefObject } from "react";
import {
  AlertTriangle,
  CheckCircle2,
  Clock3,
  FileClock,
  Info,
  LockKeyhole,
  MailWarning,
} from "lucide-react";

import { StatusBadge } from "@/components/status-badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

import type { LinearSnapshotView, NotificationView, TimelineEventView } from "./contracts";
import { formatDateTime, formatLabel } from "./formatting";
import { resolveReadinessActionPath } from "./route-intents";

export function AuthorityNotice() {
  return (
    <div className="flex gap-3 rounded-lg border border-info/30 bg-info/5 p-4 text-sm" role="note">
      <Info className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
      <p>
        Delivery percentages, Linear states, email delivery/read receipts, silence, reminders, and
        elapsed due dates are evidence only. They never infer acceptance, certification, or
        confirmation. Only a recorded eligible server-authorized action changes business state.
      </p>
    </div>
  );
}

export function VersionNotice({
  version,
  stale,
  locked,
  updatedAt,
}: {
  version: number;
  stale: boolean;
  locked: boolean;
  updatedAt: string;
}) {
  return (
    <div
      className={`flex flex-wrap items-center gap-x-4 gap-y-2 rounded-lg border p-3 text-sm ${
        stale ? "border-warning/40 bg-warning/5" : "bg-muted/30"
      }`}
      role={stale ? "alert" : "status"}
    >
      {locked ? (
        <LockKeyhole className="h-4 w-4" aria-hidden="true" />
      ) : stale ? (
        <AlertTriangle className="h-4 w-4 text-warning-foreground" aria-hidden="true" />
      ) : (
        <CheckCircle2 className="h-4 w-4 text-success-foreground" aria-hidden="true" />
      )}
      <span>
        Server version <strong>v{version}</strong>
      </span>
      <span>{locked ? "Read-only / locked" : "Editable only when the server permits"}</span>
      <span>Updated {formatDateTime(updatedAt)}</span>
      {stale && <strong>Refresh before taking an action.</strong>}
    </div>
  );
}

export function LinearSnapshotPanel({ snapshots }: { snapshots: LinearSnapshotView[] }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Linear evidence snapshots</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {snapshots.length === 0 ? (
          <EmptyState
            title="No snapshot evidence returned"
            detail="The server must record captured, fetch-failed, or unavailable status. Current Linear state is not substituted."
          />
        ) : (
          snapshots.map((snapshot) => (
            <div
              key={snapshot.label}
              className="flex flex-wrap items-center justify-between gap-3 rounded-md border p-3 text-sm"
            >
              <div>
                <p className="font-medium">{formatLabel(snapshot.label)}</p>
                <p className="text-xs text-muted-foreground">
                  {snapshot.capturedAt
                    ? `Captured ${formatDateTime(snapshot.capturedAt)}`
                    : "No capture timestamp"}
                  {snapshot.sourceVersionId ? ` · source ${snapshot.sourceVersionId}` : ""}
                </p>
              </div>
              <div className="flex gap-2">
                <StatusBadge status={snapshot.status} />
                <StatusBadge status={snapshot.freshness} />
              </div>
            </div>
          ))
        )}
        <p className="text-xs text-muted-foreground">
          “Done” or “Completed” in Linear is not displayed as product-owner acceptance.
        </p>
      </CardContent>
    </Card>
  );
}

export function NotificationHistory({ notifications }: { notifications: NotificationView[] }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Communication history</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {notifications.length === 0 ? (
          <EmptyState
            title="No communication records"
            detail="No message delivery or reminder state has been returned for this scope."
          />
        ) : (
          notifications.map((notice) => (
            <div key={notice.id} className="rounded-md border p-3 text-sm">
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div>
                  <p className="font-medium">{formatLabel(notice.category)}</p>
                  <p className="text-xs text-muted-foreground">
                    {notice.recipientSummary} · created {formatDateTime(notice.createdAt)}
                  </p>
                </div>
                <StatusBadge status={notice.transportStatus} />
              </div>
              {notice.errorCategory && (
                <p className="mt-2 flex items-center gap-1 text-xs text-destructive">
                  <MailWarning className="h-3.5 w-3.5" aria-hidden="true" />
                  {formatLabel(notice.errorCategory)}
                  {notice.correlationId ? ` · reference ${notice.correlationId}` : ""}
                </p>
              )}
              <p className="mt-2 text-xs text-muted-foreground">
                Transport status never changes the business decision.
              </p>
            </div>
          ))
        )}
      </CardContent>
    </Card>
  );
}

export function EvidenceTimeline({ events }: { events: TimelineEventView[] }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Immutable timeline</CardTitle>
      </CardHeader>
      <CardContent>
        {events.length === 0 ? (
          <EmptyState
            title="No timeline events"
            detail="Server-recorded submission, decision, clarification, and reopen events appear here."
          />
        ) : (
          <ol className="space-y-4">
            {events.map((event) => (
              <li key={event.id} className="flex gap-3">
                <FileClock
                  className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground"
                  aria-hidden="true"
                />
                <div className="min-w-0 text-sm">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">{event.label}</span>
                    <StatusBadge status={event.state} />
                  </div>
                  <p className="text-xs text-muted-foreground">
                    {event.actorDisplay} · recorded {formatDateTime(event.recordedAt)}
                    {event.representedAt
                      ? ` · represented ${formatDateTime(event.representedAt)}`
                      : ""}
                    {event.correlationId ? ` · reference ${event.correlationId}` : ""}
                  </p>
                </div>
              </li>
            ))}
          </ol>
        )}
      </CardContent>
    </Card>
  );
}

export function EmptyState({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="rounded-md border border-dashed p-6 text-center">
      <Clock3 className="mx-auto h-6 w-6 text-muted-foreground" aria-hidden="true" />
      <p className="mt-2 font-medium">{title}</p>
      <p className="mt-1 text-sm text-muted-foreground">{detail}</p>
    </div>
  );
}

export type ValidationError = {
  fieldId: string;
  message: string;
};

export function ValidationSummary({
  id,
  title,
  errors,
  summaryRef,
}: {
  id: string;
  title: string;
  errors: ValidationError[];
  summaryRef?: RefObject<HTMLDivElement | null>;
}) {
  if (errors.length === 0) return null;
  return (
    <div
      ref={summaryRef}
      id={id}
      className="rounded-md border border-destructive/30 bg-destructive/5 p-3 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring"
      role="alert"
      aria-labelledby={`${id}-title`}
      tabIndex={-1}
    >
      <p id={`${id}-title`} className="font-medium">
        {title}
      </p>
      <ul className="mt-2 list-disc space-y-1 pl-5">
        {errors.map((error) => (
          <li key={`${error.fieldId}-${error.message}`}>
            <a className="underline underline-offset-2" href={`#${error.fieldId}`}>
              {error.message}
            </a>
          </li>
        ))}
      </ul>
    </div>
  );
}

export function SafeActionLink({ path, label }: { path: string | null; label: string }) {
  const resolvedPath = resolveReadinessActionPath(path);
  if (!resolvedPath) return <span>{label}</span>;
  return (
    <Link to={resolvedPath} className="font-medium text-primary hover:underline">
      {label}
    </Link>
  );
}

export function Field({
  label,
  value,
  mono = false,
}: {
  label: string;
  value: string | number | null | undefined;
  mono?: boolean;
}) {
  return (
    <div>
      <dt className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{label}</dt>
      <dd
        className={`mt-1 min-w-0 break-words [overflow-wrap:anywhere] ${
          mono ? "font-mono text-xs" : "text-sm"
        }`}
      >
        {value === null || value === undefined || value === "" ? "Not recorded" : value}
      </dd>
    </div>
  );
}
