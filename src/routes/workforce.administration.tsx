import { createFileRoute } from "@tanstack/react-router";
import {
  CalendarCog,
  Clock3,
  FileUp,
  IdCard,
  ShieldCheck,
} from "lucide-react";
import { useState } from "react";

import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
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
import type {
  DeliverableAllocation,
  EmployeeAlias,
  EngagementMonthOption,
  EngagementOption,
  ShiftAssignment,
  ShiftPolicy,
  WorkforceCsvImport,
} from "@/features/workforce/domain";
import {
  useAddDeliverableAllocation,
  useAddEmployeeAlias,
  useAllocations,
  useAssignShift,
  useCatalogEngagements,
  useDecideLeave,
  useDecideRegularization,
  useDeliverableAllocations,
  useEmployeeAliases,
  useEmployees,
  useEngagementMonths,
  useFinalizeRoster,
  useLeavePolicies,
  useLeaveRequestInbox,
  usePublishLeavePolicy,
  usePublishShiftPolicy,
  usePublishWorkforceCalendar,
  useRecordBalanceCommand,
  useRegularizationInbox,
  useRosterReadiness,
  useRosterSnapshots,
  useShiftAssignments,
  useShiftPolicies,
  useWorkforceCalendars,
  useWorkforceCsvImport,
} from "@/features/workforce/hooks";
import {
  MutationError,
  WorkforceQueryBoundary,
} from "@/features/workforce/query-boundary";
import { OrganizationScope } from "@/features/workforce/scope-selectors";
import { requireWorkforceRoute } from "@/lib/workforce-route";

export const Route = createFileRoute("/workforce/administration")({
  beforeLoad: requireWorkforceRoute,
  head: () => ({
    meta: [{ title: "Workforce administration — Cadence" }],
  }),
  component: WorkforceAdministrationPage,
});

function WorkforceAdministrationPage() {
  return (
    <div>
      <PageHeader
        title="Workforce administration"
        description="Versioned calendars and leave policies, effective employee aliases, reviewed corrections, and atomic CSV validation."
      />
      <div className="p-6">
        <OrganizationScope>
          {(organizationId) => (
            <AdministrationWorkspace organizationId={organizationId} />
          )}
        </OrganizationScope>
      </div>
    </div>
  );
}

