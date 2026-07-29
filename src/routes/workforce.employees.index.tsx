import { Link, createFileRoute } from "@tanstack/react-router";
import { Search, Users } from "lucide-react";
import { useMemo, useState } from "react";

import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { useEmployees } from "@/features/workforce/hooks";
import type { EmployeeSummary } from "@/features/workforce/domain";
import { WorkforceQueryBoundary } from "@/features/workforce/query-boundary";
import { OrganizationScope } from "@/features/workforce/scope-selectors";
import { requireWorkforceRoute } from "@/lib/workforce-route";

export const Route = createFileRoute("/workforce/employees/")({
  beforeLoad: requireWorkforceRoute,
  head: () => ({
    meta: [
      { title: "Employees — Cadence" },
      {
        name: "description",
        content:
          "Authorized employee roster, employment status and attendance source.",
      },
    ],
  }),
  component: EmployeeDirectory,
});

const EMPTY_EMPLOYEES: EmployeeSummary[] = [];

function EmployeeDirectory() {
  return (
    <div>
      <PageHeader
        title="Employee directory"
        description="Effective-dated workforce roster. Payroll, salary and rate data are not collected or displayed."
      />
      <div className="p-6">
        <div className="mb-4 flex justify-end">
          <Button asChild variant="outline">
            <Link to="/workforce/administration">
              Workforce administration
            </Link>
          </Button>
        </div>
        <OrganizationScope>
          {(organizationId) => (
            <EmployeeTable organizationId={organizationId} />
          )}
        </OrganizationScope>
      </div>
    </div>
  );
}

function EmployeeTable({ organizationId }: { organizationId: string }) {
  const query = useEmployees(organizationId);
  const [search, setSearch] = useState("");
  const employees = query.data ?? EMPTY_EMPLOYEES;
  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase();
    if (!term) return employees;
    return employees.filter((employee) =>
      [
        employee.displayName,
        employee.employeeNumber,
        employee.workEmail,
      ].some((value) => value.toLowerCase().includes(term)),
    );
  }, [employees, search]);

  return (
    <WorkforceQueryBoundary queries={[query]}>
      <div className="space-y-4">
        <div className="relative max-w-md">
          <Search className="absolute left-3 top-2.5 h-4 w-4 text-muted-foreground" />
          <Input
            aria-label="Search employees"
            className="pl-9"
            placeholder="Search name, number or work email…"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </div>
        <Card>
          <CardContent className="p-0">
            {employees.length === 0 ? (
              <div className="grid min-h-52 place-items-center p-8 text-center">
                <div>
                  <Users className="mx-auto h-8 w-8 text-muted-foreground" />
                  <p className="mt-3 font-medium">No employees found</p>
                  <p className="mt-1 text-sm text-muted-foreground">
                    The authorized organization has no employee records yet.
                  </p>
                </div>
              </div>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Employee</TableHead>
                    <TableHead>Employment</TableHead>
                    <TableHead>Access</TableHead>
                    <TableHead>Attendance source</TableHead>
                    <TableHead>Effective from</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {filtered.map((employee) => (
                    <TableRow key={employee.id}>
                      <TableCell>
                        <Link
                          to="/workforce/employees/$employeeId"
                          params={{ employeeId: employee.id }}
                          className="font-medium text-primary hover:underline"
                        >
                          {employee.displayName}
                        </Link>
                        <p className="text-xs text-muted-foreground">
                          {employee.employeeNumber} · {employee.workEmail}
                        </p>
                      </TableCell>
                      <TableCell>
                        <StatusBadge status={employee.employmentStatus} />
                      </TableCell>
                      <TableCell>
                        <StatusBadge status={employee.activationStatus} />
                      </TableCell>
                      <TableCell>
                        <StatusBadge
                          status={employee.attendanceSourceMode}
                        />
                      </TableCell>
                      <TableCell>{employee.validFrom}</TableCell>
                    </TableRow>
                  ))}
                  {filtered.length === 0 && (
                    <TableRow>
                      <TableCell
                        colSpan={5}
                        className="h-32 text-center text-muted-foreground"
                      >
                        No employees match the search.
                      </TableCell>
                    </TableRow>
                  )}
                </TableBody>
              </Table>
            )}
          </CardContent>
        </Card>
      </div>
    </WorkforceQueryBoundary>
  );
}
