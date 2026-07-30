import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import type {
  CreateEmployeeInput,
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
  deliverableAllocations: (employeeId: string) =>
    ["workforce", "deliverable-allocations", employeeId] as const,
  leaveBalances: (employeeId: string) =>
    ["workforce", "leave-balances", employeeId] as const,
  leaveRequests: (employeeId: string) =>
    ["workforce", "leave-requests", employeeId] as const,
  leaveRequestInbox: (organizationId: string) =>
    ["workforce", "leave-request-inbox", organizationId] as const,
  aliases: (employeeId: string) =>
    ["workforce", "aliases", employeeId] as const,
  calendars: (organizationId: string) =>
    ["workforce", "calendars", organizationId] as const,
  shiftPolicies: (organizationId: string) =>
    ["workforce", "shift-policies", organizationId] as const,
  shiftAssignments: (employeeId: string) =>
    ["workforce", "shift-assignments", employeeId] as const,
  rosterReadiness: (engagementMonthId: string) =>
    ["workforce", "roster-readiness", engagementMonthId] as const,
  rosterSnapshots: (engagementMonthId: string) =>
    ["workforce", "roster-snapshots", engagementMonthId] as const,
  leavePolicies: (organizationId: string) =>
    ["workforce", "leave-policies", organizationId] as const,
  regularizationInbox: (organizationId: string) =>
    ["workforce", "regularization-inbox", organizationId] as const,
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

export function useCreateEmployee(organizationId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: CreateEmployeeInput) => workforceApi.createEmployee(input),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: workforceKeys.employees(organizationId),
      }),
  });
}

export function useEditEmployee(employeeId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: Parameters<typeof workforceApi.editEmployee>[1]) =>
      workforceApi.editEmployee(employeeId, input),
    onSuccess: (employee) => {
      queryClient.setQueryData(workforceKeys.employee(employeeId), employee);
      return queryClient.invalidateQueries({ queryKey: ["workforce", "employees"] });
    },
  });
}

export function useArchiveEmployee(employeeId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: Parameters<typeof workforceApi.archiveEmployee>[1]) =>
      workforceApi.archiveEmployee(employeeId, input),
    onSuccess: (employee) => {
      queryClient.setQueryData(workforceKeys.employee(employeeId), employee);
      return queryClient.invalidateQueries({ queryKey: ["workforce", "employees"] });
    },
  });
}

export function useDisableEmployeeAccess(employeeId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: Parameters<typeof workforceApi.disableEmployeeAccess>[1]) =>
      workforceApi.disableEmployeeAccess(employeeId, input),
    onSuccess: (employee) => {
      queryClient.setQueryData(workforceKeys.employee(employeeId), employee);
      return queryClient.invalidateQueries({ queryKey: ["workforce", "employees"] });
    },
  });
}

export function useCreateAllocation(employeeId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: Parameters<typeof workforceApi.createAllocation>[1]) =>
      workforceApi.createAllocation(employeeId, input),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: workforceKeys.allocations(employeeId) }),
  });
}

export function useEditAllocation(employeeId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      allocationId,
      input,
    }: {
      allocationId: string;
      input: Parameters<typeof workforceApi.editAllocation>[2];
    }) => workforceApi.editAllocation(employeeId, allocationId, input),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: workforceKeys.allocations(employeeId) }),
  });
}

export function useEndAllocation(employeeId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      allocationId,
      input,
    }: {
      allocationId: string;
      input: Parameters<typeof workforceApi.endAllocation>[2];
    }) => workforceApi.endAllocation(employeeId, allocationId, input),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: workforceKeys.allocations(employeeId) }),
  });
}

export function useSplitAllocation(employeeId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      allocationId,
      input,
    }: {
      allocationId: string;
      input: Parameters<typeof workforceApi.splitAllocation>[2];
    }) => workforceApi.splitAllocation(employeeId, allocationId, input),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: workforceKeys.allocations(employeeId) }),
  });
}

