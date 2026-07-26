import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import type {
  ApprovalRequest,
  CreatePlanRequest,
  LinkIssueRequest,
  RevisionRequest,
} from "./contracts";
import { deliveryApi } from "./api";

export const deliveryKeys = {
  plans: (engagementMonthId: string) =>
    ["delivery", "plans", engagementMonthId] as const,
  plan: (planId: string) => ["delivery", "plan", planId] as const,
  issueCurrent: (linkId: string) =>
    ["delivery", "linear", "current", linkId] as const,
  issueSnapshots: (linkId: string) =>
    ["delivery", "linear", "snapshots", linkId] as const,
  health: (engagementId: string) =>
    ["delivery", "linear", "health", engagementId] as const,
};

export function usePlans(engagementMonthId: string) {
  return useQuery({
    queryKey: deliveryKeys.plans(engagementMonthId),
    queryFn: () => deliveryApi.plans(engagementMonthId),
    enabled: Boolean(engagementMonthId),
  });
}

export function usePlan(planId: string) {
  return useQuery({
    queryKey: deliveryKeys.plan(planId),
    queryFn: () => deliveryApi.plan(planId),
    enabled: Boolean(planId),
  });
}

export function useIssueCurrent(linkId: string) {
  return useQuery({
    queryKey: deliveryKeys.issueCurrent(linkId),
    queryFn: () => deliveryApi.issueCurrent(linkId),
    enabled: Boolean(linkId),
  });
}

export function useIssueSnapshots(linkId: string) {
  return useQuery({
    queryKey: deliveryKeys.issueSnapshots(linkId),
    queryFn: () => deliveryApi.issueSnapshots(linkId),
    enabled: Boolean(linkId),
  });
}

export function useLinearHealth(engagementId: string) {
  return useQuery({
    queryKey: deliveryKeys.health(engagementId),
    queryFn: () => deliveryApi.linearHealth(engagementId),
    enabled: Boolean(engagementId),
  });
}

export function useCreatePlan() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: CreatePlanRequest) => deliveryApi.createPlan(input),
    onSuccess: (plan) =>
      queryClient.invalidateQueries({
        queryKey: deliveryKeys.plans(plan.engagementMonthId),
      }),
  });
}

export function useSubmitPlan(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => deliveryApi.submitPlan(planId),
    onSuccess: (plan) => {
      queryClient.setQueryData(deliveryKeys.plan(planId), plan);
      return queryClient.invalidateQueries({
        queryKey: deliveryKeys.plans(plan.engagementMonthId),
      });
    },
    onError: () =>
      queryClient.invalidateQueries({
        queryKey: deliveryKeys.plan(planId),
      }),
  });
}

export function usePlanDecision(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: ApprovalRequest) =>
      deliveryApi.decidePlan(planId, input),
    onSuccess: (plan) => {
      queryClient.setQueryData(deliveryKeys.plan(planId), plan);
      return queryClient.invalidateQueries({
        queryKey: deliveryKeys.plans(plan.engagementMonthId),
      });
    },
  });
}

export function useRevisePlan(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: RevisionRequest) =>
      deliveryApi.revisePlan(planId, input),
    onSuccess: (plan) =>
      queryClient.invalidateQueries({
        queryKey: deliveryKeys.plans(plan.engagementMonthId),
      }),
  });
}

export function useLinkIssue(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: LinkIssueRequest) => deliveryApi.linkIssue(input),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: deliveryKeys.plan(planId),
      }),
  });
}
