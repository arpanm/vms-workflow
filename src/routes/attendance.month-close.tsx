import { createFileRoute } from "@tanstack/react-router";
import { CheckCircle2, LockKeyhole, TriangleAlert } from "lucide-react";

import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { useSnapshots } from "@/features/workforce/hooks";
import { WorkforceQueryBoundary } from "@/features/workforce/query-boundary";
import { MonthScope } from "@/features/workforce/scope-selectors";
import { requireWorkforceRoute } from "@/lib/workforce-route";

export const Route = createFileRoute("/attendance/month-close")({
  beforeLoad: requireWorkforceRoute,
  head: () => ({ meta: [{ title: "Attendance month status — Cadence" }] }),
  component: MonthStatusPage,
});

function MonthStatusPage() {
  return (
    <div>
      <PageHeader
        title="Attendance month status"
        description="Read-only snapshot lineage and close state for an authorized engagement month."
      />
      <div className="p-6">
        <MonthScope>
          {(engagementMonthId) => (
            <SnapshotStatus engagementMonthId={engagementMonthId} />
          )}
        </MonthScope>
      </div>
    </div>
  );
}

function SnapshotStatus({
  engagementMonthId,
}: {
  engagementMonthId: string;
}) {
  const query = useSnapshots(engagementMonthId);
  const snapshots = query.data ?? [];

  return (
    <WorkforceQueryBoundary queries={[query]}>
      {snapshots.length === 0 ? (
        <Card className="border-dashed">
          <CardContent className="grid min-h-48 place-items-center text-center">
            <div>
              <TriangleAlert className="mx-auto h-7 w-7 text-muted-foreground" />
              <p className="mt-3 font-medium">No attendance snapshot yet</p>
              <p className="mt-1 text-sm text-muted-foreground">
                The month remains unsnapshotted. No readiness is inferred.
              </p>
            </div>
          </CardContent>
        </Card>
      ) : (
        <div className="space-y-4">
          {snapshots
            .slice()
            .sort((a, b) => b.version - a.version)
            .map((snapshot) => (
              <Card key={snapshot.id}>
                <CardHeader>
                  <div className="flex items-center justify-between gap-3">
                    <CardTitle className="flex items-center gap-2 text-base">
                      {snapshot.status === "CLOSED" ? (
                        <LockKeyhole className="h-4 w-4" />
                      ) : (
                        <CheckCircle2 className="h-4 w-4" />
                      )}
                      Snapshot version {snapshot.version}
                    </CardTitle>
                    <StatusBadge status={snapshot.status} />
                  </div>
                </CardHeader>
                <CardContent className="grid gap-4 text-sm sm:grid-cols-3">
                  <Metric label="Employee days" value={snapshot.dayCount} />
                  <Metric
                    label="Closed"
                    value={snapshot.closedAt ?? "Not closed"}
                  />
                  <Metric
                    label="Reopened"
                    value={snapshot.reopenedAt ?? "No"}
                  />
                  {snapshot.supersedesId && (
                    <p className="sm:col-span-3 text-xs text-muted-foreground">
                      Supersedes snapshot {snapshot.supersedesId}
                    </p>
                  )}
                </CardContent>
              </Card>
            ))}
          <p className="text-xs text-muted-foreground">
            Close and reopen remain permission-gated backend operations. This
            view does not infer blocker resolution or mutate snapshot lineage.
          </p>
        </div>
      )}
    </WorkforceQueryBoundary>
  );
}

function Metric({ label, value }: { label: string; value: string | number }) {
  return (
    <div>
      <p className="text-xs uppercase tracking-wide text-muted-foreground">
        {label}
      </p>
      <p className="mt-1 font-medium">{value}</p>
    </div>
  );
}
