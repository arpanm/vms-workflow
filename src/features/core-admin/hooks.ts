import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { coreAdminApi } from "./api";
import type {
  AddContactMemberRequest,
  ApprovalActionInput,
  CreateApprovalRequestInput,
  CreateApprovalPolicyRequest,
  CreateContactGroupRequest,
  CreateDelegationRequest,
  PublishApprovalPolicyRequest,
  ReviseApprovalPolicyRequest,
  PublishConfigurationRequest,
  RevokeDelegationRequest,
  TransitionMonthRequest,
  UpdateEngagementRequest,
} from "./contracts";

export const coreAdminKeys = {
  organizations: ["core-admin", "organizations"] as const,
  engagements: (organizationId: string) =>
    ["core-admin", "engagements", organizationId] as const,
  engagement: (engagementId: string) =>
    ["core-admin", "engagement", engagementId] as const,
  configurations: (engagementId: string) =>
    ["core-admin", "configurations", engagementId] as const,
  months: (engagementId: string) =>
    ["core-admin", "months", engagementId] as const,
  contactGroups: (engagementId: string) =>
    ["core-admin", "contact-groups", engagementId] as const,
  approvalPolicies: (engagementId: string) =>
    ["core-admin", "approval-policies", engagementId] as const,
  approvalRequests: (engagementId: string) =>
    ["core-admin", "approval-requests", engagementId] as const,
  approvalRequest: (requestId: string) =>
    ["core-admin", "approval-request", requestId] as const,
  delegations: (engagementId: string) =>
    ["core-admin", "delegations", engagementId] as const,
  eligibleUsers: (engagementId: string, organizationId: string) =>
    ["core-admin", "eligible-users", engagementId, organizationId] as const,
  monthTransitions: (monthId: string) =>
    ["core-admin", "month-transitions", monthId] as const,
};

export function useCoreOrganizations(enabled = true) {
  return useQuery({
    queryKey: coreAdminKeys.organizations,
    queryFn: coreAdminApi.organizations,
    enabled,
  });
}

export function useCoreEngagements(organizationId: string) {
  return useQuery({
    queryKey: coreAdminKeys.engagements(organizationId),
    queryFn: () => coreAdminApi.engagements(organizationId),
    enabled: Boolean(organizationId),
  });
}

export function useCoreEngagement(engagementId: string, enabled = true) {
  return useQuery({
    queryKey: coreAdminKeys.engagement(engagementId),
    queryFn: () => coreAdminApi.engagement(engagementId),
    enabled: Boolean(engagementId) && enabled,
  });
}

export function useCoreMonths(engagementId: string) {
  return useQuery({
    queryKey: coreAdminKeys.months(engagementId),
    queryFn: () => coreAdminApi.months(engagementId),
    enabled: Boolean(engagementId),
  });
}

export function useConfigurations(engagementId: string, enabled = true) {
  return useQuery({
    queryKey: coreAdminKeys.configurations(engagementId),
    queryFn: () => coreAdminApi.configurations(engagementId),
    enabled: Boolean(engagementId) && enabled,
  });
}

export function useContactGroups(engagementId: string, enabled = true) {
  return useQuery({
    queryKey: coreAdminKeys.contactGroups(engagementId),
    queryFn: () => coreAdminApi.contactGroups(engagementId),
    enabled: Boolean(engagementId) && enabled,
  });
}

export function useApprovalPolicies(engagementId: string, enabled = true) {
  return useQuery({
    queryKey: coreAdminKeys.approvalPolicies(engagementId),
    queryFn: () => coreAdminApi.approvalPolicies(engagementId),
    enabled: Boolean(engagementId) && enabled,
  });
}

export function useApprovalRequests(engagementId: string, enabled = true) {
  return useQuery({
    queryKey: coreAdminKeys.approvalRequests(engagementId),
    queryFn: () => coreAdminApi.approvalRequests(engagementId),
    enabled: Boolean(engagementId) && enabled,
  });
}

export function useApprovalRequest(requestId: string, enabled = true) {
  return useQuery({
    queryKey: coreAdminKeys.approvalRequest(requestId),
    queryFn: () => coreAdminApi.approvalRequest(requestId),
    enabled: Boolean(requestId) && enabled,
  });
}

export function useDelegations(engagementId: string, enabled = true) {
  return useQuery({
    queryKey: coreAdminKeys.delegations(engagementId),
    queryFn: () => coreAdminApi.delegations(engagementId),
    enabled: Boolean(engagementId) && enabled,
  });
}

export function useEligibleUsers(
  engagementId: string,
  organizationId: string,
  enabled = true,
) {
  return useQuery({
    queryKey: coreAdminKeys.eligibleUsers(engagementId, organizationId),
    queryFn: () => coreAdminApi.eligibleUsers(engagementId, organizationId),
    enabled: Boolean(engagementId) && Boolean(organizationId) && enabled,
  });
}

