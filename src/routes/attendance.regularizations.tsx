import { createFileRoute } from "@tanstack/react-router";
import { endOfMonth, format, startOfMonth } from "date-fns";
import { useState } from "react";

import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { AttendanceEmployeeScope } from "@/features/workforce/attendance-scope";
import type { CreateRegularizationInput } from "@/features/workforce/domain";
import {
  useAttendanceDays,
  useCreateRegularization,
  useRegularizations,
} from "@/features/workforce/hooks";
import {
  MutationError,
  WorkforceQueryBoundary,
} from "@/features/workforce/query-boundary";
import {
  type ValidationErrors,
  validateRegularization,
} from "@/features/workforce/presentation";
import { requireWorkforceRoute } from "@/lib/workforce-route";

export const Route = createFileRoute("/attendance/regularizations")({
  beforeLoad: requireWorkforceRoute,
  head: () => ({ meta: [{ title: "Regularizations — Cadence" }] }),
  component: RegularizationsPage,
});

function RegularizationsPage() {
  return (
    <div>
      <PageHeader
        title="Attendance regularizations"
        description="Request a reviewed correction while preserving the original punch and calculation evidence."
      />
      <div className="p-6">
        <AttendanceEmployeeScope>
          {(employeeId) => (
            <RegularizationWorkspace employeeId={employeeId} />
          )}
        </AttendanceEmployeeScope>
      </div>
    </div>
  );
}

