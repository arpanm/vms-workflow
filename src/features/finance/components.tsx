import { Link } from "@tanstack/react-router";
import { AlertTriangle, Ban, Clock3, LoaderCircle, RefreshCcw, ShieldCheck } from "lucide-react";
import type { ReactNode } from "react";

import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ApiError } from "@/lib/api-client";

import type {
  FinanceDashboard,
  FinancePermission,
  PaymentEvent,
  ReadinessRule,
  SourceReference,
} from "./contracts";
import {
  formatDateTime,
  safeFinanceActionPath,
  shortenHash,
} from "./presentation";

type QueryLike = {
  isPending: boolean;
  isError: boolean;
  error: Error | null;
  refetch: () => unknown;
};

const problemCopy: Record<string, string> = {
  NOT_FOUND: "This record is unavailable or outside your current scope.",
  FORBIDDEN: "Your current server-authorized scope does not permit this view or action.",
  VERSION_CONFLICT: "A newer version exists. Refresh and review it before acting.",
  IDEMPOTENCY_KEY_REUSED: "This retry differs from the original request. Start a new action.",
  SCAN_PENDING: "The document scan is still pending.",
  ARTIFACT_QUARANTINED: "The artifact is quarantined and cannot be viewed or downloaded.",
  DOWNLOAD_EXPIRED: "The download authorization expired. Request it again.",
  PROVIDER_NOT_CONFIGURED: "The required controlled provider is not configured.",
  NOT_CONFIGURED: "The required controlled provider is not configured.",
  READINESS_BLOCKED: "Mandatory readiness rules still block this action.",
  SEPARATION_OF_DUTIES_VIOLATION:
    "The exception requester cannot approve their own request. Sign in as a distinct authorized Procurement reviewer.",
  EXCEPTION_EXPIRED:
    "This exception expired and cannot be approved. Create a new bounded request against current evidence.",
  EXCEPTION_APPROVAL_BINDING_MISMATCH:
    "The exception lineage changed or is incomplete. Refresh and approve only the exact pending request.",
};

function safeReference(error: ApiError) {
  return error.correlationId && /^[a-z0-9][a-z0-9._:-]{0,127}$/i.test(error.correlationId)
    ? error.correlationId
    : null;
}

export function FinanceBoundary({
  queries,
  children,
}: {
  queries: QueryLike[];
  children: ReactNode;
}) {
  if (queries.some((query) => query.isPending)) {
    return (
      <div
        className="m-6 flex min-h-48 items-center justify-center gap-2 rounded-lg border border-dashed text-sm text-muted-foreground"
        role="status"
        aria-live="polite"
      >
        <LoaderCircle className="h-4 w-4 animate-spin" aria-hidden="true" />
        Loading server-authoritative finance data…
      </div>
    );
  }
  const failed = queries.find((query) => query.isError);
  if (!failed) return children;
  return <FinanceError error={failed.error} retry={failed.refetch} />;
}