export function useDeliverableAllocations(employeeId: string) {
  return useQuery({
    queryKey: workforceKeys.deliverableAllocations(employeeId),
    queryFn: () => workforceApi.deliverableAllocations(employeeId),
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

export function useLeaveRequestInbox(organizationId: string) {
  return useQuery({
    queryKey: workforceKeys.leaveRequestInbox(organizationId),
    queryFn: () => workforceApi.leaveRequestInbox(organizationId),
    enabled: Boolean(organizationId),
  });
}

export function useEmployeeAliases(employeeId: string) {
  return useQuery({
    queryKey: workforceKeys.aliases(employeeId),
    queryFn: () => workforceApi.aliases(employeeId),
    enabled: Boolean(employeeId),
  });
}

export function useWorkforceCalendars(organizationId: string) {
  return useQuery({
    queryKey: workforceKeys.calendars(organizationId),
    queryFn: () => workforceApi.calendars(organizationId),
    enabled: Boolean(organizationId),
  });
}

export function useShiftPolicies(organizationId: string) {
  return useQuery({
    queryKey: workforceKeys.shiftPolicies(organizationId),
    queryFn: () => workforceApi.shiftPolicies(organizationId),
    enabled: Boolean(organizationId),
  });
}

export function useShiftAssignments(employeeId: string) {
  return useQuery({
    queryKey: workforceKeys.shiftAssignments(employeeId),
    queryFn: () => workforceApi.shiftAssignments(employeeId),
    enabled: Boolean(employeeId),
  });
}

export function useRosterReadiness(engagementMonthId: string) {
  return useQuery({
    queryKey: workforceKeys.rosterReadiness(engagementMonthId),
    queryFn: () => workforceApi.rosterReadiness(engagementMonthId),
    enabled: Boolean(engagementMonthId),
  });
}

export function useRosterSnapshots(engagementMonthId: string) {
  return useQuery({
    queryKey: workforceKeys.rosterSnapshots(engagementMonthId),
    queryFn: () => workforceApi.rosterSnapshots(engagementMonthId),
    enabled: Boolean(engagementMonthId),
  });
}

export function useLeavePolicies(organizationId: string) {
  return useQuery({
    queryKey: workforceKeys.leavePolicies(organizationId),
    queryFn: () => workforceApi.leavePolicies(organizationId),
    enabled: Boolean(organizationId),
  });
}

export function useRegularizationInbox(organizationId: string) {
  return useQuery({
    queryKey: workforceKeys.regularizationInbox(organizationId),
    queryFn: () => workforceApi.regularizationInbox(organizationId),
    enabled: Boolean(organizationId),
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
    mutationFn: (
      eventType: "CHECK_IN" | "CHECK_OUT" | "BREAK_START" | "BREAK_END",
    ) =>
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

export function useAddEmployeeAlias(employeeId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: workforceApi.addAlias.bind(null, employeeId),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: workforceKeys.aliases(employeeId),
      }),
  });
}

export function useAddDeliverableAllocation(employeeId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: workforceApi.addDeliverableAllocation.bind(null, employeeId),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: workforceKeys.deliverableAllocations(employeeId),
      }),
  });
}

export function usePublishWorkforceCalendar(organizationId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: workforceApi.publishCalendar.bind(null, organizationId),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: workforceKeys.calendars(organizationId),
      }),
  });
}

export function usePublishShiftPolicy(organizationId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: workforceApi.publishShiftPolicy.bind(null, organizationId),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: workforceKeys.shiftPolicies(organizationId),
      }),
  });
}

export function useAssignShift(employeeId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: workforceApi.assignShift.bind(null, employeeId),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: workforceKeys.shiftAssignments(employeeId),
      }),
  });
}

export function useFinalizeRoster(engagementMonthId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: workforceApi.finalizeRoster.bind(null, engagementMonthId),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({
          queryKey: workforceKeys.rosterReadiness(engagementMonthId),
        }),
        queryClient.invalidateQueries({
          queryKey: workforceKeys.rosterSnapshots(engagementMonthId),
        }),
      ]),
  });
}

export function usePublishLeavePolicy(organizationId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: workforceApi.publishLeavePolicy.bind(null, organizationId),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: workforceKeys.leavePolicies(organizationId),
      }),
  });
}

export function useRecordBalanceCommand(employeeId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: workforceApi.recordBalanceCommand.bind(null, employeeId),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: workforceKeys.leaveBalances(employeeId),
      }),
  });
}

export function useDecideLeave(organizationId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      requestId,
      input,
    }: {
      requestId: string;
      input: Parameters<typeof workforceApi.decideLeave>[1];
    }) => workforceApi.decideLeave(requestId, input),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({
          queryKey: workforceKeys.leaveRequestInbox(organizationId),
        }),
        queryClient.invalidateQueries({
          queryKey: ["workforce", "leave-requests"],
        }),
        queryClient.invalidateQueries({
          queryKey: ["workforce", "leave-balances"],
        }),
      ]),
  });
}

export function useDecideRegularization(organizationId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      requestId,
      input,
    }: {
      requestId: string;
      input: Parameters<typeof workforceApi.decideRegularization>[1];
    }) => workforceApi.decideRegularization(requestId, input),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: workforceKeys.regularizationInbox(organizationId),
      }),
  });
}

export function useWorkforceCsvImport(organizationId: string) {
  return useMutation({
    mutationFn: workforceApi.importCsv.bind(null, organizationId),
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
