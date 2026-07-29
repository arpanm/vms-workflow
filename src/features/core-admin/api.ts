import { apiClient } from "@/lib/api-client";

import {
  approvalRequestSchema,
  approvalPolicySchema,
  configurationSchema,
  contactGroupSchema,
  delegationSchema,
  eligibleUserSchema,
  engagementAdministrationSchema,
  engagementMonthSchema,
  engagementSchema,
  monthTransitionSchema,
  organizationSchema,
  type AddContactMemberRequest,
  type ApprovalActionInput,
  type CreateApprovalRequestInput,
  type CreateApprovalPolicyRequest,
  type CreateContactGroupRequest,
  type CreateDelegationRequest,
  type PublishApprovalPolicyRequest,
  type ReviseApprovalPolicyRequest,
  type PublishConfigurationRequest,
  type RevokeDelegationRequest,
  type TransitionMonthRequest,
  type UpdateEngagementRequest,
} from "./contracts";

const encoded = (value: string) => encodeURIComponent(value);
const corePath = "/core";

export const coreAdminApi = {
  organizations: async () =>
    organizationSchema.array().parse(await apiClient.get<unknown>("/organizations")),

  engagements: async (organizationId: string) =>
    engagementSchema.array().parse(
      await apiClient.get<unknown>(
        `/engagements?organizationId=${encoded(organizationId)}`,
      ),
    ),

  months: async (engagementId: string) =>
    engagementMonthSchema.array().parse(
      await apiClient.get<unknown>(
        `/engagement-months?engagementId=${encoded(engagementId)}`,
      ),
    ),

  engagement: async (engagementId: string) =>
    engagementAdministrationSchema.parse(
      await apiClient.get<unknown>(
        `${corePath}/engagements/${encoded(engagementId)}`,
      ),
    ),

  updateEngagement: async (
    engagementId: string,
    input: UpdateEngagementRequest,
  ) =>
    engagementAdministrationSchema.parse(
      await apiClient.patch<unknown>(
        `${corePath}/engagements/${encoded(engagementId)}`,
        input,
      ),
    ),

  configurations: async (engagementId: string) =>
    configurationSchema.array().parse(
      await apiClient.get<unknown>(
        `${corePath}/engagements/${encoded(engagementId)}/configurations`,
      ),
    ),

  publishConfiguration: async (
    engagementId: string,
    input: PublishConfigurationRequest,
  ) =>
    configurationSchema.parse(
      await apiClient.post<unknown>(
        `${corePath}/engagements/${encoded(engagementId)}/configurations`,
        input,
      ),
    ),

  contactGroups: async (engagementId: string) =>
    contactGroupSchema.array().parse(
      await apiClient.get<unknown>(
        `${corePath}/engagements/${encoded(engagementId)}/contact-groups`,
      ),
    ),

  createContactGroup: async (
    engagementId: string,
    input: CreateContactGroupRequest,
  ) =>
    contactGroupSchema.parse(
      await apiClient.post<unknown>(
        `${corePath}/engagements/${encoded(engagementId)}/contact-groups`,
        input,
      ),
    ),

  addContactMember: async (
    groupId: string,
    input: AddContactMemberRequest,
  ) =>
    contactGroupSchema.parse(
      await apiClient.post<unknown>(
        `${corePath}/contact-groups/${encoded(groupId)}/members`,
        input,
      ),
    ),

  approvalPolicies: async (engagementId: string) =>
    approvalPolicySchema.array().parse(
      await apiClient.get<unknown>(
        `${corePath}/engagements/${encoded(engagementId)}/approval-policies`,
      ),
    ),

  createApprovalPolicy: async (
    engagementId: string,
    input: CreateApprovalPolicyRequest,
  ) =>
    approvalPolicySchema.parse(
      await apiClient.post<unknown>(
        `${corePath}/engagements/${encoded(engagementId)}/approval-policies`,
        input,
      ),
    ),

  publishApprovalPolicy: async (
    policyId: string,
    input: PublishApprovalPolicyRequest,
  ) =>
    approvalPolicySchema.parse(
      await apiClient.post<unknown>(
        `${corePath}/approval-policies/${encoded(policyId)}/publish`,
        input,
      ),
    ),

  reviseApprovalPolicy: async (
    policyId: string,
    input: ReviseApprovalPolicyRequest,
  ) =>
    approvalPolicySchema.parse(
      await apiClient.post<unknown>(
        `${corePath}/approval-policies/${encoded(policyId)}/revisions`,
        input,
      ),
    ),

  approvalRequests: async (engagementId: string) =>
    approvalRequestSchema.array().parse(
      await apiClient.get<unknown>(
        `${corePath}/engagements/${encoded(engagementId)}/approval-requests`,
      ),
    ),

  approvalRequest: async (requestId: string) =>
    approvalRequestSchema.parse(
      await apiClient.get<unknown>(
        `${corePath}/approval-requests/${encoded(requestId)}`,
      ),
    ),

  createApprovalRequest: async (
    engagementId: string,
    input: CreateApprovalRequestInput,
  ) =>
    approvalRequestSchema.parse(
      await apiClient.post<unknown>(
        `${corePath}/engagements/${encoded(engagementId)}/approval-requests`,
        input,
      ),
    ),

  actOnApprovalRequest: async (
    requestId: string,
    input: ApprovalActionInput,
  ) =>
    approvalRequestSchema.parse(
      await apiClient.post<unknown>(
        `${corePath}/approval-requests/${encoded(requestId)}/actions`,
        input,
      ),
    ),

  delegations: async (engagementId: string) =>
    delegationSchema.array().parse(
      await apiClient.get<unknown>(
        `${corePath}/engagements/${encoded(engagementId)}/delegations`,
      ),
    ),

  eligibleUsers: async (engagementId: string, organizationId: string) =>
    eligibleUserSchema.array().parse(
      await apiClient.get<unknown>(
        `${corePath}/engagements/${encoded(engagementId)}/eligible-users?organizationId=${encoded(organizationId)}`,
      ),
    ),

  createDelegation: async (
    engagementId: string,
    input: CreateDelegationRequest,
  ) =>
    delegationSchema.parse(
      await apiClient.post<unknown>(
        `${corePath}/engagements/${encoded(engagementId)}/delegations`,
        input,
      ),
    ),

  revokeDelegation: async (
    delegationId: string,
    input: RevokeDelegationRequest,
  ) =>
    delegationSchema.parse(
      await apiClient.post<unknown>(
        `${corePath}/delegations/${encoded(delegationId)}/revoke`,
        input,
      ),
    ),

  transitionMonth: async (monthId: string, input: TransitionMonthRequest) =>
    monthTransitionSchema.parse(
      await apiClient.post<unknown>(
        `${corePath}/engagement-months/${encoded(monthId)}/transitions`,
        input,
      ),
    ),

  monthTransitions: async (monthId: string) =>
    monthTransitionSchema.array().parse(
      await apiClient.get<unknown>(
        `${corePath}/engagement-months/${encoded(monthId)}/transitions`,
      ),
    ),
};
