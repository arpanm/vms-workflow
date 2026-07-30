import { apiClient } from "@/lib/api-client";

import type {
  Allocation,
  CalendarVersion,
  DeliverableAllocation,
  AttendanceDay,
  CreateLeaveRequestInput,
  CreateRegularizationInput,
  CreateEmployeeInput,
  EmployeeDetail,
  EmployeeSummary,
  EmployeeAlias,
  EngagementMonthOption,
  EngagementOption,
  LeaveBalance,
  LeaveDecision,
  LeavePolicy,
  LeaveRequest,
  MonthlyAttendanceSnapshot,
  OrganizationOption,
  Punch,
  RegularizationRequest,
  RosterReadiness,
  RosterSnapshot,
  ShiftAssignment,
  ShiftPolicy,
  WorkforceCsvImport,
} from "./domain";

const workforcePath = "/workforce";
const attendancePath = "/attendance";

function encoded(value: string) {
  return encodeURIComponent(value);
}

export const workforceApi = {
  organizations: () => apiClient.get<OrganizationOption[]>("/organizations"),

  engagements: (organizationId: string) =>
    apiClient.get<EngagementOption[]>(
      `/engagements?organizationId=${encoded(organizationId)}`,
    ),

  engagementMonths: (engagementId: string) =>
    apiClient.get<EngagementMonthOption[]>(
      `/engagement-months?engagementId=${encoded(engagementId)}`,
    ),

  employees: (organizationId: string) =>
    apiClient.get<EmployeeSummary[]>(
      `${workforcePath}/employees?organizationId=${encoded(organizationId)}`,
    ),

  myEmployee: () =>
    apiClient.get<EmployeeDetail>(`${workforcePath}/employees/me`),

  employee: (employeeId: string) =>
    apiClient.get<EmployeeDetail>(
      `${workforcePath}/employees/${encoded(employeeId)}`,
    ),

  createEmployee: (input: CreateEmployeeInput) =>
    apiClient.post<EmployeeDetail>(`${workforcePath}/employees`, input),

  editEmployee: (
    employeeId: string,
    input: {
      effectiveFrom: string;
      firstName: string;
      lastName: string;
      displayName: string;
      designation?: string | null;
      reason: string;
    },
  ) =>
    apiClient.patch<EmployeeDetail>(
      `${workforcePath}/employees/${encoded(employeeId)}`,
      input,
    ),

  archiveEmployee: (
    employeeId: string,
    input: { effectiveFrom: string; reason: string },
  ) =>
    apiClient.patch<EmployeeDetail>(
      `${workforcePath}/employees/${encoded(employeeId)}/lifecycle`,
      {
        ...input,
        employmentStatus: "ARCHIVED",
        activationStatus: "DISABLED",
      },
    ),

  disableEmployeeAccess: (
    employeeId: string,
    input: {
      effectiveFrom: string;
      employmentStatus: EmployeeDetail["employmentStatus"];
      exitDate?: string | null;
      reason: string;
    },
  ) =>
    apiClient.patch<EmployeeDetail>(
      `${workforcePath}/employees/${encoded(employeeId)}/lifecycle`,
      { ...input, activationStatus: "DISABLED" },
    ),

  allocations: (employeeId: string) =>
    apiClient.get<Allocation[]>(
      `${workforcePath}/employees/${encoded(employeeId)}/allocations`,
    ),

  createAllocation: (
    employeeId: string,
    input: {
      engagementId: string;
      projectId: string;
      validFrom: string;
      validTo?: string | null;
      allocationPercent: number;
      roleOnProject?: string | null;
    },
  ) =>
    apiClient.post<Allocation>(
      `${workforcePath}/employees/${encoded(employeeId)}/allocations`,
      input,
    ),

  editAllocation: (
    employeeId: string,
    allocationId: string,
    input: {
      validFrom: string;
      validTo?: string | null;
      allocationPercent: number;
      roleOnProject?: string | null;
    },
  ) =>
    apiClient.patch<Allocation>(
      `${workforcePath}/employees/${encoded(employeeId)}/allocations/${encoded(allocationId)}`,
      input,
    ),

  endAllocation: (
    employeeId: string,
    allocationId: string,
    input: { effectiveTo: string; reason: string },
  ) =>
    apiClient.post<Allocation>(
      `${workforcePath}/employees/${encoded(employeeId)}/allocations/${encoded(allocationId)}/end`,
      input,
    ),

  splitAllocation: (
    employeeId: string,
    allocationId: string,
    input: {
      splitFrom: string;
      engagementId: string;
      projectId: string;
      allocationPercent: number;
      roleOnProject?: string | null;
      reason: string;
    },
  ) =>
    apiClient.post<Allocation>(
      `${workforcePath}/employees/${encoded(employeeId)}/allocations/${encoded(allocationId)}/split`,
      input,
    ),

  aliases: (employeeId: string) =>
    apiClient.get<EmployeeAlias[]>(
      `${workforcePath}/employees/${encoded(employeeId)}/aliases`,
    ),

  addAlias: (
    employeeId: string,
    input: {
      aliasType: EmployeeAlias["aliasType"];
      aliasValue: string;
      validFrom: string;
      validTo?: string | null;
    },
  ) =>
    apiClient.post<EmployeeAlias>(
      `${workforcePath}/employees/${encoded(employeeId)}/aliases`,
      input,
    ),

  deliverableAllocations: (employeeId: string) =>
    apiClient.get<DeliverableAllocation[]>(
      `${workforcePath}/employees/${encoded(employeeId)}/deliverable-allocations`,
    ),

  addDeliverableAllocation: (
    employeeId: string,
    input: {
      projectAllocationId: string;
      deliverableId: string;
      validFrom: string;
      validTo?: string | null;
      allocationPercent: number;
      roleOnDeliverable?: string | null;
    },
  ) =>
    apiClient.post<DeliverableAllocation>(
      `${workforcePath}/employees/${encoded(employeeId)}/deliverable-allocations`,
      input,
    ),

  calendars: (organizationId: string) =>
    apiClient.get<CalendarVersion[]>(
      `${workforcePath}/organizations/${encoded(organizationId)}/calendars`,
    ),

  publishCalendar: (
    organizationId: string,
    input: Omit<CalendarVersion, "id" | "organizationId" | "version">,
  ) =>
    apiClient.post<CalendarVersion>(
      `${workforcePath}/organizations/${encoded(organizationId)}/calendars`,
      input,
    ),

  shiftPolicies: (organizationId: string) =>
    apiClient.get<ShiftPolicy[]>(
      `${workforcePath}/organizations/${encoded(organizationId)}/shift-policies`,
    ),

  publishShiftPolicy: (
    organizationId: string,
    input: Omit<
      ShiftPolicy,
      | "id"
      | "organizationId"
      | "version"
      | "status"
      | "publishedAt"
    >,
  ) =>
    apiClient.post<ShiftPolicy>(
      `${workforcePath}/organizations/${encoded(organizationId)}/shift-policies`,
      input,
    ),

  shiftAssignments: (employeeId: string) =>
    apiClient.get<ShiftAssignment[]>(
      `${workforcePath}/employees/${encoded(employeeId)}/shift-assignments`,
    ),

  assignShift: (
    employeeId: string,
    input: {
      shiftPolicyVersionId: string;
      validFrom: string;
      validTo?: string | null;
    },
  ) =>
    apiClient.post<ShiftAssignment>(
      `${workforcePath}/employees/${encoded(employeeId)}/shift-assignments`,
      input,
    ),

  rosterReadiness: (engagementMonthId: string) =>
    apiClient.get<RosterReadiness>(
      `${workforcePath}/engagement-months/${encoded(engagementMonthId)}/roster-readiness`,
    ),

  rosterSnapshots: (engagementMonthId: string) =>
    apiClient.get<RosterSnapshot[]>(
      `${workforcePath}/engagement-months/${encoded(engagementMonthId)}/roster-snapshots`,
    ),

  finalizeRoster: (engagementMonthId: string, reason: string) =>
    apiClient.post<RosterSnapshot>(
      `${workforcePath}/engagement-months/${encoded(engagementMonthId)}/roster-snapshots`,
      { reason },
    ),

  leavePolicies: (organizationId: string) =>
    apiClient.get<LeavePolicy[]>(
      `${workforcePath}/organizations/${encoded(organizationId)}/leave-policies`,
    ),

  publishLeavePolicy: (
    organizationId: string,
    input: {
      leaveTypeCode: string;
      leaveTypeName: string;
      paid: boolean;
      balanceTracked: boolean;
      minimumIncrement: number;
      validFrom: string;
      validTo?: string | null;
      approvalRequired: boolean;
      maximumUnitsPerRequest?: number | null;
      excessToLwp: boolean;
      cancellationAllowed: boolean;
      rules: Record<string, unknown>;
    },
  ) =>
    apiClient.post<LeavePolicy>(
      `${workforcePath}/organizations/${encoded(organizationId)}/leave-policies`,
      input,
    ),

  leaveBalances: (employeeId: string) =>
    apiClient.get<LeaveBalance[]>(
      `${workforcePath}/employees/${encoded(employeeId)}/leave-balances`,
    ),

  leaveRequests: (employeeId: string) =>
    apiClient.get<LeaveRequest[]>(
      `${workforcePath}/employees/${encoded(employeeId)}/leave-requests`,
    ),

  leaveRequestInbox: (organizationId: string) =>
    apiClient.get<LeaveRequest[]>(
      `${workforcePath}/leave-request-inbox?organizationId=${encoded(organizationId)}`,
    ),

  recordBalanceCommand: (
    employeeId: string,
    input: {
      leaveTypeId: string;
      commandType: "ACCRUAL" | "GRANT" | "ADJUSTMENT";
      quantity: number;
      effectiveDate: string;
      idempotencyKey: string;
      reason: string;
    },
  ) =>
    apiClient.post(
      `${workforcePath}/employees/${encoded(employeeId)}/leave-balance-commands`,
      input,
    ),

  createLeaveRequest: (
    employeeId: string,
    input: CreateLeaveRequestInput,
    idempotencyKey: string,
  ) =>
    apiClient.post<LeaveRequest>(
      `${workforcePath}/employees/${encoded(employeeId)}/leave-requests`,
      { ...input, idempotencyKey },
    ),

  decideLeave: (
    requestId: string,
    input: {
      decision: "APPROVE" | "REJECT" | "CANCEL";
      expectedVersion: number;
      idempotencyKey: string;
      reason: string;
    },
  ) =>
    apiClient.post<LeaveDecision>(
      `${workforcePath}/leave-requests/${encoded(requestId)}/decisions`,
      input,
    ),

  regularizationInbox: (organizationId: string) =>
    apiClient.get<RegularizationRequest[]>(
      `${workforcePath}/regularization-inbox?organizationId=${encoded(organizationId)}`,
    ),

  decideRegularization: (
    requestId: string,
    input: {
      decision: "APPROVE" | "REJECT";
      adjustedNetMinutes?: number | null;
      reasoning: string;
    },
  ) =>
    apiClient.post(
      `${attendancePath}/regularizations/${encoded(requestId)}/decisions`,
      input,
    ),

  importCsv: (
    organizationId: string,
    input: {
      importType: WorkforceCsvImport["importType"];
      fileName: string;
      csvContent: string;
      idempotencyKey: string;
      apply: boolean;
    },
  ) =>
    apiClient.post<WorkforceCsvImport>(
      `${workforcePath}/organizations/${encoded(organizationId)}/imports`,
      input,
    ),

  attendanceDays: (employeeId: string, from: string, to: string) =>
    apiClient.get<AttendanceDay[]>(
      `${attendancePath}/days?employeeId=${encoded(employeeId)}&from=${encoded(from)}&to=${encoded(to)}`,
    ),

  punch: (
    employeeId: string,
    eventType: "CHECK_IN" | "CHECK_OUT" | "BREAK_START" | "BREAK_END",
    idempotencyKey: string,
  ) =>
    apiClient.post<Punch>(`${attendancePath}/punches`, {
      employeeId,
      eventType,
      idempotencyKey,
    }),

  regularizations: (employeeId: string) =>
    apiClient.get<RegularizationRequest[]>(
      `${attendancePath}/regularizations?employeeId=${encoded(employeeId)}`,
    ),

  createRegularization: (
    input: CreateRegularizationInput,
    idempotencyKey: string,
  ) =>
    apiClient.post<RegularizationRequest>(
      `${attendancePath}/regularizations`,
      {
        employeeId: input.employeeId,
        workDate: input.workDate,
        reasonCode: input.reasonCode,
        narrative: input.narrative,
        requestedOutcome: input.requestedOutcome,
        idempotencyKey,
      },
    ),

  snapshots: (engagementMonthId: string) =>
    apiClient.get<MonthlyAttendanceSnapshot[]>(
      `${attendancePath}/month-snapshots?engagementMonthId=${encoded(engagementMonthId)}`,
    ),
};
