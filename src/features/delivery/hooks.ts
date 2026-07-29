import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import type {
  ApprovalRequest,
  CreatePlanRequest,
  LinkIssueRequest,
  LinearReconciliationRequest,
  RevisionRequest,
} from "./contracts";
import { deliveryApi } from "./api";

export const deliveryKeys = {
  plans: (engagementMonthId: string) =>
    ["delivery", "plans", engagementMonthId] as const,
  plan: (planId: string) => ["delivery", "plan", planId] as const,
  commitmentDeadLetters: (engagementId: string) =>
    ["delivery", "commitment-operations", engagementId] as const,
  revisionComparison: (planId: string) =>
    ["delivery", "plan", planId, "revision-comparison"] as const,
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

export function useRevisionComparison(planId: string) {
  return useQuery({
    queryKey: deliveryKeys.revisionComparison(planId),
    queryFn: () => deliveryApi.revisionComparison(planId),
    enabled: Boolean(planId),
  });
}

export function useCommitmentDeadLetters(engagementId: string, enabled: boolean) {
  return useQuery({
    queryKey: deliveryKeys.commitmentDeadLetters(engagementId),
    queryFn: () => deliveryApi.commitmentDeadLetters(engagementId),
    enabled: Boolean(engagementId) && enabled,
  });
}

export function useCommitmentReplay(engagementId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      outboxId,
      input,
      idempotencyKey,
    }: {
      outboxId: string;
      input: { reason: string };
      idempotencyKey: string;
    }) => deliveryApi.replayCommitment(outboxId, input, idempotencyKey),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: deliveryKeys.commitmentDeadLetters(engagementId),
      }),
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

export function useLinearReconciliation(engagementId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      connectionId,
      input,
      idempotencyKey,
    }: {
      connectionId: string;
      input: LinearReconciliationRequest;
      idempotencyKey: string;
    }) => deliveryApi.reconcileLinear(connectionId, input, idempotencyKey),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: deliveryKeys.health(engagementId),
      }),
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
