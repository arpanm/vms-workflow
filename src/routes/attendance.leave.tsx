import { createFileRoute } from "@tanstack/react-router";
import { useState } from "react";

import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
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
import type { CreateLeaveRequestInput } from "@/features/workforce/domain";
import {
  useCreateLeaveRequest,
  useLeaveBalances,
  useLeaveRequests,
} from "@/features/workforce/hooks";
import {
  MutationError,
  WorkforceQueryBoundary,
} from "@/features/workforce/query-boundary";
import {
  type ValidationErrors,
  validateLeaveRequest,
} from "@/features/workforce/presentation";
import { requireWorkforceRoute } from "@/lib/workforce-route";

export const Route = createFileRoute("/attendance/leave")({
  beforeLoad: requireWorkforceRoute,
  head: () => ({ meta: [{ title: "Leave — Cadence" }] }),
  component: LeavePage,
});

const initialValue: CreateLeaveRequestInput = {
  leaveTypeId: "",
  startDate: "",
  endDate: "",
  units: 1,
  reason: "",
};

function LeavePage() {
  return (
    <div>
      <PageHeader
        title="Leave"
        description="View ledger-derived balances and submit a request. Paid/LWP allocation is calculated by the server policy."
      />
      <div className="p-6">
        <AttendanceEmployeeScope>
          {(employeeId) => <LeaveWorkspace employeeId={employeeId} />}
        </AttendanceEmployeeScope>
      </div>
    </div>
  );
}

function LeaveWorkspace({ employeeId }: { employeeId: string }) {
  const balancesQuery = useLeaveBalances(employeeId);
  const requestsQuery = useLeaveRequests(employeeId);
  const mutation = useCreateLeaveRequest(employeeId);
  const [value, setValue] = useState(initialValue);
  const [errors, setErrors] =
    useState<ValidationErrors<CreateLeaveRequestInput>>({});

  function submit(event: React.FormEvent) {
    event.preventDefault();
    const nextErrors = validateLeaveRequest(value);
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length) return;
    mutation.mutate(value, {
      onSuccess: () => {
        setValue(initialValue);
        setErrors({});
      },
    });
  }

  return (
    <WorkforceQueryBoundary queries={[balancesQuery, requestsQuery]}>
      <div className="grid gap-4 lg:grid-cols-5">
        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle className="text-base">New leave request</CardTitle>
          </CardHeader>
          <CardContent>
            <form className="space-y-4" onSubmit={submit}>
              <FormField label="Leave type" error={errors.leaveTypeId}>
                <Select
                  value={value.leaveTypeId}
                  onValueChange={(leaveTypeId) =>
                    setValue((current) => ({ ...current, leaveTypeId }))
                  }
                >
                  <SelectTrigger aria-label="Leave type">
                    <SelectValue placeholder="Select leave type" />
                  </SelectTrigger>
                  <SelectContent>
                    {balancesQuery.data?.map((balance) => (
                      <SelectItem
                        key={balance.leaveTypeId}
                        value={balance.leaveTypeId}
                      >
                        {balance.leaveTypeName} · {balance.availableUnits}{" "}
                        available
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </FormField>
              <div className="grid gap-3 sm:grid-cols-2">
                <FormField label="Start date" error={errors.startDate}>
                  <Input
                    type="date"
                    value={value.startDate}
                    onChange={(event) =>
                      setValue((current) => ({
                        ...current,
                        startDate: event.target.value,
                      }))
                    }
                  />
                </FormField>
                <FormField label="End date" error={errors.endDate}>
                  <Input
                    type="date"
                    value={value.endDate}
                    onChange={(event) =>
                      setValue((current) => ({
                        ...current,
                        endDate: event.target.value,
                      }))
                    }
                  />
                </FormField>
              </div>
              <FormField
                label="Requested units"
                error={errors.units}
                errorId="leave-units-error"
              >
                <Input
                  type="number"
                  min="0.5"
                  step="0.5"
                  value={value.units}
                  aria-invalid={Boolean(errors.units)}
                  aria-describedby={
                    errors.units ? "leave-units-error" : undefined
                  }
                  onChange={(event) =>
                    setValue((current) => ({
                      ...current,
                      units: Number(event.target.value),
                    }))
                  }
                />
              </FormField>
              <FormField label="Reason" error={errors.reason}>
                <Textarea
                  value={value.reason}
                  onChange={(event) =>
                    setValue((current) => ({
                      ...current,
                      reason: event.target.value,
                    }))
                  }
                />
              </FormField>
              <MutationError error={mutation.error} />
              <Button disabled={mutation.isPending} type="submit">
                {mutation.isPending ? "Submitting…" : "Submit request"}
              </Button>
              <p className="text-xs text-muted-foreground">
                The displayed balance is not edited in the browser. The server
                returns the final paid and LWP split.
              </p>
            </form>
          </CardContent>
        </Card>
        <Card className="lg:col-span-3">
          <CardHeader>
            <CardTitle className="text-base">Requests</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            {(requestsQuery.data ?? []).length === 0 ? (
              <Empty label="No leave requests have been submitted." />
            ) : (
              requestsQuery.data?.map((request) => (
                <div
                  className="flex flex-col justify-between gap-2 rounded-md border p-3 sm:flex-row sm:items-center"
                  key={request.id}
                >
                  <div>
                    <p className="font-medium">
                      {balancesQuery.data?.find(
                        (balance) =>
                          balance.leaveTypeId === request.leaveTypeId,
                      )?.leaveTypeCode ?? "Leave"}{" "}
                      · {request.units} unit(s)
                    </p>
                    <p className="text-xs text-muted-foreground">
                      {request.startDate} → {request.endDate} · paid{" "}
                      {request.paidUnits}, LWP {request.lwpUnits}
                    </p>
                  </div>
                  <StatusBadge status={request.status} />
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
  errorId,
  children,
}: {
  label: string;
  error?: string;
  errorId?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-1.5">
      <Label>{label}</Label>
      {children}
      {error && (
        <p
          id={errorId}
          className="text-xs text-destructive"
          role="alert"
        >
          {error}
        </p>
      )}
    </div>
  );
}

function Empty({ label }: { label: string }) {
  return (
    <p className="rounded-md border border-dashed p-8 text-center text-sm text-muted-foreground">
      {label}
    </p>
  );
}
