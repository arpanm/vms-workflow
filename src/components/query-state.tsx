import type { ReactNode } from "react";
import { AlertCircle, LoaderCircle } from "lucide-react";

import { ApiError } from "@/lib/api-client";

export type QueryStateLike = {
  isPending: boolean;
  isError: boolean;
  error: Error | null;
};

export function QueryState({
  queries,
  children,
}: {
  queries: QueryStateLike[];
  children: ReactNode;
}) {
  if (queries.some((query) => query.isPending)) {
    return (
      <div
        className="m-6 flex min-h-48 items-center justify-center gap-2 rounded-lg border border-dashed text-sm text-muted-foreground"
        role="status"
      >
        <LoaderCircle className="h-4 w-4 animate-spin" />
        Loading data…
      </div>
    );
  }

  const failed = queries.find((query) => query.isError);
  if (failed) {
    const correlationId =
      failed.error instanceof ApiError ? failed.error.correlationId : undefined;
    return (
      <div
        className="m-6 rounded-lg border border-destructive/30 bg-destructive/10 p-5 text-sm text-destructive"
        role="alert"
      >
        <p className="flex items-center gap-2 font-medium">
          <AlertCircle className="h-4 w-4" />
          Data could not be loaded.
        </p>
        <p className="mt-1 text-xs">
          {failed.error?.message ?? "The backend returned an unexpected error."}
          {correlationId ? ` Reference: ${correlationId}` : ""}
        </p>
      </div>
    );
  }

  return children;
}