export function useMonthTransitions(monthId: string, enabled = true) {
  return useQuery({
    queryKey: coreAdminKeys.monthTransitions(monthId),
    queryFn: () => coreAdminApi.monthTransitions(monthId),
    enabled: Boolean(monthId) && enabled,
  });
}

export function useUpdateEngagement(engagementId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: UpdateEngagementRequest) =>
      coreAdminApi.updateEngagement(engagementId, input),
    onSuccess: (value) => {
      client.setQueryData(coreAdminKeys.engagement(engagementId), value);
      void client.invalidateQueries({ queryKey: ["core-admin", "engagements"] });
    },
  });
}

export function usePublishConfiguration(engagementId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: PublishConfigurationRequest) =>
      coreAdminApi.publishConfiguration(engagementId, input),
    onSuccess: () =>
      Promise.all([
        client.invalidateQueries({
          queryKey: coreAdminKeys.configurations(engagementId),
        }),
        client.invalidateQueries({
          queryKey: coreAdminKeys.engagement(engagementId),
        }),
      ]),
  });
}

export function useCreateContactGroup(engagementId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: CreateContactGroupRequest) =>
      coreAdminApi.createContactGroup(engagementId, input),
    onSuccess: () =>
      client.invalidateQueries({
        queryKey: coreAdminKeys.contactGroups(engagementId),
      }),
  });
}

export function useAddContactMember(engagementId: string, groupId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: AddContactMemberRequest) =>
      coreAdminApi.addContactMember(groupId, input),
    onSuccess: () =>
      client.invalidateQueries({
        queryKey: coreAdminKeys.contactGroups(engagementId),
      }),
  });
}

export function useCreateApprovalPolicy(engagementId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: CreateApprovalPolicyRequest) =>
      coreAdminApi.createApprovalPolicy(engagementId, input),
    onSuccess: () =>
      client.invalidateQueries({
        queryKey: coreAdminKeys.approvalPolicies(engagementId),
      }),
  });
}

export function usePublishApprovalPolicy(engagementId: string, policyId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: PublishApprovalPolicyRequest) =>
      coreAdminApi.publishApprovalPolicy(policyId, input),
    onSuccess: () =>
      client.invalidateQueries({
        queryKey: coreAdminKeys.approvalPolicies(engagementId),
      }),
  });
}

export function useReviseApprovalPolicy(engagementId: string, policyId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: ReviseApprovalPolicyRequest) =>
      coreAdminApi.reviseApprovalPolicy(policyId, input),
    onSuccess: () =>
      client.invalidateQueries({
        queryKey: coreAdminKeys.approvalPolicies(engagementId),
      }),
  });
}

export function useCreateApprovalRequest(engagementId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: CreateApprovalRequestInput) =>
      coreAdminApi.createApprovalRequest(engagementId, input),
    onSuccess: (value) => {
      client.setQueryData(coreAdminKeys.approvalRequest(value.id), value);
      return client.invalidateQueries({
        queryKey: coreAdminKeys.approvalRequests(engagementId),
      });
    },
  });
}

export function useApprovalRequestAction(
  engagementId: string,
  requestId: string,
) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: ApprovalActionInput) =>
      coreAdminApi.actOnApprovalRequest(requestId, input),
    onSuccess: (value) => {
      client.setQueryData(coreAdminKeys.approvalRequest(requestId), value);
      return client.invalidateQueries({
        queryKey: coreAdminKeys.approvalRequests(engagementId),
      });
    },
    onError: () =>
      Promise.all([
        client.invalidateQueries({
          queryKey: coreAdminKeys.approvalRequest(requestId),
        }),
        client.invalidateQueries({
          queryKey: coreAdminKeys.approvalRequests(engagementId),
        }),
      ]),
  });
}

export function useCreateDelegation(engagementId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: CreateDelegationRequest) =>
      coreAdminApi.createDelegation(engagementId, input),
    onSuccess: () =>
      client.invalidateQueries({
        queryKey: coreAdminKeys.delegations(engagementId),
      }),
  });
}

export function useRevokeDelegation(
  engagementId: string,
  delegationId: string,
) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: RevokeDelegationRequest) =>
      coreAdminApi.revokeDelegation(delegationId, input),
    onSuccess: () =>
      client.invalidateQueries({
        queryKey: coreAdminKeys.delegations(engagementId),
      }),
  });
}

export function useTransitionMonth(engagementId: string, monthId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: TransitionMonthRequest) =>
      coreAdminApi.transitionMonth(monthId, input),
    onSuccess: () =>
      Promise.all([
        client.invalidateQueries({ queryKey: coreAdminKeys.months(engagementId) }),
        client.invalidateQueries({
          queryKey: coreAdminKeys.monthTransitions(monthId),
        }),
      ]),
    onError: () =>
      Promise.all([
        client.invalidateQueries({ queryKey: coreAdminKeys.months(engagementId) }),
        client.invalidateQueries({
          queryKey: coreAdminKeys.monthTransitions(monthId),
        }),
      ]),
  });
}