function AdministrationWorkspace({
  organizationId,
}: {
  organizationId: string;
}) {
  const employees = useEmployees(organizationId);
  const calendars = useWorkforceCalendars(organizationId);
  const shiftPolicies = useShiftPolicies(organizationId);
  const engagements = useCatalogEngagements(organizationId);
  const policies = useLeavePolicies(organizationId);
  const leaveRequests = useLeaveRequestInbox(organizationId);
  const regularizations = useRegularizationInbox(organizationId);
  const [employeeId, setEmployeeId] = useState("");
  const [engagementId, setEngagementId] = useState("");
  const [engagementMonthId, setEngagementMonthId] = useState("");
  const engagementMonths = useEngagementMonths(engagementId);
  const aliases = useEmployeeAliases(employeeId);
  const allocations = useAllocations(employeeId);
  const deliverableAllocations = useDeliverableAllocations(employeeId);
  const shiftAssignments = useShiftAssignments(employeeId);

  return (
    <WorkforceQueryBoundary
      queries={[
        employees,
        calendars,
        shiftPolicies,
        engagements,
        policies,
        leaveRequests,
        regularizations,
      ]}
    >
      <div className="space-y-5">
        <div className="max-w-md">
          <Label htmlFor="admin-employee">Employee administration scope</Label>
          <Select value={employeeId} onValueChange={setEmployeeId}>
            <SelectTrigger id="admin-employee">
              <SelectValue placeholder="Select employee" />
            </SelectTrigger>
            <SelectContent>
              {employees.data?.map((employee) => (
                <SelectItem key={employee.id} value={employee.id}>
                  {employee.displayName} · {employee.employeeNumber}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="grid gap-5 xl:grid-cols-2">
          <AliasCard employeeId={employeeId} aliases={aliases.data ?? []} />
          <DeliverableAllocationCard
            employeeId={employeeId}
            projectAllocations={allocations.data ?? []}
            deliverableAllocations={deliverableAllocations.data ?? []}
          />
          <LeavePolicyCard
            organizationId={organizationId}
            policies={policies.data ?? []}
          />
          <CalendarCard
            organizationId={organizationId}
            calendars={calendars.data ?? []}
          />
          <ShiftPolicyCard
            organizationId={organizationId}
            employeeId={employeeId}
            policies={shiftPolicies.data ?? []}
            assignments={shiftAssignments.data ?? []}
          />
          <RosterCard
            engagements={engagements.data ?? []}
            engagementId={engagementId}
            onEngagementChange={(value) => {
              setEngagementId(value);
              setEngagementMonthId("");
            }}
            months={engagementMonths.data ?? []}
            engagementMonthId={engagementMonthId}
            onMonthChange={setEngagementMonthId}
          />
          <BalanceCommandCard
            employeeId={employeeId}
            policies={policies.data ?? []}
          />
          <LeaveReviewCard
            organizationId={organizationId}
            requests={leaveRequests.data ?? []}
          />
          <RegularizationCard
            organizationId={organizationId}
            requests={regularizations.data ?? []}
          />
        </div>
        <CsvImportCard organizationId={organizationId} />
      </div>
    </WorkforceQueryBoundary>
  );
}

function DeliverableAllocationCard({
  employeeId,
  projectAllocations,
  deliverableAllocations,
}: {
  employeeId: string;
  projectAllocations: NonNullable<ReturnType<typeof useAllocations>["data"]>;
  deliverableAllocations: DeliverableAllocation[];
}) {
  const mutation = useAddDeliverableAllocation(employeeId);
  const [projectAllocationId, setProjectAllocationId] = useState("");
  const [deliverableId, setDeliverableId] = useState("");
  const [allocationPercent, setAllocationPercent] = useState("10");
  const [role, setRole] = useState("");

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Deliverable allocation</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {!employeeId ? (
          <Empty>Select an employee before allocating a deliverable.</Empty>
        ) : (
          <>
            <div className="grid gap-3 sm:grid-cols-2">
              <div>
                <Label htmlFor="project-allocation">Project allocation</Label>
                <Select
                  value={projectAllocationId}
                  onValueChange={setProjectAllocationId}
                >
                  <SelectTrigger id="project-allocation">
                    <SelectValue placeholder="Select allocation" />
                  </SelectTrigger>
                  <SelectContent>
                    {projectAllocations.map((allocation) => (
                      <SelectItem key={allocation.id} value={allocation.id}>
                        {allocation.projectId} · {allocation.allocationPercent}%
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div>
                <Label htmlFor="deliverable-id">Deliverable UUID</Label>
                <Input
                  id="deliverable-id"
                  value={deliverableId}
                  onChange={(event) => setDeliverableId(event.target.value)}
                />
              </div>
              <div>
                <Label htmlFor="deliverable-percent">Allocation percent</Label>
                <Input
                  id="deliverable-percent"
                  type="number"
                  min="0.01"
                  max="100"
                  step="0.01"
                  value={allocationPercent}
                  onChange={(event) =>
                    setAllocationPercent(event.target.value)
                  }
                />
              </div>
              <div>
                <Label htmlFor="deliverable-role">Role</Label>
                <Input
                  id="deliverable-role"
                  value={role}
                  onChange={(event) => setRole(event.target.value)}
                />
              </div>
            </div>
            <MutationError error={mutation.error} />
            <Button
              disabled={
                mutation.isPending ||
                !projectAllocationId ||
                !deliverableId.trim() ||
                Number(allocationPercent) <= 0
              }
              onClick={() =>
                mutation.mutate({
                  projectAllocationId,
                  deliverableId: deliverableId.trim(),
                  validFrom: today(),
                  allocationPercent: Number(allocationPercent),
                  roleOnDeliverable: role.trim() || null,
                })
              }
            >
              Add bounded allocation
            </Button>
            {deliverableAllocations.map((allocation) => (
              <div key={allocation.id} className="rounded-md border p-3 text-sm">
                <strong>{allocation.deliverableCode}</strong> ·{" "}
                {allocation.allocationPercent}%
                <span className="block text-xs text-muted-foreground">
                  {allocation.roleOnDeliverable || "No role recorded"} · from{" "}
                  {allocation.validFrom}
                </span>
              </div>
            ))}
            {!deliverableAllocations.length && (
              <Empty>No deliverable allocations recorded.</Empty>
            )}
          </>
        )}
      </CardContent>
    </Card>
  );
}

function BalanceCommandCard({
  employeeId,
  policies,
}: {
  employeeId: string;
  policies: NonNullable<ReturnType<typeof useLeavePolicies>["data"]>;
}) {
  const mutation = useRecordBalanceCommand(employeeId);
  const [leaveTypeId, setLeaveTypeId] = useState("");
  const [commandType, setCommandType] =
    useState<"ACCRUAL" | "GRANT" | "ADJUSTMENT">("GRANT");
  const [quantity, setQuantity] = useState("1");
  const [reason, setReason] = useState("");
  const [commandKey, setCommandKey] = useState(() =>
    `leave-balance:${crypto.randomUUID()}`,
  );
  const currentPolicies = policies.filter(
    (policy, index, values) =>
      values.findIndex(
        (candidate) => candidate.leaveTypeId === policy.leaveTypeId,
      ) === index,
  );

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Leave balance command</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {!employeeId ? (
          <Empty>Select an employee before recording a balance command.</Empty>
        ) : (
          <>
            <div className="grid gap-3 sm:grid-cols-3">
              <div>
                <Label htmlFor="balance-type">Leave type</Label>
                <Select value={leaveTypeId} onValueChange={setLeaveTypeId}>
                  <SelectTrigger id="balance-type">
                    <SelectValue placeholder="Select policy" />
                  </SelectTrigger>
                  <SelectContent>
                    {currentPolicies.map((policy) => (
                      <SelectItem
                        key={policy.leaveTypeId}
                        value={policy.leaveTypeId}
                      >
                        {policy.leaveTypeCode}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div>
                <Label htmlFor="balance-command">Command</Label>
                <Select
                  value={commandType}
                  onValueChange={(value) =>
                    setCommandType(
                      value as "ACCRUAL" | "GRANT" | "ADJUSTMENT",
                    )
                  }
                >
                  <SelectTrigger id="balance-command">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="ACCRUAL">Accrual</SelectItem>
                    <SelectItem value="GRANT">Grant</SelectItem>
                    <SelectItem value="ADJUSTMENT">Adjustment</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div>
                <Label htmlFor="balance-quantity">Units</Label>
                <Input
                  id="balance-quantity"
                  type="number"
                  step="0.25"
                  value={quantity}
                  onChange={(event) => setQuantity(event.target.value)}
                />
              </div>
            </div>
            <Label htmlFor="balance-reason">Auditable reason</Label>
            <Textarea
              id="balance-reason"
              value={reason}
              onChange={(event) => setReason(event.target.value)}
            />
            <MutationError error={mutation.error} />
            <Button
              disabled={
                mutation.isPending ||
                !leaveTypeId ||
                !reason.trim() ||
                !Number(quantity)
              }
              onClick={() =>
                mutation.mutate(
                  {
                    leaveTypeId,
                    commandType,
                    quantity: Number(quantity),
                    effectiveDate: today(),
                    idempotencyKey: commandKey,
                    reason: reason.trim(),
                  },
                  {
                    onSuccess: () => {
                      setReason("");
                      setCommandKey(
                        `leave-balance:${crypto.randomUUID()}`,
                      );
                    },
                  },
                )
              }
            >
              Record immutable command
            </Button>
          </>
        )}
      </CardContent>
    </Card>
  );
}

function LeaveReviewCard({
  organizationId,
  requests,
}: {
  organizationId: string;
  requests: NonNullable<ReturnType<typeof useLeaveRequestInbox>["data"]>;
}) {
  const mutation = useDecideLeave(organizationId);
  const [reason, setReason] = useState("");
  const [keys, setKeys] = useState<Record<string, string>>({});
  const actionable = requests.filter((request) =>
    ["SUBMITTED", "APPROVED"].includes(request.status),
  );

  function decide(
    requestId: string,
    decision: "APPROVE" | "REJECT" | "CANCEL",
    expectedVersion: number,
  ) {
    const keyName = `${requestId}:${decision}`;
    const idempotencyKey =
      keys[keyName] ?? `leave-decision:${crypto.randomUUID()}`;
    if (!keys[keyName]) {
      setKeys((current) => ({ ...current, [keyName]: idempotencyKey }));
    }
    mutation.mutate(
      {
        requestId,
        input: {
          decision,
          expectedVersion,
          idempotencyKey,
          reason: reason.trim(),
        },
      },
      {
        onSuccess: () => {
          setReason("");
          setKeys((current) => {
            const next = { ...current };
            delete next[keyName];
            return next;
          });
        },
      },
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Leave approval inbox</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <Label htmlFor="leave-decision-reason">Decision reasoning</Label>
        <Textarea
          id="leave-decision-reason"
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          placeholder="Record the evidence and decision rationale."
        />
        <MutationError error={mutation.error} />
        {actionable.map((request) => (
          <div key={request.id} className="rounded-md border p-3 text-sm">
            <div className="flex items-start justify-between gap-3">
              <div>
                <strong>
                  {request.startDate} → {request.endDate}
                </strong>
                <p className="text-xs text-muted-foreground">
                  {request.units} unit(s) · paid {request.paidUnits} · LWP{" "}
                  {request.lwpUnits} · version {request.version}
                </p>
              </div>
              <StatusBadge status={request.status} />
            </div>
            <div className="mt-3 flex flex-wrap gap-2">
              {request.status === "SUBMITTED" ? (
                <>
                  <Button
                    size="sm"
                    disabled={!reason.trim() || mutation.isPending}
                    onClick={() =>
                      decide(request.id, "APPROVE", request.version)
                    }
                  >
                    Approve exact version
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={!reason.trim() || mutation.isPending}
                    onClick={() =>
                      decide(request.id, "REJECT", request.version)
                    }
                  >
                    Reject
                  </Button>
                </>
              ) : (
                <Button
                  size="sm"
                  variant="outline"
                  disabled={!reason.trim() || mutation.isPending}
                  onClick={() =>
                    decide(request.id, "CANCEL", request.version)
                  }
                >
                  Cancel and release balance
                </Button>
              )}
            </div>
          </div>
        ))}
        {!actionable.length && (
          <Empty>No submitted or approved leave requests require action.</Empty>
        )}
      </CardContent>
    </Card>
  );
}

function AliasCard({
  employeeId,
  aliases,
}: {
  employeeId: string;
  aliases: EmployeeAlias[];
}) {
  const mutation = useAddEmployeeAlias(employeeId);
  const [aliasType, setAliasType] =
    useState<EmployeeAlias["aliasType"]>("HRIS_ID");
  const [aliasValue, setAliasValue] = useState("");
  const [validFrom, setValidFrom] = useState(today());

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <IdCard className="h-4 w-4" aria-hidden="true" />
          Effective employee aliases
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {employeeId ? (
          <>
            <div className="grid gap-3 sm:grid-cols-3">
              <div>
                <Label htmlFor="alias-type">Alias type</Label>
                <Select
                  value={aliasType}
                  onValueChange={(value) =>
                    setAliasType(value as EmployeeAlias["aliasType"])
                  }
                >
                  <SelectTrigger id="alias-type">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {["HRIS_ID", "EMAIL", "BADGE", "LEGACY_ID", "OTHER"].map(
                      (value) => (
                        <SelectItem key={value} value={value}>
                          {value.replaceAll("_", " ")}
                        </SelectItem>
                      ),
                    )}
                  </SelectContent>
                </Select>
              </div>
              <div>
                <Label htmlFor="alias-value">Alias value</Label>
                <Input
                  id="alias-value"
                  value={aliasValue}
                  onChange={(event) => setAliasValue(event.target.value)}
                />
              </div>
              <div>
                <Label htmlFor="alias-from">Effective from</Label>
                <Input
                  id="alias-from"
                  type="date"
                  value={validFrom}
                  onChange={(event) => setValidFrom(event.target.value)}
                />
              </div>
            </div>
            <MutationError error={mutation.error} />
            <Button
              disabled={mutation.isPending || !aliasValue.trim()}
              onClick={() =>
                mutation.mutate(
                  { aliasType, aliasValue: aliasValue.trim(), validFrom },
                  { onSuccess: () => setAliasValue("") },
                )
              }
            >
              Add immutable alias
            </Button>
            <div className="space-y-2">
              {aliases.map((alias) => (
                <div
                  key={alias.id}
                  className="flex items-center justify-between rounded-md border p-3 text-sm"
                >
                  <span>
                    {alias.aliasType} · <strong>{alias.aliasValue}</strong>
                    <span className="block text-xs text-muted-foreground">
                      From {alias.validFrom}
                    </span>
                  </span>
                  <StatusBadge status={alias.status} />
                </div>
              ))}
              {!aliases.length && <Empty>No aliases recorded.</Empty>}
            </div>
          </>
        ) : (
          <Empty>Select an employee to administer aliases.</Empty>
        )}
      </CardContent>
    </Card>
  );
}

function LeavePolicyCard({
  organizationId,
  policies,
}: {
  organizationId: string;
  policies: ReturnType<typeof useLeavePolicies>["data"] extends
    | infer T
    | undefined
    ? NonNullable<T>
    : never;
}) {
  const mutation = usePublishLeavePolicy(organizationId);
  const [code, setCode] = useState("CL");
  const [name, setName] = useState("Casual Leave");
  const [validFrom, setValidFrom] = useState(today());
  const [approvalRequired, setApprovalRequired] = useState(true);
  const [excessToLwp, setExcessToLwp] = useState(true);

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <ShieldCheck className="h-4 w-4" aria-hidden="true" />
          Governed leave policy
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-3">
          <div>
            <Label htmlFor="policy-code">Leave code</Label>
            <Input
              id="policy-code"
              value={code}
              onChange={(event) => setCode(event.target.value.toUpperCase())}
            />
          </div>
          <div>
            <Label htmlFor="policy-name">Leave name</Label>
            <Input
              id="policy-name"
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
          </div>
          <div>
            <Label htmlFor="policy-from">Effective from</Label>
            <Input
              id="policy-from"
              type="date"
              value={validFrom}
              onChange={(event) => setValidFrom(event.target.value)}
            />
          </div>
        </div>
        <Check
          id="approval-required"
          checked={approvalRequired}
          onCheckedChange={setApprovalRequired}
          label="Require explicit approval before ledger consumption"
        />
        <Check
          id="excess-lwp"
          checked={excessToLwp}
          onCheckedChange={setExcessToLwp}
          label="Represent insufficient paid balance explicitly as LWP"
        />
        <MutationError error={mutation.error} />
        <Button
          disabled={mutation.isPending || !code || !name}
          onClick={() =>
            mutation.mutate({
              leaveTypeCode: code,
              leaveTypeName: name,
              paid: true,
              balanceTracked: true,
              minimumIncrement: 0.5,
              validFrom,
              approvalRequired,
              maximumUnitsPerRequest: 30,
              excessToLwp,
              cancellationAllowed: true,
              rules: { reviewerRequired: approvalRequired },
            })
          }
        >
          Publish policy version
        </Button>
        <div className="space-y-2">
          {policies.slice(0, 5).map((policy) => (
            <div key={policy.id} className="rounded-md border p-3 text-sm">
              <div className="flex justify-between">
                <strong>
                  {policy.leaveTypeCode} · v{policy.version}
                </strong>
                <StatusBadge status={policy.status} />
              </div>
              <p className="mt-1 text-xs text-muted-foreground">
                Effective {policy.validFrom} ·{" "}
                {policy.approvalRequired
                  ? "approval required"
                  : "automatic approval"}
              </p>
            </div>
          ))}
          {!policies.length && <Empty>No governed policies published.</Empty>}
        </div>
      </CardContent>
    </Card>
  );
}

function CalendarCard({
  organizationId,
  calendars,
}: {
  organizationId: string;
  calendars: ReturnType<typeof useWorkforceCalendars>["data"] extends
    | infer T
    | undefined
    ? NonNullable<T>
    : never;
}) {
  const mutation = usePublishWorkforceCalendar(organizationId);
  const [name, setName] = useState("Standard working week");
  const [timezone, setTimezone] = useState("Asia/Kolkata");
  const [validFrom, setValidFrom] = useState(today());
  const [holidayDate, setHolidayDate] = useState("");
  const [holidayName, setHolidayName] = useState("");
  const [holidayClassification, setHolidayClassification] =
    useState<"HOLIDAY" | "HALF_DAY_EXPECTED">("HOLIDAY");
  const [holidayMinutes, setHolidayMinutes] = useState("0");

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <CalendarCog className="h-4 w-4" aria-hidden="true" />
          Calendar versions
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-3">
          <div>
            <Label htmlFor="calendar-name">Calendar name</Label>
            <Input
              id="calendar-name"
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
          </div>
          <div>
            <Label htmlFor="calendar-zone">IANA timezone</Label>
            <Input
              id="calendar-zone"
              value={timezone}
              onChange={(event) => setTimezone(event.target.value)}
            />
          </div>
          <div>
            <Label htmlFor="calendar-from">Effective from</Label>
            <Input
              id="calendar-from"
              type="date"
              value={validFrom}
              onChange={(event) => setValidFrom(event.target.value)}
            />
          </div>
        </div>
        <div className="grid gap-3 sm:grid-cols-4">
          <div>
            <Label htmlFor="holiday-date">Holiday date</Label>
            <Input
              id="holiday-date"
              type="date"
              value={holidayDate}
              onChange={(event) => setHolidayDate(event.target.value)}
            />
          </div>
          <div>
            <Label htmlFor="holiday-name">Holiday name</Label>
            <Input
              id="holiday-name"
              value={holidayName}
              onChange={(event) => setHolidayName(event.target.value)}
            />
          </div>
          <div>
            <Label htmlFor="holiday-classification">Classification</Label>
            <Select
              value={holidayClassification}
              onValueChange={(value) =>
                setHolidayClassification(
                  value as "HOLIDAY" | "HALF_DAY_EXPECTED",
                )
              }
            >
              <SelectTrigger id="holiday-classification">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="HOLIDAY">Holiday</SelectItem>
                <SelectItem value="HALF_DAY_EXPECTED">Half day</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div>
            <Label htmlFor="holiday-minutes">Expected minutes</Label>
            <Input
              id="holiday-minutes"
              type="number"
              min="0"
              value={holidayMinutes}
              onChange={(event) => setHolidayMinutes(event.target.value)}
            />
          </div>
        </div>
        <MutationError error={mutation.error} />
        <Button
          disabled={mutation.isPending || !name || !timezone}
          onClick={() =>
            mutation.mutate({
              name,
              timezone,
              validFrom,
              validTo: null,
              expectedFullMinutes: 540,
              expectedHalfMinutes: 270,
              weekdays: Array.from({ length: 7 }, (_, index) => ({
                isoWeekday: index + 1,
                classification:
                  index < 5 ? ("WORKING" as const) : ("WEEKLY_OFF" as const),
                expectedMinutes: index < 5 ? 540 : 0,
              })),
              holidays:
                holidayDate && holidayName.trim()
                  ? [
                      {
                        holidayDate,
                        name: holidayName.trim(),
                        classification: holidayClassification,
                        expectedMinutes: Number(holidayMinutes),
                      },
                    ]
                  : [],
            })
          }
        >
          Publish standard calendar
        </Button>
        <div className="space-y-2">
          {calendars.slice(0, 5).map((calendar) => (
            <div key={calendar.id} className="rounded-md border p-3 text-sm">
              <strong>
                {calendar.name} · v{calendar.version}
              </strong>
              <p className="text-xs text-muted-foreground">
                {calendar.timezone} · effective {calendar.validFrom}
              </p>
            </div>
          ))}
          {!calendars.length && <Empty>No calendar versions published.</Empty>}
        </div>
      </CardContent>
    </Card>
  );
}

function ShiftPolicyCard({
  organizationId,
  employeeId,
  policies,
  assignments,
}: {
  organizationId: string;
  employeeId: string;
  policies: ShiftPolicy[];
  assignments: ShiftAssignment[];
}) {
  const publish = usePublishShiftPolicy(organizationId);
  const assign = useAssignShift(employeeId);
  const [code, setCode] = useState("STANDARD_DAY");
  const [name, setName] = useState("Standard day shift");
  const [timezone, setTimezone] = useState("Asia/Kolkata");
  const [start, setStart] = useState("09:00");
  const [end, setEnd] = useState("18:30");
  const [cutoff, setCutoff] = useState("06:00");
  const [expectedMinutes, setExpectedMinutes] = useState("540");
  const [maximumMinutes, setMaximumMinutes] = useState("960");
  const [allowSplit, setAllowSplit] = useState(true);
  const [selectedPolicy, setSelectedPolicy] = useState("");
  const [validFrom, setValidFrom] = useState(today());

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <Clock3 className="h-4 w-4" aria-hidden="true" />
          Shift and roster policy
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <p className="text-xs text-muted-foreground">
          A start later than end defines an overnight shift. Punches through
          the cutoff remain attributed to the prior roster work date.
        </p>
        <div className="grid gap-3 sm:grid-cols-3">
          <Field
            id="shift-code"
            label="Policy code"
            value={code}
            onChange={setCode}
          />
          <Field
            id="shift-name"
            label="Policy name"
            value={name}
            onChange={setName}
          />
          <Field
            id="shift-timezone"
            label="IANA timezone"
            value={timezone}
            onChange={setTimezone}
          />
          <TimeField
            id="shift-start"
            label="Scheduled start"
            value={start}
            onChange={setStart}
          />
          <TimeField
            id="shift-end"
            label="Scheduled end"
            value={end}
            onChange={setEnd}
          />
          <TimeField
            id="shift-cutoff"
            label="Overnight cutoff"
            value={cutoff}
            onChange={setCutoff}
          />
          <NumberField
            id="shift-expected"
            label="Expected net minutes"
            value={expectedMinutes}
            onChange={setExpectedMinutes}
          />
          <NumberField
            id="shift-maximum"
            label="Maximum session minutes"
            value={maximumMinutes}
            onChange={setMaximumMinutes}
          />
          <div className="pt-6">
            <Check
              id="shift-split"
              checked={allowSplit}
              onCheckedChange={setAllowSplit}
              label="Allow multiple non-overlapping sessions"
            />
          </div>
        </div>
        <MutationError error={publish.error} />
        <Button
          disabled={publish.isPending || !code.trim() || !name.trim()}
          onClick={() =>
            publish.mutate({
              code: code.trim(),
              name: name.trim(),
              timezone,
              validFrom,
              validTo: null,
              scheduledStartLocalTime: start,
              scheduledEndLocalTime: end,
              overnightCutoffLocalTime: cutoff,
              expectedNetMinutes: Number(expectedMinutes),
              maximumSessionMinutes: Number(maximumMinutes),
              allowSplitSessions: allowSplit,
              minimumBreakMinutes: 0,
            })
          }
        >
          Publish shift policy
        </Button>

        <div className="grid gap-3 sm:grid-cols-2">
          <div>
            <Label htmlFor="shift-assignment-policy">Published policy</Label>
            <Select value={selectedPolicy} onValueChange={setSelectedPolicy}>
              <SelectTrigger id="shift-assignment-policy">
                <SelectValue placeholder="Select policy version" />
              </SelectTrigger>
              <SelectContent>
                {policies.map((policy) => (
                  <SelectItem key={policy.id} value={policy.id}>
                    {policy.code} · v{policy.version} ·{" "}
                    {policy.scheduledStartLocalTime}–
                    {policy.scheduledEndLocalTime}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div>
            <Label htmlFor="shift-assignment-from">Assignment from</Label>
            <Input
              id="shift-assignment-from"
              type="date"
              value={validFrom}
              onChange={(event) => setValidFrom(event.target.value)}
            />
          </div>
        </div>
        <MutationError error={assign.error} />
        <Button
          variant="outline"
          disabled={
            assign.isPending || !employeeId || !selectedPolicy || !validFrom
          }
          onClick={() =>
            assign.mutate({
              shiftPolicyVersionId: selectedPolicy,
              validFrom,
              validTo: null,
            })
          }
        >
          Assign shift to selected employee
        </Button>
        <div className="space-y-2">
          {assignments.slice(0, 4).map((assignment) => (
            <div key={assignment.id} className="rounded-md border p-3 text-sm">
              <strong>
                {assignment.shiftPolicyCode} · v
                {assignment.shiftPolicyVersion}
              </strong>
              <p className="text-xs text-muted-foreground">
                {assignment.timezone} · from {assignment.validFrom}
              </p>
            </div>
          ))}
          {!assignments.length && (
            <Empty>No shift assigned to the selected employee.</Empty>
          )}
        </div>
      </CardContent>
    </Card>
  );
}

function RosterCard({
  engagements,
  engagementId,
  onEngagementChange,
  months,
  engagementMonthId,
  onMonthChange,
}: {
  engagements: EngagementOption[];
  engagementId: string;
  onEngagementChange: (value: string) => void;
  months: EngagementMonthOption[];
  engagementMonthId: string;
  onMonthChange: (value: string) => void;
}) {
  const readiness = useRosterReadiness(engagementMonthId);
  const snapshots = useRosterSnapshots(engagementMonthId);
  const finalize = useFinalizeRoster(engagementMonthId);
  const [reason, setReason] = useState(
    "Roster reviewed and finalized for attendance evidence.",
  );

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Roster completeness</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-2">
          <div>
            <Label htmlFor="roster-engagement">Engagement</Label>
            <Select value={engagementId} onValueChange={onEngagementChange}>
              <SelectTrigger id="roster-engagement">
                <SelectValue placeholder="Select engagement" />
              </SelectTrigger>
              <SelectContent>
                {engagements.map((engagement) => (
                  <SelectItem key={engagement.id} value={engagement.id}>
                    {engagement.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div>
            <Label htmlFor="roster-month">Engagement month</Label>
            <Select value={engagementMonthId} onValueChange={onMonthChange}>
              <SelectTrigger id="roster-month">
                <SelectValue placeholder="Select month" />
              </SelectTrigger>
              <SelectContent>
                {months.map((month) => (
                  <SelectItem key={month.id} value={month.id}>
                    {month.monthStartDate} · {month.state}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>
        {readiness.data && (
          <div className="rounded-md border p-3 text-sm">
            <div className="flex items-center justify-between">
              <strong>
                {readiness.data.allocatedEmployeeCount} employees ·{" "}
                {readiness.data.allocatedEmployeeDayCount} employee-days
              </strong>
              <StatusBadge
                status={readiness.data.ready ? "READY" : "BLOCKED"}
              />
            </div>
            <p className="mt-1 text-xs text-muted-foreground">
              Missing calendar {readiness.data.missingCalendarDayCount} · shift{" "}
              {readiness.data.missingShiftDayCount} · employee version{" "}
              {readiness.data.missingEmployeeVersionDayCount} · source mode{" "}
              {readiness.data.missingSourceModeDayCount}
            </p>
            {readiness.data.issues.slice(0, 5).map((issue, index) => (
              <p
                key={`${issue.code}-${issue.employeeId}-${issue.workDate}-${index}`}
                className="mt-1 text-xs text-destructive"
              >
                {issue.code} · {issue.workDate ?? "month"} · {issue.message}
              </p>
            ))}
          </div>
        )}
        <div>
          <Label htmlFor="roster-reason">Finalization reason</Label>
          <Textarea
            id="roster-reason"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
        </div>
        <MutationError error={finalize.error} />
        <Button
          disabled={
            finalize.isPending ||
            !engagementMonthId ||
            !reason.trim() ||
            !readiness.data?.ready
          }
          onClick={() => finalize.mutate(reason.trim())}
        >
          Finalize immutable roster
        </Button>
        {(snapshots.data ?? []).slice(-3).map((snapshot) => (
          <div key={snapshot.id} className="rounded-md border p-3 text-sm">
            <div className="flex justify-between">
              <strong>Roster v{snapshot.version}</strong>
              <StatusBadge status={snapshot.status} />
            </div>
            <p className="text-xs text-muted-foreground">
              {snapshot.employeeDayCount} employee-days · checksum{" "}
              {snapshot.checksum.slice(0, 12)}…
            </p>
          </div>
        ))}
      </CardContent>
    </Card>
  );
}

function RegularizationCard({
  organizationId,
  requests,
}: {
  organizationId: string;
  requests: ReturnType<typeof useRegularizationInbox>["data"] extends
    | infer T
    | undefined
    ? NonNullable<T>
    : never;
}) {
  const mutation = useDecideRegularization(organizationId);
  const [reason, setReason] = useState("");

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Regularization review</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <Label htmlFor="regularization-reason">Review reasoning</Label>
        <Textarea
          id="regularization-reason"
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          placeholder="Record independent review evidence."
        />
        <MutationError error={mutation.error} />
        {requests.filter((request) => request.status === "SUBMITTED").map(
          (request) => (
            <div key={request.id} className="rounded-md border p-3 text-sm">
              <div className="flex items-start justify-between gap-3">
                <span>
                  <strong>{request.workDate}</strong> · {request.reasonCode}
                  <span className="block text-xs text-muted-foreground">
                    {request.narrative}
                  </span>
                </span>
                <StatusBadge status={request.status} />
              </div>
              <div className="mt-3 flex gap-2">
                <Button
                  size="sm"
                  disabled={!reason.trim() || mutation.isPending}
                  onClick={() =>
                    mutation.mutate({
                      requestId: request.id,
                      input: {
                        decision: "APPROVE",
                        adjustedNetMinutes: 540,
                        reasoning: reason.trim(),
                      },
                    })
                  }
                >
                  Approve 540 minutes
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={!reason.trim() || mutation.isPending}
                  onClick={() =>
                    mutation.mutate({
                      requestId: request.id,
                      input: {
                        decision: "REJECT",
                        adjustedNetMinutes: null,
                        reasoning: reason.trim(),
                      },
                    })
                  }
                >
                  Reject
                </Button>
              </div>
            </div>
          ),
        )}
        {!requests.some((request) => request.status === "SUBMITTED") && (
          <Empty>No submitted regularizations require review.</Empty>
        )}
      </CardContent>
    </Card>
  );
}

function CsvImportCard({ organizationId }: { organizationId: string }) {
  const mutation = useWorkforceCsvImport(organizationId);
  const [importType, setImportType] =
    useState<WorkforceCsvImport["importType"]>("EMPLOYEE_ALIASES");
  const [fileName, setFileName] = useState("workforce.csv");
  const [csvContent, setCsvContent] = useState("");
  const [keys, setKeys] = useState(() => ({
    validate: `workforce-validate:${crypto.randomUUID()}`,
    apply: `workforce-apply:${crypto.randomUUID()}`,
  }));

  function submit(apply: boolean) {
    mutation.mutate(
      {
        importType,
        fileName,
        csvContent,
        idempotencyKey: apply ? keys.apply : keys.validate,
        apply,
      },
      {
        onSuccess: () =>
          setKeys({
            validate: `workforce-validate:${crypto.randomUUID()}`,
            apply: `workforce-apply:${crypto.randomUUID()}`,
          }),
      },
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <FileUp className="h-4 w-4" aria-hidden="true" />
          Bounded workforce CSV import
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid gap-3 md:grid-cols-2">
          <div>
            <Label htmlFor="import-type">Template</Label>
            <Select
              value={importType}
              onValueChange={(value) =>
                setImportType(value as WorkforceCsvImport["importType"])
              }
            >
              <SelectTrigger id="import-type">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="EMPLOYEE_ALIASES">
                  Employee aliases
                </SelectItem>
                <SelectItem value="DELIVERABLE_ALLOCATIONS">
                  Deliverable allocations
                </SelectItem>
                <SelectItem value="LEAVE_BALANCE_COMMANDS">
                  Leave balance commands
                </SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div>
            <Label htmlFor="csv-file">CSV source file</Label>
            <Input
              id="csv-file"
              type="file"
              accept=".csv,text/csv"
              onChange={(event) => {
                const file = event.target.files?.[0];
                if (!file) return;
                setFileName(file.name);
                void file.text().then(setCsvContent);
              }}
            />
          </div>
        </div>
        <Textarea
          aria-label="CSV preview"
          rows={6}
          value={csvContent}
          onChange={(event) => setCsvContent(event.target.value)}
          placeholder="Upload a CSV or paste its exact contents for validation."
        />
        <MutationError error={mutation.error} />
        <div className="flex gap-2">
          <Button
            variant="outline"
            disabled={!csvContent.trim() || mutation.isPending}
            onClick={() => submit(false)}
          >
            Validate only
          </Button>
          <Button
            disabled={!csvContent.trim() || mutation.isPending}
            onClick={() => submit(true)}
          >
            Apply atomically
          </Button>
        </div>
        {mutation.data && (
          <div className="rounded-md border p-4 text-sm" role="status">
            <p className="font-medium">
              {mutation.data.status} · {mutation.data.rowCount} row(s),{" "}
              {mutation.data.importedCount} imported
            </p>
            {mutation.data.errors.map((error) => (
              <p
                key={`${error.rowNumber}:${error.fieldName}`}
                className="mt-1 text-destructive"
              >
                Row {error.rowNumber}, {error.fieldName}: {error.errorCode} —{" "}
                {error.message}
              </p>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function Check({
  id,
  checked,
  onCheckedChange,
  label,
}: {
  id: string;
  checked: boolean;
  onCheckedChange: (checked: boolean) => void;
  label: string;
}) {
  return (
    <div className="flex items-center gap-2">
      <Checkbox
        id={id}
        checked={checked}
        onCheckedChange={(value) => onCheckedChange(value === true)}
      />
      <Label htmlFor={id}>{label}</Label>
    </div>
  );
}

function Field({
  id,
  label,
  value,
  onChange,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <div>
      <Label htmlFor={id}>{label}</Label>
      <Input
        id={id}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </div>
  );
}

function TimeField(props: Parameters<typeof Field>[0]) {
  return (
    <div>
      <Label htmlFor={props.id}>{props.label}</Label>
      <Input
        id={props.id}
        type="time"
        value={props.value}
        onChange={(event) => props.onChange(event.target.value)}
      />
    </div>
  );
}

function NumberField(props: Parameters<typeof Field>[0]) {
  return (
    <div>
      <Label htmlFor={props.id}>{props.label}</Label>
      <Input
        id={props.id}
        type="number"
        min="1"
        value={props.value}
        onChange={(event) => props.onChange(event.target.value)}
      />
    </div>
  );
}

function Empty({ children }: { children: React.ReactNode }) {
  return (
    <p className="rounded-md border border-dashed p-4 text-sm text-muted-foreground">
      {children}
    </p>
  );
}

function today() {
  return new Date().toISOString().slice(0, 10);
}
