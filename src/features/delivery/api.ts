import { apiClient } from "@/lib/api-client";

import type {
  ApprovalRequest,
  CreatePlanRequest,
  IssueCurrentView,
  IssueLinkView,
  IssueSnapshotView,
  LinearHealthView,
  LinkIssueRequest,
  PlanSummaryView,
  PlanView,
  RevisionRequest,
} from "./contracts";

const deliveryPath = "/delivery";
const linearPath = "/integrations/linear";
const encoded = (value: string) => encodeURIComponent(value);

export const deliveryApi = {
  plans: (engagementMonthId: string) =>
    apiClient.get<PlanSummaryView[]>(
      `${deliveryPath}/plans?engagementMonthId=${encoded(engagementMonthId)}`,
    ),
  plan: (planId: string) =>
    apiClient.get<PlanView>(`${deliveryPath}/plans/${encoded(planId)}`),
  createPlan: (input: CreatePlanRequest) =>
    apiClient.post<PlanView>(`${deliveryPath}/plans`, input),
  submitPlan: (planId: string) =>
    apiClient.post<PlanView>(
      `${deliveryPath}/plans/${encoded(planId)}/submit`,
    ),
  decidePlan: (planId: string, input: ApprovalRequest) =>
    apiClient.post<PlanView>(
      `${deliveryPath}/plans/${encoded(planId)}/approvals`,
      input,
    ),
  revisePlan: (planId: string, input: RevisionRequest) =>
    apiClient.post<PlanView>(
      `${deliveryPath}/plans/${encoded(planId)}/revisions`,
      input,
    ),
  linkIssue: (input: LinkIssueRequest) =>
    apiClient.post<IssueLinkView>(`${linearPath}/links`, input),
  issueCurrent: (linkId: string) =>
    apiClient.get<IssueCurrentView>(
      `${linearPath}/links/${encoded(linkId)}/current`,
    ),
  issueSnapshots: (linkId: string) =>
    apiClient.get<IssueSnapshotView[]>(
      `${linearPath}/links/${encoded(linkId)}/snapshots`,
    ),
  linearHealth: (engagementId: string) =>
    apiClient.get<LinearHealthView>(
      `${linearPath}/health?engagementId=${encoded(engagementId)}`,
    ),
};
