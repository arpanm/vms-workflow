import type { ReactNode } from "react";
import {
  AlertCircle,
  Ban,
  LoaderCircle,
  RefreshCcw,
  ShieldX,
} from "lucide-react";

import { Button } from "@/components/ui/button";
import { ApiError } from "@/lib/api-client";

import { classifyWorkforceError } from "./presentation";

type QueryLike = {
  isPending: boolean;
  isError: boolean;
  error: Error | null;
  refetch?: () => unknown;
};

const messages = {
  unauthenticated: {
    title: "Your session has expired",
    detail: "Sign in again before accessing workforce records.",
    icon: ShieldX,
  },
  unauthorized: {
    title: "You do not have access",
    detail:
      "This record is outside your authorized organization, engagement, project, or self-service scope.",
    icon: Ban,
  },
  conflict: {
    title: "The record changed",
    detail:
      "Refresh the latest state before retrying. No attendance or leave outcome was assumed.",
    icon: RefreshCcw,
  },
  "not-found": {
    title: "Record unavailable",
    detail: "The record does not exist or is outside your authorized scope.",
    icon: Ban,
  },
  unavailable: {
    title: "Workforce service unavailable",
    detail: "Try again. No local fallback data has been substituted.",
    icon: AlertCircle,
  },
  unexpected: {
    title: "Data could not be loaded",
    detail: "The backend returned an unexpected response.",
    icon: AlertCircle,
  },
} as const;

export function WorkforceQueryBoundary({
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
      >
        <LoaderCircle className="h-4 w-4 animate-spin" />
        Loading workforce data…
      </div>
    );
  }

  const failed = queries.find((query) => query.isError);
  if (!failed) return children;

  const kind = classifyWorkforceError(failed.error);
  const message = messages[kind];
  const Icon = message.icon;
  const correlationId =
    failed.error instanceof ApiError ? failed.error.correlationId : undefined;

  return (
    <div className="m-6 rounded-lg border border-destructive/30 bg-destructive/5 p-5">
      <div className="flex gap-3">
        <Icon className="mt-0.5 h-5 w-5 text-destructive" />
        <div>
          <p className="font-medium">{message.title}</p>
          <p className="mt-1 text-sm text-muted-foreground">
            {message.detail}
            {correlationId ? ` Reference: ${correlationId}` : ""}
          </p>
          {failed.refetch && (
            <Button
              className="mt-4"
              variant="outline"
              size="sm"
              onClick={() => void failed.refetch?.()}
            >
              Retry
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}

export function MutationError({ error }: { error: Error | null }) {
  if (!error) return null;
  const kind = classifyWorkforceError(error);
  return (
    <div
      className="rounded-md border border-destructive/30 bg-destructive/5 p-3 text-sm"
      role="alert"
    >
      <p className="font-medium">{messages[kind].title}</p>
      <p className="mt-1 text-xs text-muted-foreground">
        {messages[kind].detail}
        {error instanceof ApiError && error.correlationId
          ? ` Reference: ${error.correlationId}`
          : ""}
      </p>
    </div>
  );
}
