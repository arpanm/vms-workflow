import { createFileRoute } from "@tanstack/react-router";
import { CalendarDays, Layers3, UserRound } from "lucide-react";

import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
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
  useEmployee,
  useLeaveBalances,
  useLeaveRequests,
} from "@/features/workforce/hooks";
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
                  <Field label="Exit date" value={employee.exitDate ?? "—"} />
                  <div className="flex flex-wrap gap-2">
                    <StatusBadge status={employee.employmentStatus} />
                    <StatusBadge status={employee.activationStatus} />
                  </div>
                </CardContent>
              </Card>
              <Card className="lg:col-span-2">
                <CardHeader>
                  <CardTitle className="flex items-center gap-2 text-base">
                    <Layers3 className="h-4 w-4" /> Allocations
                  </CardTitle>
                </CardHeader>
                <CardContent>
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
