import { createFileRoute } from "@tanstack/react-router";
import { format } from "date-fns";
import { Clock3, LogIn, LogOut, TriangleAlert } from "lucide-react";

import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { AttendanceEmployeeScope } from "@/features/workforce/attendance-scope";
import {
  useAttendanceDays,
  usePunch,
} from "@/features/workforce/hooks";
import {
  MutationError,
  WorkforceQueryBoundary,
} from "@/features/workforce/query-boundary";
import {
  attendanceAction,
  formatMinutes,
  hasMissingPunch,
} from "@/features/workforce/presentation";
import { requireWorkforceRoute } from "@/lib/workforce-route";

export const Route = createFileRoute("/attendance/today")({
  beforeLoad: requireWorkforceRoute,
  head: () => ({
    meta: [{ title: "Today's attendance — Cadence" }],
  }),
  component: TodayPage,
});

function TodayPage() {
  return (
    <div>
      <PageHeader
        title="Today's attendance"
        description="Server-recorded check-in and check-out. Open sessions never create an assumed checkout or worked duration."
      />
      <div className="p-6">
        <AttendanceEmployeeScope>
          {(employeeId) => <TodayCard employeeId={employeeId} />}
        </AttendanceEmployeeScope>
      </div>
    </div>
  );
}

function TodayCard({ employeeId }: { employeeId: string }) {
  const today = format(new Date(), "yyyy-MM-dd");
  const daysQuery = useAttendanceDays(employeeId, today, today);
  const day = daysQuery.data?.[0];
  const punch = usePunch(employeeId, today);
  const action = attendanceAction(day);
  const readOnly = action.action === "READ_ONLY";

  return (
    <WorkforceQueryBoundary queries={[daysQuery]}>
      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Clock3 className="h-4 w-4" />
              {today}
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-5">
            <div className="grid gap-3 sm:grid-cols-3">
              <Metric
                label="Calendar"
                value={day?.expectedClassification ?? "Not generated"}
              />
              <Metric
                label="Expected"
                value={formatMinutes(day?.expectedMinutes)}
              />
              <Metric
                label="Recorded"
                value={formatMinutes(
                  day && hasMissingPunch(day) ? undefined : day?.netMinutes,
                )}
              />
            </div>

            {day && (
              <div className="flex flex-wrap gap-2">
                <StatusBadge status={day.finalStatus} />
                <StatusBadge status={day.sourceMode} />
                {day.exceptionCode && (
                  <StatusBadge status={day.exceptionCode} />
                )}
              </div>
            )}

            {day &&
              hasMissingPunch(day) &&
              action.action !== "CHECK_OUT" && (
              <Alert className="border-warning/40 bg-warning/10">
                <TriangleAlert className="h-4 w-4" />
                <AlertTitle>Missing punch requires resolution</AlertTitle>
                <AlertDescription>
                  Worked time remains unresolved. Submit a regularization; no
                  checkout or duration has been synthesized.
                </AlertDescription>
              </Alert>
              )}

            {action.action === "CHECK_OUT" && (
              <Alert>
                <Clock3 className="h-4 w-4" />
                <AlertTitle>Session in progress</AlertTitle>
                <AlertDescription>
                  The current session has no checkout yet, so worked duration
                  remains unresolved until you check out.
                </AlertDescription>
              </Alert>
            )}

            {readOnly && (
              <Alert>
                <TriangleAlert className="h-4 w-4" />
                <AlertTitle>External source configured</AlertTitle>
                <AlertDescription>
                  Internal punches are disabled. greytHR tenant capability is
                  not certified by this application and no provider call is
                  being made.
                </AlertDescription>
              </Alert>
            )}

            <MutationError error={punch.error} />
            <div className="flex flex-wrap gap-2">
              <Button
                disabled={
                  readOnly ||
                  punch.isPending ||
                  action.action !== "CHECK_IN"
                }
                onClick={() => punch.mutate("CHECK_IN")}
              >
                <LogIn className="mr-2 h-4 w-4" /> Check in
              </Button>
              <Button
                variant="outline"
                disabled={
                  readOnly ||
                  punch.isPending ||
                  action.action !== "CHECK_OUT"
                }
                onClick={() => punch.mutate("CHECK_OUT")}
              >
                <LogOut className="mr-2 h-4 w-4" /> Check out
              </Button>
            </div>
            <p className="text-xs text-muted-foreground">
              Actions use server time and an idempotency key. The backend is
              authoritative for employment, source, open-session and close
              validation.
            </p>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Day calculation</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            {day ? (
              <>
                <Metric
                  label="Calculation version"
                  value={String(day.calculationVersion)}
                />
                <Metric label="Computed at" value={day.computedAt} />
                <Metric
                  label="Leave"
                  value={
                    day.leaveUnits
                      ? `${day.leaveUnits} · ${day.leaveTypeCode ?? "Unspecified"}`
                      : "None"
                  }
                />
              </>
            ) : (
              <p className="text-muted-foreground">
                No attendance day has been generated for this date.
              </p>
            )}
          </CardContent>
        </Card>
      </div>
    </WorkforceQueryBoundary>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-xs uppercase tracking-wide text-muted-foreground">
        {label}
      </p>
      <p className="mt-1 font-medium">{value}</p>
    </div>
  );
}