export function FinanceError({
  error,
  retry,
  compact = false,
}: {
  error: Error | null;
  retry?: () => unknown;
  compact?: boolean;
}) {
  if (!error) return null;
  const apiError = error instanceof ApiError ? error : null;
  const denied = apiError?.status === 401 || apiError?.status === 403 || apiError?.status === 404;
  const Icon = denied ? Ban : AlertTriangle;
  const detail = apiError?.code ? problemCopy[apiError.code] : null;
  const reference = apiError ? safeReference(apiError) : null;
  return (
    <div
      className={`${compact ? "" : "m-6"} rounded-lg border border-destructive/30 bg-destructive/5 p-4`}
      role="alert"
      tabIndex={-1}
    >
      <div className="flex gap-3">
        <Icon className="mt-0.5 h-5 w-5 shrink-0 text-destructive" aria-hidden="true" />
        <div>
          <p className="font-medium">
            {denied ? "This finance record is unavailable" : "Finance data could not be loaded"}
          </p>
          <p className="mt-1 text-sm text-muted-foreground">
            {detail ?? "Try again. Cached UI state is never treated as readiness or approval."}
            {reference ? ` Reference: ${reference}.` : ""}
          </p>
          {retry && (
            <Button className="mt-3" size="sm" variant="outline" onClick={() => void retry()}>
              <RefreshCcw className="mr-1.5 h-4 w-4" aria-hidden="true" />
              Refresh current version
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}

export function FinanceNav() {
  return (
    <nav aria-label="Finance workflow" className="flex flex-wrap gap-2">
      <Link
        to="/finance"
        search={{ monthId: undefined, invoiceId: undefined, packageId: undefined }}
        className="rounded-md border px-3 py-2 text-sm font-medium hover:bg-muted"
      >
        Workspace
      </Link>
      <Link
        to="/finance/procurement"
        search={{ invoiceId: undefined }}
        className="rounded-md border px-3 py-2 text-sm font-medium hover:bg-muted"
      >
        Procurement
      </Link>
      <Link
        to="/finance/reports"
        className="rounded-md border px-3 py-2 text-sm font-medium hover:bg-muted"
      >
        Reports
      </Link>
    </nav>
  );
}

export function PermissionNotice({
  permissions,
  required,
  children,
}: {
  permissions: FinancePermission[];
  required: FinancePermission;
  children: ReactNode;
}) {
  if (permissions.includes(required)) return children;
  return (
    <div className="rounded-lg border border-dashed p-4 text-sm" role="note">
      <p className="flex items-center gap-2 font-medium">
        <ShieldCheck className="h-4 w-4" aria-hidden="true" />
        Read-only for this authority
      </p>
      <p className="mt-1 text-muted-foreground">
        The server did not grant {required.replaceAll("_", " ").toLowerCase()}. Hidden controls are
        presentation only; the API remains authoritative.
      </p>
    </div>
  );
}

export function EmptyFinanceState({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="rounded-lg border border-dashed p-6 text-center">
      <Clock3 className="mx-auto h-6 w-6 text-muted-foreground" aria-hidden="true" />
      <p className="mt-2 font-medium">{title}</p>
      <p className="mt-1 text-sm text-muted-foreground">{detail}</p>
    </div>
  );
}

export function DashboardQueues({
  queues,
}: {
  queues: FinanceDashboard["queues"];
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Authorized work queues</CardTitle>
      </CardHeader>
      <CardContent>
        {queues.length === 0 ? (
          <EmptyFinanceState
            title="No queued finance work"
            detail="The server returned no actionable queue for this authority."
          />
        ) : (
          <ul className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            {queues.map((queue) => {
              const actionPath = safeFinanceActionPath(queue.actionPath);
              return (
                <li key={queue.key} className="rounded-md border p-3">
                  <div className="flex items-center justify-between gap-3">
                    <span className="font-medium">{queue.label}</span>
                    <span
                      className="rounded-full bg-muted px-2 py-0.5 text-sm font-semibold"
                      aria-label={`${queue.count} items`}
                    >
                      {queue.count}
                    </span>
                  </div>
                  {actionPath ? (
                    <a
                      className="mt-3 inline-block text-sm font-medium text-primary hover:underline"
                      href={actionPath}
                    >
                      Open scoped queue
                    </a>
                  ) : (
                    <p className="mt-3 text-xs text-muted-foreground">
                      No authorized action path was supplied.
                    </p>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}

export function CursorPagination({
  hasPrevious,
  nextCursor,
  onPrevious,
  onNext,
  label,
}: {
  hasPrevious: boolean;
  nextCursor: string | null;
  onPrevious: () => void;
  onNext: (cursor: string) => void;
  label: string;
}) {
  if (!hasPrevious && !nextCursor) return null;
  return (
    <nav className="mt-4 flex items-center justify-end gap-2" aria-label={`${label} pages`}>
      <Button type="button" size="sm" variant="outline" disabled={!hasPrevious} onClick={onPrevious}>
        Previous
      </Button>
      <Button
        type="button"
        size="sm"
        variant="outline"
        disabled={!nextCursor}
        onClick={() => {
          if (nextCursor) onNext(nextCursor);
        }}
      >
        Next
      </Button>
    </nav>
  );
}

export function PaymentTimeline({
  permissions,
  events,
}: {
  permissions: FinancePermission[];
  events: PaymentEvent[];
}) {
  return (
    <Card>
      <CardHeader><CardTitle className="text-base">Payment status timeline</CardTitle></CardHeader>
      <CardContent>
        {!permissions.includes("PAYMENT_VIEW") ? (
          <EmptyFinanceState
            title="Payment timeline restricted"
            detail="The server did not grant payment-view authority. No payment status detail is displayed."
          />
        ) : events.length === 0 ? (
          <EmptyFinanceState
            title="No payment events"
            detail="PAID is an AP/ERP/manual status only; this application never moves money."
          />
        ) : (
          <ol className="space-y-3">
            {events.map((event) => (
              <li key={event.paymentEventId} className="rounded-md border p-3 text-sm">
                <div className="flex items-center justify-between gap-2">
                  <span>{formatDateTime(event.statusAt)}</span>
                  <StatusBadge status={event.status} />
                </div>
                <p className="mt-1 text-xs text-muted-foreground">
                  {event.source} · {event.provenance} · recorded {formatDateTime(event.recordedAt)}
                </p>
                {event.comment && <p className="mt-1 text-xs">{event.comment}</p>}
              </li>
            ))}
          </ol>
        )}
      </CardContent>
    </Card>
  );
}

export function SourceFacts({ source }: { source: SourceReference }) {
  return (
    <dl className="grid gap-3 text-xs sm:grid-cols-2 lg:grid-cols-4">
      <Fact label="Source" value={`${source.sourceType} · ${source.sourceId}`} />
      <Fact label="Version" value={source.version} mono />
      <Fact label="Provenance" value={source.provenance} />
      <Fact label="Time mode" value={source.temporalMode} />
      <Fact label="Freshness" value={source.freshness} badge />
      <Fact label="Represented at" value={formatDateTime(source.representedAt)} />
      <Fact label="Recorded at" value={formatDateTime(source.recordedAt)} />
      <Fact
        label="Checksum"
        value={source.checksum ? shortenHash(source.checksum) : "Not supplied"}
        mono
      />
    </dl>
  );
}

export function ReadinessChecklist({ rules }: { rules: ReadinessRule[] }) {
  if (rules.length === 0) {
    return (
      <EmptyFinanceState
        title="No readiness run"
        detail="Run the server evaluation against the exact invoice, package and F04 source versions."
      />
    );
  }
  return (
    <ol className="space-y-3">
      {rules.map((rule) => (
        <li key={rule.ruleId} className="rounded-md border p-3">
          <div className="flex flex-wrap items-start justify-between gap-2">
            <div>
              <p className="font-medium">
                {rule.pillar} · {rule.label}
              </p>
              <p className="text-xs text-muted-foreground">
                {rule.mandatory ? "Mandatory rule" : "Non-blocking rule"} · owner{" "}
                {rule.ownerDisplay}
              </p>
            </div>
            <StatusBadge status={rule.status} />
          </div>
          {rule.source && <div className="mt-3"><SourceFacts source={rule.source} /></div>}
          {rule.exceptionId && (
            <p className="mt-2 text-xs font-medium">
              Procurement exception {rule.exceptionId}; expires{" "}
              {formatDateTime(rule.exceptionExpiresAt)}. This is not confirmed evidence.
            </p>
          )}
          {rule.remediationLabel && (
            <p className="mt-2 text-xs text-muted-foreground">
              Next action: {rule.remediationLabel}
            </p>
          )}
        </li>
      ))}
    </ol>
  );
}

export function ConfigurationCard({
  label,
  status,
}: {
  label: string;
  status: string;
}) {
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm">{label}</CardTitle>
      </CardHeader>
      <CardContent>
        <StatusBadge status={status} />
      </CardContent>
    </Card>
  );
}

export function Fact({
  label,
  value,
  mono = false,
  badge = false,
}: {
  label: string;
  value: string;
  mono?: boolean;
  badge?: boolean;
}) {
  return (
    <div>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className={`mt-0.5 break-words ${mono ? "font-mono" : ""}`}>
        {badge ? <StatusBadge status={value} /> : value}
      </dd>
    </div>
  );
}

export function VersionBanner({
  version,
  stale,
  readOnly,
  updatedAt,
}: {
  version: number;
  stale: boolean;
  readOnly: boolean;
  updatedAt: string;
}) {
  return (
    <div
      className={`rounded-lg border p-3 text-sm ${stale ? "border-warning/40 bg-warning/5" : "bg-muted/30"}`}
      role={stale ? "alert" : "status"}
    >
      Server version <strong>v{version}</strong> · {readOnly ? "read-only" : "editable when permitted"}{" "}
      · updated {formatDateTime(updatedAt)}. {stale && "Refresh before any consequential action."}
    </div>
  );
}
