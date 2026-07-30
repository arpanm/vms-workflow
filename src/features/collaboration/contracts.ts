export type WorkItemBucket = "ALL" | "BACKLOG" | "CURRENT" | "NEXT" | "PAST";
export type WorkItemStatus =
  | "BACKLOG"
  | "PLANNED"
  | "APPROVED"
  | "IN_PROGRESS"
  | "BLOCKED"
  | "DELIVERED"
  | "PARTIALLY_DELIVERED"
  | "NOT_DELIVERED"
  | "CANCELLED";
export type Discipline =
  | "DEVELOPER"
  | "QA"
  | "PRODUCT_MANAGER"
  | "PROGRAM_MANAGER"
  | "UX_DESIGNER"
  | "DEVOPS"
  | "DATA_ANALYST"
  | "OTHER";
export type LinkType =
  | "DOCUMENT"
  | "PRD"
  | "USER_STORY"
  | "FIGMA"
  | "PROTOTYPE"
  | "LINEAR"
  | "JIRA"
  | "CODE_REVIEW"
  | "COMMIT"
  | "TEST_CASES"
  | "TEST_RUN"
  | "OTHER";

export type WorkItemLink = {
  id: string;
  linkType: LinkType;
  label: string;
  url: string;
  createdBySubject: string;
  createdAt: string;
};

export type WorkItemAssignment = {
  id: string;
  userProfileId: string;
  displayName: string;
  email: string;
  discipline: Discipline;
  status: string;
  assignedAt: string;
};

export type WorkItemComment = {
  id: string;
  body: string;
  authorSubject: string;
  mentionedUserIds: string[];
  createdAt: string;
};

export type WorkItemEstimate = {
  id: string;
  userProfileId: string;
  displayName: string;
  hours: number;
  note: string | null;
  deleted: boolean;
  createdAt: string;
};

export type WorkItemEffort = {
  id: string;
  userProfileId: string;
  displayName: string;
  workDate: string;
  hours: number;
  note: string | null;
  createdAt: string;
};

export type WorkItemApproval = {
  id: string;
  stage: "PLAN_L1" | "DELIVERY_L1" | "DELIVERY_L2";
  decision: "APPROVED" | "REJECTED" | "CHANGES_REQUESTED";
  stackRank: number | null;
  comment: string | null;
  actorSubject: string;
  workItemVersion: number;
  decidedAt: string;
};

export type WorkItem = {
  id: string;
  engagementId: string;
  projectId: string;
  engagementMonthId: string | null;
  monthStartDate: string | null;
  workItemCode: string;
  title: string;
  description: string;
  workflowDescription: string;
  acceptanceCriteria: string;
  priority: "P0" | "P1" | "P2" | "P3";
  stackRank: number | null;
  lifecycleStatus: WorkItemStatus;
  deliverySummary: string | null;
  createdOnBehalfOfClient: boolean;
  version: number;
  createdBySubject: string;
  createdAt: string;
  updatedAt: string;
  totalEstimateHours: number;
  totalEffortHours: number;
  links: WorkItemLink[];
  assignments: WorkItemAssignment[];
  comments: WorkItemComment[];
  estimates: WorkItemEstimate[];
  efforts: WorkItemEffort[];
  approvals: WorkItemApproval[];
};

export type CreateWorkItemInput = {
  engagementId: string;
  projectId: string;
  engagementMonthId: string | null;
  workItemCode: string;
  title: string;
  description: string;
  workflowDescription: string;
  acceptanceCriteria: string;
  priority: WorkItem["priority"];
  lifecycleStatus: WorkItemStatus;
  createdOnBehalfOfClient: boolean;
  links: Array<{ linkType: LinkType; label: string; url: string }>;
  assignments: Array<{ userProfileId: string; discipline: Discipline }>;
};

export type ClientView = {
  organizationId: string;
  clientCode: string;
  legalName: string;
  displayName: string;
  status: string;
  engagementId: string;
  engagementCode: string;
  projectId: string;
  projectCode: string;
  provisionedMonthCount: number;
};

export type ClientUser = {
  userProfileId: string;
  organizationId: string;
  identitySubject: string;
  email: string;
  displayName: string;
  status: string;
  roleCodes: string[];
  permissions: string[];
};
