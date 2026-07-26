import { apiClient } from "@/lib/api-client";

import type {
  Allocation,
  AttendanceDay,
  CreateLeaveRequestInput,
  CreateRegularizationInput,
  EmployeeDetail,
  EmployeeSummary,
  EngagementMonthOption,
  EngagementOption,
  LeaveBalance,
  LeaveRequest,
  MonthlyAttendanceSnapshot,
  OrganizationOption,
  Punch,
  RegularizationRequest,
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

  allocations: (employeeId: string) =>
    apiClient.get<Allocation[]>(
      `${workforcePath}/employees/${encoded(employeeId)}/allocations`,
    ),

  leaveBalances: (employeeId: string) =>
    apiClient.get<LeaveBalance[]>(
      `${workforcePath}/employees/${encoded(employeeId)}/leave-balances`,
    ),

  leaveRequests: (employeeId: string) =>
    apiClient.get<LeaveRequest[]>(
      `${workforcePath}/employees/${encoded(employeeId)}/leave-requests`,
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

  attendanceDays: (employeeId: string, from: string, to: string) =>
    apiClient.get<AttendanceDay[]>(
      `${attendancePath}/days?employeeId=${encoded(employeeId)}&from=${encoded(from)}&to=${encoded(to)}`,
    ),

  punch: (
    employeeId: string,
    eventType: "CHECK_IN" | "CHECK_OUT",
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
