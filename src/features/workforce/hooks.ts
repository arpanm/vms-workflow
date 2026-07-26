import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import type {
  CreateLeaveRequestInput,
  CreateRegularizationInput,
} from "./domain";
import { workforceApi } from "./api";

export const workforceKeys = {
  organizations: ["catalog", "organizations"] as const,
  engagements: (organizationId: string) =>
    ["catalog", "engagements", organizationId] as const,
  engagementMonths: (engagementId: string) =>
    ["catalog", "engagement-months", engagementId] as const,
  employees: (organizationId: string) =>
    ["workforce", "employees", organizationId] as const,
  myEmployee: ["workforce", "employees", "me"] as const,
  employee: (employeeId: string) =>
    ["workforce", "employee", employeeId] as const,
  allocations: (employeeId: string) =>
    ["workforce", "allocations", employeeId] as const,
  leaveBalances: (employeeId: string) =>
    ["workforce", "leave-balances", employeeId] as const,
  leaveRequests: (employeeId: string) =>
    ["workforce", "leave-requests", employeeId] as const,
  attendanceDays: (employeeId: string, from: string, to: string) =>
    ["attendance", "days", employeeId, from, to] as const,
  regularizations: (employeeId: string) =>
    ["attendance", "regularizations", employeeId] as const,
  snapshots: (engagementMonthId: string) =>
    ["attendance", "month-snapshots", engagementMonthId] as const,
};

export function useOrganizations() {
  return useQuery({
    queryKey: workforceKeys.organizations,
    queryFn: workforceApi.organizations,
  });
}

export function useCatalogEngagements(organizationId: string) {
  return useQuery({
    queryKey: workforceKeys.engagements(organizationId),
    queryFn: () => workforceApi.engagements(organizationId),
    enabled: Boolean(organizationId),
  });
}

export function useEngagementMonths(engagementId: string) {
  return useQuery({
    queryKey: workforceKeys.engagementMonths(engagementId),
    queryFn: () => workforceApi.engagementMonths(engagementId),
    enabled: Boolean(engagementId),
  });
}

export function useEmployees(organizationId: string) {
  return useQuery({
    queryKey: workforceKeys.employees(organizationId),
    queryFn: () => workforceApi.employees(organizationId),
    enabled: Boolean(organizationId),
  });
}

export function useMyEmployee() {
  return useQuery({
    queryKey: workforceKeys.myEmployee,
    queryFn: workforceApi.myEmployee,
    retry: false,
  });
}

export function useEmployee(employeeId: string) {
  return useQuery({
    queryKey: workforceKeys.employee(employeeId),
    queryFn: () => workforceApi.employee(employeeId),
    enabled: Boolean(employeeId),
  });
}

export function useAllocations(employeeId: string) {
  return useQuery({
    queryKey: workforceKeys.allocations(employeeId),
    queryFn: () => workforceApi.allocations(employeeId),
    enabled: Boolean(employeeId),
  });
}

export function useLeaveBalances(employeeId: string) {
  return useQuery({
    queryKey: workforceKeys.leaveBalances(employeeId),
    queryFn: () => workforceApi.leaveBalances(employeeId),
    enabled: Boolean(employeeId),
  });
}

export function useLeaveRequests(employeeId: string) {
  return useQuery({
    queryKey: workforceKeys.leaveRequests(employeeId),
    queryFn: () => workforceApi.leaveRequests(employeeId),
    enabled: Boolean(employeeId),
  });
}

export function useAttendanceDays(
  employeeId: string,
  from: string,
  to: string,
) {
  return useQuery({
    queryKey: workforceKeys.attendanceDays(employeeId, from, to),
    queryFn: () => workforceApi.attendanceDays(employeeId, from, to),
    enabled: Boolean(employeeId && from && to),
  });
}

export function useRegularizations(employeeId: string) {
  return useQuery({
    queryKey: workforceKeys.regularizations(employeeId),
    queryFn: () => workforceApi.regularizations(employeeId),
    enabled: Boolean(employeeId),
  });
}

export function useSnapshots(engagementMonthId: string) {
  return useQuery({
    queryKey: workforceKeys.snapshots(engagementMonthId),
    queryFn: () => workforceApi.snapshots(engagementMonthId),
    enabled: Boolean(engagementMonthId),
  });
}

function idempotencyKey(prefix: string) {
  return `${prefix}:${crypto.randomUUID()}`;
}

export function usePunch(employeeId: string, date: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (eventType: "CHECK_IN" | "CHECK_OUT") =>
      workforceApi.punch(
        employeeId,
        eventType,
        idempotencyKey("attendance-punch"),
      ),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: workforceKeys.attendanceDays(employeeId, date, date),
      }),
  });
}

export function useCreateLeaveRequest(employeeId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: CreateLeaveRequestInput) =>
      workforceApi.createLeaveRequest(
        employeeId,
        input,
        idempotencyKey("leave-request"),
      ),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: workforceKeys.leaveRequests(employeeId),
        }),
        queryClient.invalidateQueries({
          queryKey: workforceKeys.leaveBalances(employeeId),
        }),
      ]);
    },
  });
}

export function useCreateRegularization(employeeId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: CreateRegularizationInput) =>
      workforceApi.createRegularization(
        input,
        idempotencyKey("regularization"),
      ),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: workforceKeys.regularizations(employeeId),
      }),
  });
}
