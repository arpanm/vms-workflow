import { useEffect, useRef, type ReactNode, type RefObject } from "react";
import { AlertTriangle, Ban, LoaderCircle, LockKeyhole, RefreshCcw, ShieldX } from "lucide-react";

import { Button } from "@/components/ui/button";
import { ApiError } from "@/lib/api-client";

import { classifyCertificationError } from "./presentation";

type QueryLike = {
  isPending: boolean;
  isError: boolean;
  error: Error | null;
  refetch?: () => unknown;
};

const messages = {
  unauthenticated: {
    title: "Your session has expired",
    detail: "Sign in again to request server-authorized certification data.",
    icon: ShieldX,
  },
  permission: {
    title: "This record is unavailable",
    detail:
      "It does not exist or is outside your active organization, engagement, or project scope.",
    icon: Ban,
  },
  "not-found": {
    title: "This record is unavailable",
    detail:
      "It does not exist or is outside your active organization, engagement, or project scope.",
    icon: Ban,
  },
  "version-conflict": {
    title: "A newer version is available",
    detail:
      "Refresh and review the exact current version before acting. Nothing was inferred or overwritten.",
    icon: RefreshCcw,
  },
  locked: {
    title: "This version is locked",
    detail:
      "The server has locked this evidence. Use clarification, correction, or reopen when authorized.",
    icon: LockKeyhole,
  },
  validation: {
    title: "The server did not accept this request",
    detail:
      "Resolve the named fields or blockers. No submission, certification, or confirmation was assumed.",
    icon: AlertTriangle,
  },
  unavailable: {
    title: "Certification service unavailable",
    detail: "Try again. Cached UI state is never treated as readiness or approval.",
    icon: AlertTriangle,
  },
  unexpected: {
    title: "Certification data could not be loaded",
    detail: "The server returned an unexpected response. No business outcome was inferred.",
    icon: AlertTriangle,
  },
} as const;

const curatedProblemCodes: Record<string, string> = {
  NOT_FOUND: "The record is unavailable.",
  FORBIDDEN: "The current authority cannot perform this action.",
  VERSION_CONFLICT: "The expected server version is no longer current.",
  MONTH_LOCKED: "The month is locked.",
  SUBMISSION_LOCKED: "The submission is locked.",
  VALIDATION_FAILED: "One or more submitted fields are invalid.",
  READINESS_BLOCKED: "Server readiness checks still have blockers.",
  IDEMPOTENCY_KEY_REUSED: "This retry does not match the original user intent.",
  PROVIDER_NOT_CONFIGURED: "The controlled provider integration is not configured.",
};

function curatedProblemDetail(error: ApiError | null) {
  if (!error?.code) return null;
  return curatedProblemCodes[error.code] ?? null;
}

function safeCorrelationId(error: ApiError | null) {
  const correlationId = error?.correlationId;
  return correlationId && /^[a-z0-9][a-z0-9._:-]{0,127}$/i.test(correlationId)
    ? correlationId
    : null;
}

export function CertificationQueryBoundary({
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
        Loading the server-authoritative version…
      </div>
    );
  }

  const failed = queries.find((query) => query.isError);
  if (!failed) return children;
  return <CertificationError error={failed.error} retry={failed.refetch} />;
}

export function CertificationMutationError({ error }: { error: Error | null }) {
  const alertRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (error) {
      window.requestAnimationFrame(() => alertRef.current?.focus());
    }
  }, [error]);
  if (!error) return null;
  return <CertificationError error={error} compact alertRef={alertRef} />;
}

function CertificationError({
  error,
  retry,
  compact = false,
  alertRef,
}: {
  error: Error | null;
  retry?: () => unknown;
  compact?: boolean;
  alertRef?: RefObject<HTMLDivElement | null>;
}) {
  const kind = classifyCertificationError(error);
  const message = messages[kind];
  const Icon = message.icon;
  const apiError = error instanceof ApiError ? error : null;
  const problemDetail = curatedProblemDetail(apiError);
  const correlationId = safeCorrelationId(apiError);
  return (
    <div
      ref={alertRef}
      className={`${compact ? "" : "m-6"} rounded-lg border border-destructive/30 bg-destructive/5 p-4`}
      role="alert"
      tabIndex={compact ? -1 : undefined}
    >
      <div className="flex gap-3">
        <Icon className="mt-0.5 h-5 w-5 shrink-0 text-destructive" aria-hidden="true" />
        <div>
          <p className="font-medium">{message.title}</p>
          <p className="mt-1 text-sm text-muted-foreground">
            {message.detail}
            {problemDetail ? ` ${problemDetail}` : ""}
            {correlationId ? ` Reference: ${correlationId}` : ""}
          </p>
          {retry && (
            <Button className="mt-3" size="sm" variant="outline" onClick={() => void retry()}>
              Refresh current version
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}
