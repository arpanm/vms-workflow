import { createFileRoute } from "@tanstack/react-router";
import { CalendarSync, History, ShieldAlert } from "lucide-react";
import { useState } from "react";

import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import {
  CoreAdminBoundary,
  MutationNotice,
} from "@/features/core-admin/components";
import {
  useMonthTransitions,
  useTransitionMonth,
} from "@/features/core-admin/hooks";
import { administrativeMonthTransitions } from "@/features/core-admin/month-state";
import { coreAdminPermissions } from "@/features/core-admin/permissions";
import { useActiveScope } from "@/features/core-admin/scope-provider";

export const Route = createFileRoute("/administration/months")({
  head: () => ({
    meta: [
      { title: "Month governance — Cadence" },
      {
        name: "description",
        content:
          "Guarded engagement-month transitions and append-only transition history.",
      },
    ],
  }),
  component: MonthGovernancePage,
});

function MonthGovernancePage() {
  const scope = useActiveScope();
  const month = scope.month;
  const authorized = scope.can(coreAdminPermissions.monthTransition);
  const historyQuery = useMonthTransitions(month?.id ?? "", authorized);
  const mutation = useTransitionMonth(
    scope.selection.engagementId,
    month?.id ?? "",
  );
  const [targetState, setTargetState] = useState("");
  const [reason, setReason] = useState("");
  const allowed = month ? administrativeMonthTransitions(month.state) : [];

  function transition() {
    if (!month || !targetState || !reason.trim()) return;
    mutation.mutate(
      {
        targetState,
        reason: reason.trim(),
        expectedVersion: month.governanceVersion,
      },
      {
        onSuccess: () => {
          setTargetState("");
          setReason("");
        },
      },
    );
  }

  return (
    <div>
      <PageHeader
        title="Month governance"
        description="Only explicitly allowed administrative transitions are shown. The server rechecks authority, preconditions and governance version."
      />
      <CoreAdminBoundary
        authorized={authorized}
        query={historyQuery}
        empty={!month}
        emptyTitle="No engagement month"
        emptyDescription="The active engagement has no authorized month to govern."
      >
        {month ? (
          <div className="grid gap-5 p-6 xl:grid-cols-[1fr_1.5fr]">
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2 text-base">
                  <CalendarSync className="h-4 w-4" aria-hidden="true" />
                  Controlled transition
                </CardTitle>
              </CardHeader>
              <CardContent className="space-y-4">
                <div className="flex items-center justify-between rounded-md border p-3">
                  <div>
                    <p className="text-xs text-muted-foreground">
                      {month.monthStartDate} · governance version{" "}
                      {month.governanceVersion}
                    </p>
                    <p className="font-medium">Current state</p>
                  </div>
                  <StatusBadge status={month.state} />
                </div>
                {allowed.length ? (
                  <>
                    <div>
                      <Label htmlFor="target-month-state">Allowed transition</Label>
                      <Select
                        value={targetState}
                        onValueChange={setTargetState}
                      >
                        <SelectTrigger id="target-month-state">
                          <SelectValue placeholder="Choose target state" />
                        </SelectTrigger>
                        <SelectContent>
                          {allowed.map((state) => (
                            <SelectItem key={state} value={state}>
                              {state.replaceAll("_", " ")}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </div>
                    <div>
                      <Label htmlFor="transition-reason">
                        Reason and impact declaration
                      </Label>
                      <Textarea
                        id="transition-reason"
                        value={reason}
                        onChange={(event) => setReason(event.target.value)}
                        placeholder="Explain the authority, business reason and downstream impact."
                        rows={4}
                        required
                      />
                    </div>
                    <MutationNotice
                      error={mutation.error}
                      pending={mutation.isPending}
                      onReload={() => {
                        void historyQuery.refetch();
                      }}
                    />
                    <AlertDialog>
                      <AlertDialogTrigger asChild>
                        <Button
                          variant={
                            targetState === "REOPEN_REQUESTED"
                              ? "destructive"
                              : "default"
                          }
                          disabled={
                            mutation.isPending ||
                            !targetState ||
                            reason.trim().length < 8
                          }
                        >
                          Review transition
                        </Button>
                      </AlertDialogTrigger>
                      <AlertDialogContent>
                        <AlertDialogHeader>
                          <AlertDialogTitle>
                            Transition {month.state} → {targetState}?
                          </AlertDialogTitle>
                          <AlertDialogDescription>
                            This writes append-only transition and audit evidence
                            against governance version {month.governanceVersion}.
                            Reopen requests may invalidate downstream evidence after
                            approval; this action does not rewrite prior packages.
                          </AlertDialogDescription>
                        </AlertDialogHeader>
                        <div className="rounded-md bg-muted p-3 text-sm">{reason}</div>
                        <AlertDialogFooter>
                          <AlertDialogCancel>Cancel</AlertDialogCancel>
                          <AlertDialogAction onClick={transition}>
                            Confirm guarded transition
                          </AlertDialogAction>
                        </AlertDialogFooter>
                      </AlertDialogContent>
                    </AlertDialog>
                  </>
                ) : (
                  <div className="rounded-md border border-dashed p-4 text-sm text-muted-foreground">
                    <p className="flex items-center gap-2 font-medium text-foreground">
                      <ShieldAlert className="h-4 w-4" aria-hidden="true" />
                      No administrative transition available
                    </p>
                    <p className="mt-1">
                      This state advances through its owning workflow or requires
                      approval outside core administration.
                    </p>
                  </div>
                )}
              </CardContent>
            </Card>
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2 text-base">
                  <History className="h-4 w-4" aria-hidden="true" />
                  Append-only transition history
                </CardTitle>
              </CardHeader>
              <CardContent>
                {historyQuery.data?.length ? (
                  <ol className="relative space-y-4 border-l pl-5">
                    {[...historyQuery.data].reverse().map((event) => (
                      <li key={event.id}>
                        <span className="absolute -left-1.5 mt-1.5 h-3 w-3 rounded-full border bg-background" />
                        <div className="flex flex-wrap items-center gap-2">
                          <StatusBadge status={event.fromState} />
                          <span aria-hidden="true">→</span>
                          <StatusBadge status={event.toState} />
                          <span className="text-xs text-muted-foreground">
                            v{event.fromVersion} → v{event.toVersion}
                          </span>
                        </div>
                        <p className="mt-2 text-sm">{event.reason}</p>
                        <p className="mt-1 text-xs text-muted-foreground">
                          {event.actorSubject} ·{" "}
                          {new Date(event.transitionedAt).toLocaleString()}
                          {event.correlationId
                            ? ` · reference ${event.correlationId}`
                            : ""}
                        </p>
                      </li>
                    ))}
                  </ol>
                ) : (
                  <p className="rounded-md border border-dashed p-6 text-center text-sm text-muted-foreground">
                    No administrative transition history has been recorded.
                  </p>
                )}
              </CardContent>
            </Card>
          </div>
        ) : null}
      </CoreAdminBoundary>
    </div>
  );
}
