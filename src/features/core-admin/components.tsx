import {
  AlertCircle,
  Ban,
  DatabaseZap,
  LoaderCircle,
  RefreshCw,
} from "lucide-react";
import type { ReactNode } from "react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ApiError } from "@/lib/api-client";
import { safeErrorPresentation } from "@/lib/safe-error";

export type QueryLike = {
  isPending: boolean;
  isError: boolean;
  error: Error | null;
  refetch?: () => Promise<unknown>;
};

export function CoreAdminBoundary({
  query,
  authorized = true,
  empty = false,
  emptyTitle = "Nothing configured yet",
  emptyDescription = "Create the first record when this scope is ready.",
  children,
}: {
  query?: QueryLike;
  authorized?: boolean;
  empty?: boolean;
  emptyTitle?: string;
  emptyDescription?: string;
  children: ReactNode;
}) {
  if (!authorized) {
    return (
      <StatePanel
        icon={<Ban className="h-5 w-5" aria-hidden="true" />}
        title="Permission denied"
        description="Your current authority does not include this administration capability. No record details were loaded."
        role="alert"
      />
    );
  }
  if (empty) {
    return (
      <StatePanel
        icon={<DatabaseZap className="h-5 w-5" aria-hidden="true" />}
        title={emptyTitle}
        description={emptyDescription}
      />
    );
  }
  if (query?.isPending) {
    return (
      <StatePanel
        icon={<LoaderCircle className="h-5 w-5 animate-spin" aria-hidden="true" />}
        title="Loading authorized records"
        description="Resolving the active scope and current server version…"
        role="status"
      />
    );
  }
  if (query?.isError) {
    const presentation = safeErrorPresentation(query.error);
    return (
      <StatePanel
        icon={<AlertCircle className="h-5 w-5" aria-hidden="true" />}
        title="Records could not be loaded"
        description={`${presentation.message}${presentation.correlationId ? ` Support reference: ${presentation.correlationId}` : ""}`}
        role="alert"
        action={
          query.refetch ? (
            <Button variant="outline" onClick={() => void query.refetch?.()}>
              <RefreshCw className="mr-2 h-4 w-4" aria-hidden="true" />
              Retry
            </Button>
          ) : null
        }
      />
    );
  }
  return children;
}

export function MutationNotice({
  error,
  pending,
  onReload,
}: {
  error: Error | null;
  pending: boolean;
  onReload?: () => void;
}) {
  if (pending) {
    return (
      <Alert>
        <LoaderCircle className="h-4 w-4 animate-spin" aria-hidden="true" />
        <AlertTitle>Saving</AlertTitle>
        <AlertDescription>
          Waiting for the server to validate authority and version…
        </AlertDescription>
      </Alert>
    );
  }
  if (!error) return null;
  const apiError = error instanceof ApiError ? error : null;
  const stale =
    apiError?.status === 409 ||
    apiError?.status === 412 ||
    apiError?.code === "STALE_VERSION";
  const denied = apiError?.status === 403 || apiError?.status === 404;
  const presentation = safeErrorPresentation(error);
  return (
    <Alert variant="destructive" role="alert">
      <AlertCircle className="h-4 w-4" aria-hidden="true" />
      <AlertTitle>
        {stale
          ? "This record changed"
          : denied
            ? "Action not permitted"
            : "Change was not saved"}
      </AlertTitle>
      <AlertDescription>
        <p>
          {stale
            ? "A newer server version exists. Reload it before comparing and trying again; this screen will never overwrite it."
            : presentation.message}
        </p>
        {presentation.correlationId ? (
          <p className="mt-1 font-mono text-xs">
            Support reference: {presentation.correlationId}
          </p>
        ) : null}
        {stale && onReload ? (
          <Button
            className="mt-3"
            size="sm"
            variant="outline"
            onClick={onReload}
          >
            Reload current version
          </Button>
        ) : null}
      </AlertDescription>
    </Alert>
  );
}

function StatePanel({
  icon,
  title,
  description,
  action,
  role,
}: {
  icon: ReactNode;
  title: string;
  description: string;
  action?: ReactNode;
  role?: "alert" | "status";
}) {
  return (
    <div
      className="m-6 grid min-h-52 place-items-center rounded-lg border border-dashed p-8 text-center"
      role={role}
    >
      <div className="max-w-lg">
        <div className="mx-auto grid h-10 w-10 place-items-center rounded-full bg-muted text-muted-foreground">
          {icon}
        </div>
        <h2 className="mt-3 font-semibold">{title}</h2>
        <p className="mt-1 text-sm text-muted-foreground">{description}</p>
        {action ? <div className="mt-4">{action}</div> : null}
      </div>
    </div>
  );
}

export function StaleDataNotice({
  updatedAt,
  staleAfterMinutes = 15,
}: {
  updatedAt?: string | null;
  staleAfterMinutes?: number;
}) {
  if (!updatedAt) return null;
  const age = Date.now() - new Date(updatedAt).getTime();
  if (age <= staleAfterMinutes * 60_000) return null;
  return (
    <Alert>
      <RefreshCw className="h-4 w-4" aria-hidden="true" />
      <AlertTitle>Stale data</AlertTitle>
      <AlertDescription>
        This view was last refreshed at {new Date(updatedAt).toLocaleString()}.
        Reload before making a consequential change.
      </AlertDescription>
    </Alert>
  );
}
