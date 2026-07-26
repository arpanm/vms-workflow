import type { ReactNode } from "react";

import { StatusBadge } from "@/components/status-badge";

import { useMyEmployee } from "./hooks";
import { WorkforceQueryBoundary } from "./query-boundary";

/**
 * Resolves the authenticated identity through the server-side user-to-employee
 * binding. It deliberately never falls back to an organization roster because
 * self-service users do not need workforce.read and must not discover peers.
 */
export function AttendanceEmployeeScope({
  children,
}: {
  children: (employeeId: string) => ReactNode;
}) {
  const employeeQuery = useMyEmployee();
  const employee = employeeQuery.data;

  return (
    <WorkforceQueryBoundary queries={[employeeQuery]}>
      {employee && (
        <div className="space-y-4">
          <div className="flex flex-wrap items-center gap-2 rounded-md border bg-muted/30 px-3 py-2 text-sm">
            <span className="font-medium">{employee.displayName}</span>
            <span className="text-muted-foreground">
              {employee.employeeNumber}
            </span>
            <StatusBadge status={employee.employmentStatus} />
          </div>
          {children(employee.id)}
        </div>
      )}
    </WorkforceQueryBoundary>
  );
}
