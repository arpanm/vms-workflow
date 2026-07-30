import { apiClient } from "@/lib/api-client";

import type {
  ClientUser,
  ClientView,
  CreateWorkItemInput,
  Discipline,
  LinkType,
  WorkItem,
  WorkItemBucket,
  WorkItemStatus,
} from "./contracts";

const root = "/collaboration";
const encoded = encodeURIComponent;

export const collaborationApi = {
  listWorkItems: (
    engagementId: string,
    bucket: WorkItemBucket,
    assignedToMe: boolean,
    mentionedToMe: boolean,
  ) => {
    const query = new URLSearchParams({
      engagementId,
      bucket,
      assignedToMe: String(assignedToMe),
      mentionedToMe: String(mentionedToMe),
    });
    return apiClient.get<WorkItem[]>(`${root}/work-items?${query}`);
  },
  workItem: (id: string) => apiClient.get<WorkItem>(`${root}/work-items/${encoded(id)}`),
  createWorkItem: (input: CreateWorkItemInput) =>
    apiClient.post<WorkItem>(`${root}/work-items`, input),
  bulkCreate: (inputs: CreateWorkItemInput[]) =>
    apiClient.post<WorkItem[]>(`${root}/work-items/bulk`, inputs),
  updateWorkItem: (
    id: string,
    input: {
      expectedVersion: number;
      title: string;
      description: string;
      workflowDescription: string;
      acceptanceCriteria: string;
      priority: WorkItem["priority"];
      engagementMonthId: string | null;
    },
  ) => apiClient.patch<WorkItem>(`${root}/work-items/${encoded(id)}`, input),
  updateStatus: (
    id: string,
    input: { expectedVersion: number; lifecycleStatus: WorkItemStatus; deliverySummary: string },
  ) => apiClient.patch<WorkItem>(`${root}/work-items/${encoded(id)}/delivery-status`, input),
  addComment: (id: string, input: { body: string; mentionedUserIds: string[] }) =>
    apiClient.post<WorkItem>(`${root}/work-items/${encoded(id)}/comments`, input),
  addAssignment: (
    id: string,
    input: { userProfileId: string; discipline: Discipline },
  ) => apiClient.post<WorkItem>(`${root}/work-items/${encoded(id)}/assignments`, input),
  addEstimate: (
    id: string,
    input: { userProfileId: string; hours: number; note: string },
  ) => apiClient.post<WorkItem>(`${root}/work-items/${encoded(id)}/estimates`, input),
  deleteEstimate: (id: string, estimateId: string) =>
    apiClient.delete<WorkItem>(
      `${root}/work-items/${encoded(id)}/estimates/${encoded(estimateId)}`,
    ),
  addEffort: (
    id: string,
    input: { userProfileId: string; workDate: string; hours: number; note: string },
  ) => apiClient.post<WorkItem>(`${root}/work-items/${encoded(id)}/efforts`, input),
  addLink: (id: string, input: { linkType: LinkType; label: string; url: string }) =>
    apiClient.post<WorkItem>(`${root}/work-items/${encoded(id)}/links`, input),
  approve: (
    id: string,
    input: {
      expectedVersion: number;
      stage: "PLAN_L1" | "DELIVERY_L1" | "DELIVERY_L2";
      decision: "APPROVED" | "REJECTED" | "CHANGES_REQUESTED";
      stackRank: number | null;
      comment: string;
    },
  ) => apiClient.post<WorkItem>(`${root}/work-items/${encoded(id)}/approvals`, input),
  onboardClient: (input: {
    clientCode: string;
    legalName: string;
    displayName: string;
    primaryDomain: string;
    timezone: string;
    engagementCode: string;
    engagementName: string;
    vendorOrganizationId: string;
    procurementOrganizationId: string | null;
    engagementModel: string;
    startDate: string;
    projectCode: string;
    projectName: string;
  }) => apiClient.post<ClientView>(`${root}/clients`, input),
  clientUsers: (clientId: string) =>
    apiClient.get<ClientUser[]>(`${root}/clients/${encoded(clientId)}/users`),
  addClientUser: (
    clientId: string,
    input: {
      identitySubject: string;
      email: string;
      displayName: string;
      roleCodes: string[];
      validFrom: string;
      validTo: string | null;
    },
  ) => apiClient.post<ClientUser>(`${root}/clients/${encoded(clientId)}/users`, input),
  grantClientRole: (
    clientId: string,
    userId: string,
    input: {
      roleCode: string;
      scopeType: "ORGANIZATION" | "ENGAGEMENT" | "PROJECT";
      scopeId: string;
      validFrom: string;
      validTo: string | null;
    },
  ) => apiClient.post<ClientUser>(
    `${root}/clients/${encoded(clientId)}/users/${encoded(userId)}/role-grants`,
    input,
  ),
};
