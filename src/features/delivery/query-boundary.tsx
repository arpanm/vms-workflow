import type { ReactNode } from "react";
import { AlertCircle, Ban, LoaderCircle, RefreshCcw, ShieldX } from "lucide-react";

import { Button } from "@/components/ui/button";
import { ApiError } from "@/lib/api-client";

import { classifyDeliveryError } from "./presentation";

type QueryLike = {
  isPending: boolean;
  isError: boolean;
  error: Error | null;
  refetch?: () => unknown;
};

const messages = {
  unauthenticated: {
    title: "Your session has expired",
    detail: "Sign in again before accessing delivery plans.",
    icon: ShieldX,
  },
  unauthorized: {
    title: "You do not have access",
    detail: "This plan or integration operation is outside your authorized scope.",
    icon: Ban,
  },
  conflict: {
    title: "The plan changed",
    detail:
      "Refresh the exact version and checksum before retrying. No approval or edit was assumed.",
    icon: RefreshCcw,
  },
  validation: {
    title: "The request was not accepted",
    detail: "Review the returned validation details. No plan or provider state was assumed.",
    icon: AlertCircle,
  },
  "not-found": {
    title: "Plan unavailable",
    detail: "The record does not exist or is outside your authorized scope.",
    icon: Ban,
  },
  unavailable: {
    title: "Delivery service unavailable",
    detail: "Try again. No provider or plan data has been substituted.",
    icon: AlertCircle,
  },
  unexpected: {
    title: "Data could not be loaded",
    detail: "The backend returned an unexpected response.",
    icon: AlertCircle,
  },
} as const;

export function DeliveryQueryBoundary({
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
        Loading delivery planning data…
      </div>
    );
  }

  const failed = queries.find((query) => query.isError);
  if (!failed) return children;
  const kind = classifyDeliveryError(failed.error);
  const message = messages[kind];
  const Icon = message.icon;
  const correlationId = failed.error instanceof ApiError ? failed.error.correlationId : undefined;

  return (
    <div className="m-6 rounded-lg border border-destructive/30 bg-destructive/5 p-5" role="alert">
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

export function DeliveryMutationError({ error }: { error: Error | null }) {
  if (!error) return null;
  const kind = classifyDeliveryError(error);
  const message = messages[kind];
  return (
    <div
      className="rounded-md border border-destructive/30 bg-destructive/5 p-3 text-sm"
      role="alert"
    >
      <p className="font-medium">{message.title}</p>
      <p className="mt-1 text-xs text-muted-foreground">
        {message.detail}
        {kind === "conflict" && error instanceof ApiError ? ` ${error.message}` : ""}
        {kind === "validation" && error instanceof ApiError ? ` ${error.message}` : ""}
        {error instanceof ApiError && error.correlationId
          ? ` Reference: ${error.correlationId}`
          : ""}
      </p>
    </div>
  );
}
