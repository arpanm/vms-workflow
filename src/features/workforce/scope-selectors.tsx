import { useEffect, useState } from "react";

import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

import {
  useCatalogEngagements,
  useEmployees,
  useEngagementMonths,
  useOrganizations,
} from "./hooks";
import { WorkforceQueryBoundary } from "./query-boundary";

export function OrganizationScope({
  children,
}: {
  children: (organizationId: string) => React.ReactNode;
}) {
  const organizationsQuery = useOrganizations();
  const organizations = organizationsQuery.data ?? [];
  const [selection, setSelection] = useState("");
  const organizationId = selection || organizations[0]?.id || "";

  return (
    <WorkforceQueryBoundary queries={[organizationsQuery]}>
      <div className="space-y-4">
        {organizations.length === 0 ? (
          <EmptyScope label="No authorized organization memberships were found." />
        ) : (
          <>
            <Select value={organizationId} onValueChange={setSelection}>
              <SelectTrigger className="w-full sm:w-[320px]" aria-label="Organization">
                <SelectValue placeholder="Select organization" />
              </SelectTrigger>
              <SelectContent>
                {organizations.map((organization) => (
                  <SelectItem key={organization.id} value={organization.id}>
                    {organization.displayName} ({organization.code})
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {children(organizationId)}
          </>
        )}
      </div>
    </WorkforceQueryBoundary>
  );
}

export function EmployeeScope({
  organizationId,
  employeeId,
  onEmployeeChange,
  children,
}: {
  organizationId: string;
  employeeId: string;
  onEmployeeChange: (employeeId: string) => void;
  children: (employeeId: string) => React.ReactNode;
}) {
  const employeesQuery = useEmployees(organizationId);
  const employees = employeesQuery.data ?? [];
  const resolved =
    employees.some((employee) => employee.id === employeeId)
      ? employeeId
      : employees[0]?.id || "";

  useEffect(() => {
    if (resolved && employeeId !== resolved) onEmployeeChange(resolved);
  }, [employeeId, onEmployeeChange, resolved]);

  return (
    <WorkforceQueryBoundary queries={[employeesQuery]}>
      {employees.length === 0 ? (
        <EmptyScope label="No employees are available in this organization." />
      ) : (
        <div className="space-y-4">
          <Select value={resolved} onValueChange={onEmployeeChange}>
            <SelectTrigger className="w-full sm:w-[320px]" aria-label="Employee">
              <SelectValue placeholder="Select employee" />
            </SelectTrigger>
            <SelectContent>
              {employees.map((employee) => (
                <SelectItem key={employee.id} value={employee.id}>
                  {employee.displayName} · {employee.employeeNumber}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          {children(resolved)}
        </div>
      )}
    </WorkforceQueryBoundary>
  );
}

export function MonthScope({
  children,
}: {
  children: (engagementMonthId: string) => React.ReactNode;
}) {
  const organizationsQuery = useOrganizations();
  const organizations = organizationsQuery.data ?? [];
  const [organizationId, setOrganizationId] = useState("");
  const resolvedOrganization = organizationId || organizations[0]?.id || "";
  const engagementsQuery = useCatalogEngagements(resolvedOrganization);
  const engagements = engagementsQuery.data ?? [];
  const [engagementId, setEngagementId] = useState("");
  const resolvedEngagement = engagementId || engagements[0]?.id || "";
  const monthsQuery = useEngagementMonths(resolvedEngagement);
  const months = monthsQuery.data ?? [];
  const [monthId, setMonthId] = useState("");
  const resolvedMonth = monthId || months[0]?.id || "";

  return (
    <WorkforceQueryBoundary
      queries={[organizationsQuery, engagementsQuery, monthsQuery]}
    >
      {!resolvedOrganization || !resolvedEngagement || !resolvedMonth ? (
        <EmptyScope label="No authorized engagement month is available." />
      ) : (
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-3">
            <Select
              value={resolvedOrganization}
              onValueChange={(value) => {
                setOrganizationId(value);
                setEngagementId("");
                setMonthId("");
              }}
            >
              <SelectTrigger aria-label="Organization">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {organizations.map((item) => (
                  <SelectItem value={item.id} key={item.id}>
                    {item.displayName}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Select
              value={resolvedEngagement}
              onValueChange={(value) => {
                setEngagementId(value);
                setMonthId("");
              }}
            >
              <SelectTrigger aria-label="Engagement">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {engagements.map((item) => (
                  <SelectItem value={item.id} key={item.id}>
                    {item.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Select value={resolvedMonth} onValueChange={setMonthId}>
              <SelectTrigger aria-label="Engagement month">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {months.map((item) => (
                  <SelectItem value={item.id} key={item.id}>
                    {item.monthStartDate} · {item.state}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          {children(resolvedMonth)}
        </div>
      )}
    </WorkforceQueryBoundary>
  );
}

function EmptyScope({ label }: { label: string }) {
  return (
    <div className="rounded-lg border border-dashed p-10 text-center text-sm text-muted-foreground">
      {label}
    </div>
  );
}