function RegularizationWorkspace({ employeeId }: { employeeId: string }) {
  const now = new Date();
  const from = format(startOfMonth(now), "yyyy-MM-dd");
  const to = format(endOfMonth(now), "yyyy-MM-dd");
  const daysQuery = useAttendanceDays(employeeId, from, to);
  const requestsQuery = useRegularizations(employeeId);
  const mutation = useCreateRegularization(employeeId);
  const [value, setValue] = useState<CreateRegularizationInput>({
    employeeId,
    workDate: "",
    reasonCode: "",
    narrative: "",
    requestedOutcome: "",
    declarationAccepted: false,
  });
  const [errors, setErrors] =
    useState<ValidationErrors<CreateRegularizationInput>>({});

  const candidateDays = (daysQuery.data ?? []).filter(
    (day) =>
      day.workDate !== format(now, "yyyy-MM-dd") &&
      (day.exceptionCode ||
        ["ABSENT", "SHORT_HOURS_HALF_DAY_EXCEPTION"].includes(day.finalStatus)),
  );

  function submit(event: React.FormEvent) {
    event.preventDefault();
    const input = { ...value, employeeId };
    const nextErrors = validateRegularization(input);
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length) return;
    mutation.mutate(input, {
      onSuccess: () => {
        setValue({
          employeeId,
          workDate: "",
          reasonCode: "",
          narrative: "",
          requestedOutcome: "",
          declarationAccepted: false,
        });
        setErrors({});
      },
    });
  }

  return (
    <WorkforceQueryBoundary queries={[daysQuery, requestsQuery]}>
      <div className="grid gap-4 lg:grid-cols-5">
        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle className="text-base">Request correction</CardTitle>
          </CardHeader>
          <CardContent>
            <form className="space-y-4" onSubmit={submit}>
              <FormField label="Attendance date" error={errors.workDate}>
                <Select
                  value={value.workDate}
                  onValueChange={(workDate) =>
                    setValue((current) => ({ ...current, workDate }))
                  }
                >
                  <SelectTrigger>
                    <SelectValue placeholder="Choose an exception" />
                  </SelectTrigger>
                  <SelectContent>
                    {candidateDays.map((day) => (
                      <SelectItem
                        key={`${day.employeeId}:${day.workDate}`}
                        value={day.workDate}
                      >
                        {day.workDate} · {day.exceptionCode ?? day.finalStatus}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                {candidateDays.length === 0 && (
                  <p className="text-xs text-muted-foreground">
                    No exception days are available in the current month.
                  </p>
                )}
              </FormField>
              <FormField label="Reason" error={errors.reasonCode}>
                <Select
                  value={value.reasonCode}
                  onValueChange={(reasonCode) =>
                    setValue((current) => ({ ...current, reasonCode }))
                  }
                >
                  <SelectTrigger>
                    <SelectValue placeholder="Select reason" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="MISSED_CHECK_IN">
                      Missed check-in
                    </SelectItem>
                    <SelectItem value="MISSED_CHECK_OUT">
                      Missed check-out
                    </SelectItem>
                    <SelectItem value="OFFICIAL_WORK">
                      Official work outside system
                    </SelectItem>
                    <SelectItem value="SYSTEM_OUTAGE">
                      System outage
                    </SelectItem>
                    <SelectItem value="APPROVED_TRAVEL">
                      Approved travel or offsite
                    </SelectItem>
                    <SelectItem value="OTHER">Other</SelectItem>
                  </SelectContent>
                </Select>
              </FormField>
              <FormField
                label="Requested outcome"
                error={errors.requestedOutcome}
              >
                <Select
                  value={value.requestedOutcome}
                  onValueChange={(requestedOutcome) =>
                    setValue((current) => ({
                      ...current,
                      requestedOutcome,
                    }))
                  }
                >
                  <SelectTrigger>
                    <SelectValue placeholder="Select outcome" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="CORRECT_PUNCH">
                      Correct punch
                    </SelectItem>
                    <SelectItem value="CREDIT_FULL_DAY">
                      Credit full day
                    </SelectItem>
                    <SelectItem value="CREDIT_HALF_DAY">
                      Credit half day
                    </SelectItem>
                    <SelectItem value="MARK_OFFICIAL_DUTY">
                      Mark official duty
                    </SelectItem>
                  </SelectContent>
                </Select>
              </FormField>
              <FormField label="Explanation" error={errors.narrative}>
                <Textarea
                  value={value.narrative}
                  onChange={(event) =>
                    setValue((current) => ({
                      ...current,
                      narrative: event.target.value,
                    }))
                  }
                  placeholder="Explain what happened and the evidence available…"
                />
              </FormField>
              <div className="flex items-start gap-2">
                <Checkbox
                  id="declaration"
                  checked={value.declarationAccepted}
                  onCheckedChange={(checked) =>
                    setValue((current) => ({
                      ...current,
                      declarationAccepted: checked === true,
                    }))
                  }
                />
                <div>
                  <Label htmlFor="declaration">
                    I confirm this information is accurate.
                  </Label>
                  {errors.declarationAccepted && (
                    <p className="mt-1 text-xs text-destructive">
                      {errors.declarationAccepted}
                    </p>
                  )}
                </div>
              </div>
              <MutationError error={mutation.error} />
              <Button
                type="submit"
                disabled={mutation.isPending || candidateDays.length === 0}
              >
                {mutation.isPending ? "Submitting…" : "Submit regularization"}
              </Button>
              <p className="text-xs text-muted-foreground">
                Approval creates a correction record and recalculation. Raw
                source events are never edited by this form.
              </p>
            </form>
          </CardContent>
        </Card>
        <Card className="lg:col-span-3">
          <CardHeader>
            <CardTitle className="text-base">Request history</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            {(requestsQuery.data ?? []).length === 0 ? (
              <Empty />
            ) : (
              requestsQuery.data?.map((request) => (
                <div className="rounded-md border p-3" key={request.id}>
                  <div className="flex items-center justify-between gap-2">
                    <p className="font-medium">
                      {request.workDate} · {request.reasonCode}
                    </p>
                    <StatusBadge status={request.status} />
                  </div>
                  <p className="mt-1 text-sm text-muted-foreground">
                    {request.narrative}
                  </p>
                  <p className="mt-2 text-xs text-muted-foreground">
                    Requested: {request.requestedOutcome} · {request.createdAt}
                  </p>
                </div>
              ))
            )}
          </CardContent>
        </Card>
      </div>
    </WorkforceQueryBoundary>
  );
}

function FormField({
  label,
  error,
  children,
}: {
  label: string;
  error?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-1.5">
      <Label>{label}</Label>
      {children}
      {error && <p className="text-xs text-destructive">{error}</p>}
    </div>
  );
}

function Empty() {
  return (
    <p className="rounded-md border border-dashed p-8 text-center text-sm text-muted-foreground">
      No regularization requests found.
    </p>
  );
}
