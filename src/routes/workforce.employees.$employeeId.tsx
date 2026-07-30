import { createFileRoute } from "@tanstack/react-router";
import { CalendarDays, Layers3, UserRound } from "lucide-react";
import { useState } from "react";

import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  useAllocations,
  useArchiveEmployee,
  useCreateAllocation,
  useDisableEmployeeAccess,
  useEditAllocation,
  useEditEmployee,
  useEndAllocation,
  useEmployee,
  useLeaveBalances,
  useLeaveRequests,
  useSplitAllocation,
} from "@/features/workforce/hooks";
import type { Allocation, EmployeeDetail } from "@/features/workforce/domain";
import { WorkforceQueryBoundary } from "@/features/workforce/query-boundary";
import { requireWorkforceRoute } from "@/lib/workforce-route";

export const Route = createFileRoute("/workforce/employees/$employeeId")({
  beforeLoad: requireWorkforceRoute,
  head: () => ({
    meta: [{ title: "Employee profile — Cadence" }],
  }),
  component: EmployeeProfile,
});

function EmployeeProfile() {
  const { employeeId } = Route.useParams();
  const employeeQuery = useEmployee(employeeId);
  const allocationsQuery = useAllocations(employeeId);
  const balancesQuery = useLeaveBalances(employeeId);
  const requestsQuery = useLeaveRequests(employeeId);
  const employee = employeeQuery.data;

  return (
    <div>
      <PageHeader
        title={employee?.displayName ?? "Employee profile"}
        description="Employment, allocation, leave and attendance-source details in your authorized scope."
      />
      <WorkforceQueryBoundary
        queries={[
          employeeQuery,
          allocationsQuery,
          balancesQuery,
          requestsQuery,
        ]}
      >
        {employee && (
          <div className="space-y-6 p-6">
            <div className="grid gap-4 lg:grid-cols-3">
              <Card>
                <CardHeader>
                  <CardTitle className="flex items-center gap-2 text-base">
                    <UserRound className="h-4 w-4" /> Overview
                  </CardTitle>
                </CardHeader>
                <CardContent className="grid gap-3 text-sm">
                  <Field label="Employee number" value={employee.employeeNumber} />
                  <Field label="Work email" value={employee.workEmail} />
                  <Field label="Joined" value={employee.joinDate} />
                  <Field label="Designation" value={employee.designation ?? "—"} />
                  <Field label="Exit date" value={employee.exitDate ?? "—"} />
                  <div className="flex flex-wrap gap-2">
                    <StatusBadge status={employee.employmentStatus} />
                    <StatusBadge status={employee.activationStatus} />
                  </div>
                  <EmployeeActions employee={employee} />
                </CardContent>
              </Card>
              <Card className="lg:col-span-2">
                <CardHeader>
                  <CardTitle className="flex items-center gap-2 text-base">
                    <Layers3 className="h-4 w-4" /> Allocations
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <div className="mb-4"><CreateAllocation employeeId={employeeId} /></div>
                  {(allocationsQuery.data ?? []).length === 0 ? (
                    <Empty label="No effective-dated allocations found." />
                  ) : (
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>Engagement / project</TableHead>
                          <TableHead>Allocation</TableHead>
                          <TableHead>Effective dates</TableHead>
                          <TableHead>Status</TableHead>
                          <TableHead>Actions</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {allocationsQuery.data?.map((allocation) => (
                          <TableRow key={allocation.id}>
                            <TableCell>
                              <p className="font-medium">
                                {allocation.engagementId}
                              </p>
                              <p className="text-xs text-muted-foreground">
                                Project {allocation.projectId}
                                {allocation.roleOnProject
                                  ? ` · ${allocation.roleOnProject}`
                                  : ""}
                              </p>
                            </TableCell>
                            <TableCell>{allocation.allocationPercent}%</TableCell>
                            <TableCell>
                              {allocation.validFrom} →{" "}
                              {allocation.validTo ?? "ongoing"}
                            </TableCell>
                            <TableCell>
                              <StatusBadge status={allocation.status} />
                            </TableCell>
                            <TableCell><AllocationActions employeeId={employeeId} allocation={allocation} /></TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  )}
                </CardContent>
              </Card>
            </div>

            <div className="grid gap-4 lg:grid-cols-2">
              <Card>
                <CardHeader>
                  <CardTitle className="flex items-center gap-2 text-base">
                    <CalendarDays className="h-4 w-4" /> Leave balances
                  </CardTitle>
                </CardHeader>
                <CardContent className="space-y-3">
                  {(balancesQuery.data ?? []).length === 0 ? (
                    <Empty label="No leave balances found." />
                  ) : (
                    balancesQuery.data?.map((balance) => (
                      <div
                        className="flex items-center justify-between rounded-md border p-3"
                        key={balance.leaveTypeId}
                      >
                        <div>
                          <p className="font-medium">{balance.leaveTypeName}</p>
                          <p className="text-xs text-muted-foreground">
                            {balance.leaveTypeCode} ·{" "}
                            {balance.paid ? "Paid" : "Unpaid"}
                          </p>
                        </div>
                        <p className="text-lg font-semibold tabular-nums">
                          {balance.availableUnits}
                        </p>
                      </div>
                    ))
                  )}
                </CardContent>
              </Card>
              <Card>
                <CardHeader>
                  <CardTitle className="text-base">Recent leave requests</CardTitle>
                </CardHeader>
                <CardContent className="space-y-3">
                  {(requestsQuery.data ?? []).length === 0 ? (
                    <Empty label="No leave requests found." />
                  ) : (
                    requestsQuery.data?.slice(0, 6).map((request) => (
                      <div
                        className="flex items-center justify-between gap-3 rounded-md border p-3"
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
                            {request.startDate} → {request.endDate}
                          </p>
                        </div>
                        <StatusBadge status={request.status} />
                      </div>
                    ))
                  )}
                </CardContent>
              </Card>
            </div>

            <Card className="border-info/30 bg-info/5">
              <CardContent className="py-4 text-sm">
                Attendance source:{" "}
                <strong>{employee.attendanceSourceMode}</strong>. greytHR
                availability has not been certified; this screen does not call
                or imply access to greytHR.
              </CardContent>
            </Card>
          </div>
        )}
      </WorkforceQueryBoundary>
    </div>
  );
}

function EmployeeActions({ employee }: { employee: EmployeeDetail }) {
  const edit = useEditEmployee(employee.id);
  const archive = useArchiveEmployee(employee.id);
  const disableAccess = useDisableEmployeeAccess(employee.id);
  const [open, setOpen] = useState(false);
  return <div className="flex flex-wrap gap-2 pt-2">
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild><Button size="sm" variant="outline">Edit master fields</Button></DialogTrigger>
      <DialogContent><DialogHeader><DialogTitle>Edit employee</DialogTitle></DialogHeader>
        <form className="grid gap-3" onSubmit={(event) => {
          event.preventDefault(); const data = new FormData(event.currentTarget);
          void edit.mutateAsync({
            effectiveFrom: String(data.get("effectiveFrom")), firstName: String(data.get("firstName")),
            lastName: String(data.get("lastName")), displayName: String(data.get("displayName")),
            designation: String(data.get("designation") || ""), reason: String(data.get("reason")),
          }).then(() => setOpen(false));
        }}>
          <InputField name="effectiveFrom" label="Effective from" type="date" />
          <InputField name="firstName" label="First name" defaultValue={employee.firstName} />
          <InputField name="lastName" label="Last name" defaultValue={employee.lastName} />
          <InputField name="displayName" label="Display name" defaultValue={employee.displayName} />
          <InputField name="designation" label="Designation" defaultValue={employee.designation} required={false} />
          <InputField name="reason" label="Reason" />
          <Button disabled={edit.isPending}>Save effective version</Button>
          {edit.error && <p role="alert" className="text-sm text-destructive">{edit.error.message}</p>}
        </form>
      </DialogContent>
    </Dialog>
    <Button size="sm" variant="outline" disabled={disableAccess.isPending || employee.activationStatus === "DISABLED"}
      onClick={() => { const effectiveFrom = window.prompt("Access disable effective date (YYYY-MM-DD)"); if (effectiveFrom) disableAccess.mutate({ effectiveFrom, employmentStatus: employee.employmentStatus, exitDate: employee.exitDate, reason: "Access disabled from employee profile" }); }}>
      Disable access
    </Button>
    <Button size="sm" variant="destructive" disabled={
      archive.isPending ||
      employee.employmentStatus === "ARCHIVED" ||
      (employee.employmentStatus !== "EXITED" && employee.activationStatus !== "DISABLED")
    }
      onClick={() => { const effectiveFrom = window.prompt("Archive effective date (YYYY-MM-DD)"); if (effectiveFrom) archive.mutate({ effectiveFrom, reason: "Archived from employee profile" }); }}>
      Archive employee
    </Button>
    {employee.employmentStatus !== "EXITED" && employee.activationStatus !== "DISABLED" && (
      <p className="w-full text-xs text-muted-foreground">Exit or disable access in an earlier effective version before archiving.</p>
    )}
  </div>;
}

function CreateAllocation({ employeeId }: { employeeId: string }) {
  const mutation = useCreateAllocation(employeeId);
  return <Dialog><DialogTrigger asChild><Button size="sm">Add allocation</Button></DialogTrigger>
    <DialogContent><DialogHeader><DialogTitle>Add project allocation</DialogTitle></DialogHeader>
      <AllocationForm submitLabel="Add allocation" pending={mutation.isPending} onSubmit={(data) => mutation.mutate(data)} />
      {mutation.error && <p role="alert" className="text-sm text-destructive">{mutation.error.message}</p>}
    </DialogContent>
  </Dialog>;
}

function AllocationActions({ employeeId, allocation }: { employeeId: string; allocation: Allocation }) {
  const edit = useEditAllocation(employeeId);
  const end = useEndAllocation(employeeId);
  const split = useSplitAllocation(employeeId);
  return <div className="flex flex-wrap gap-1">
    <Dialog><DialogTrigger asChild><Button size="sm" variant="outline" disabled={allocation.status !== "PLANNED"}>Edit</Button></DialogTrigger>
      <DialogContent><DialogHeader><DialogTitle>Edit allocation</DialogTitle></DialogHeader>
        <AllocationForm submitLabel="Save allocation" pending={edit.isPending} initial={allocation}
          onSubmit={(input) => edit.mutate({ allocationId: allocation.id, input })} />
      </DialogContent>
    </Dialog>
    <Button size="sm" variant="outline" disabled={end.isPending || allocation.status === "ENDED"} onClick={() => {
      const effectiveTo = window.prompt("End date (YYYY-MM-DD)", allocation.validTo);
      if (effectiveTo) end.mutate({ allocationId: allocation.id, input: { effectiveTo, reason: "Ended from employee profile" } });
    }}>End</Button>
    <Dialog><DialogTrigger asChild><Button size="sm" variant="outline" disabled={allocation.status === "ENDED"}>Split</Button></DialogTrigger>
      <DialogContent><DialogHeader><DialogTitle>Split allocation</DialogTitle></DialogHeader>
        <AllocationForm submitLabel="Split allocation" pending={split.isPending} initial={allocation} includeSplitDate
          onSubmit={(input, splitFrom) => split.mutate({ allocationId: allocation.id, input: { splitFrom: splitFrom!, engagementId: input.engagementId, projectId: input.projectId, allocationPercent: input.allocationPercent, roleOnProject: input.roleOnProject, reason: "Split from employee profile" } })} />
      </DialogContent>
    </Dialog>
    {allocation.status === "ACTIVE" && <span className="sr-only">Use split to change an effective allocation without rewriting prior days.</span>}
  </div>;
}

type AllocationInput = Parameters<ReturnType<typeof useCreateAllocation>["mutate"]>[0];
function AllocationForm({ submitLabel, pending, initial, includeSplitDate = false, onSubmit }: {
  submitLabel: string; pending: boolean; initial?: Allocation; includeSplitDate?: boolean;
  onSubmit: (input: AllocationInput, splitFrom?: string) => void;
}) {
  return <form className="grid gap-3" onSubmit={(event) => {
    event.preventDefault(); const data = new FormData(event.currentTarget);
    onSubmit({ engagementId: String(data.get("engagementId")), projectId: String(data.get("projectId")),
      validFrom: String(data.get("validFrom")), validTo: String(data.get("validTo") || "") || null,
      allocationPercent: Number(data.get("allocationPercent")), roleOnProject: String(data.get("roleOnProject") || "") || null },
      includeSplitDate ? String(data.get("splitFrom")) : undefined);
  }}>
    {includeSplitDate && <InputField name="splitFrom" label="Split from" type="date" />}
    <InputField name="engagementId" label="Engagement ID" defaultValue={initial?.engagementId} />
    <InputField name="projectId" label="Project ID" defaultValue={initial?.projectId} />
    {!includeSplitDate && <><InputField name="validFrom" label="Valid from" type="date" defaultValue={initial?.validFrom} /><InputField name="validTo" label="Valid to" type="date" defaultValue={initial?.validTo} required={false} /></>}
    <InputField name="allocationPercent" label="Allocation percent" type="number" defaultValue={String(initial?.allocationPercent ?? "")} />
    <InputField name="roleOnProject" label="Project role" defaultValue={initial?.roleOnProject} required={false} />
    <Button disabled={pending}>{submitLabel}</Button>
  </form>;
}

function InputField({ name, label, type = "text", defaultValue, required = true }: { name: string; label: string; type?: string; defaultValue?: string; required?: boolean }) {
  return <div className="grid gap-2"><Label htmlFor={`${name}-field`}>{label}</Label><Input id={`${name}-field`} name={name} type={type} defaultValue={defaultValue} required={required} /></div>;
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-xs uppercase tracking-wide text-muted-foreground">
        {label}
      </p>
      <p className="mt-0.5">{value}</p>
    </div>
  );
}

function Empty({ label }: { label: string }) {
  return (
    <p className="rounded-md border border-dashed p-6 text-center text-sm text-muted-foreground">
      {label}
    </p>
  );
}
