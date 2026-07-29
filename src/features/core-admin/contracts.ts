import { z } from "zod";

export const organizationSchema = z.object({
  id: z.string().min(1),
  code: z.string().min(1),
  displayName: z.string().min(1),
  legalName: z.string().optional(),
  organizationType: z.string().optional(),
  status: z.string().optional(),
  defaultTimezone: z.string().optional(),
  defaultLocale: z.string().optional(),
});

export const engagementSchema = z.object({
  id: z.string().min(1),
  engagementCode: z.string().default(""),
  name: z.string().min(1),
  clientOrganizationId: z.string().default(""),
  vendorOrganizationId: z.string().default(""),
  procurementOrganizationId: z.string().nullable().optional(),
  engagementModel: z.string().default("DEDICATED_RESOURCE_MONTHLY"),
  startDate: z.string().default(""),
  endDate: z.string().nullable().optional(),
  status: z.string().default("ACTIVE"),
  defaultProjectId: z.string().nullable().optional(),
  configurationVersionId: z.string().nullable().optional(),
  adminVersion: z.number().int().nonnegative().default(0),
});

export const engagementAdministrationSchema = z.object({
  id: z.string().min(1),
  engagementCode: z.string(),
  name: z.string(),
  status: z.string(),
  defaultProjectId: z.string().nullable().optional(),
  configurationVersionId: z.string().nullable().optional(),
  version: z.number().int().nonnegative(),
});

export const engagementMonthSchema = z.object({
  id: z.string().min(1),
  engagementId: z.string().min(1),
  monthStartDate: z.string().min(1),
  state: z.string().min(1),
  riskStatus: z.string().optional().default("ON_TRACK"),
  historicalFlag: z.boolean().optional().default(false),
  governanceVersion: z.number().int().nonnegative().optional().default(0),
});

export const configurationSchema = z.object({
  id: z.string(),
  engagementId: z.string(),
  version: z.number().int().positive(),
  status: z.string(),
  validFrom: z.string(),
  validTo: z.string().nullable().optional(),
  timezone: z.string(),
  planningDueDay: z.number().int().nullable().optional(),
  certificationDueDay: z.number().int().nullable().optional(),
  confirmationDueDay: z.number().int().nullable().optional(),
  reopenPolicy: z.record(z.string(), z.unknown()),
  notificationPolicy: z.record(z.string(), z.unknown()),
  publishedAt: z.string().nullable().optional(),
});

export const contactMemberSchema = z.object({
  id: z.string(),
  userProfileId: z.string().nullable().optional(),
  email: z.string().email(),
  displayName: z.string(),
  roleAttribution: z.string(),
  verified: z.boolean(),
  validFrom: z.string(),
  validTo: z.string().nullable().optional(),
  status: z.string(),
});

export const contactGroupSchema = z.object({
  id: z.string(),
  engagementId: z.string(),
  projectId: z.string().nullable().optional(),
  code: z.string(),
  name: z.string(),
  groupType: z.string(),
  status: z.string(),
  version: z.number().int().nonnegative(),
  members: z.array(contactMemberSchema).optional().default([]),
});

export const approvalStageSchema = z.object({
  id: z.string().optional(),
  stageOrder: z.number().int().positive(),
  name: z.string(),
  roleCode: z.string().nullable().optional(),
  contactGroupId: z.string().nullable().optional(),
  explicitAssigneeId: z.string().nullable().optional(),
  quorumMode: z.enum(["ANY_ONE", "ALL", "N_OF_M"]),
  quorumRequired: z.number().int().positive(),
  allowDelegation: z.boolean(),
  dueDurationHours: z.number().int().positive().nullable().optional(),
});

export const approvalPolicySchema = z.object({
  id: z.string(),
  engagementId: z.string(),
  projectId: z.string().nullable().optional(),
  code: z.string(),
  name: z.string(),
  actionType: z.string(),
  status: z.string(),
  version: z.number().int().nonnegative(),
  policyVersionId: z.string(),
  policyVersion: z.number().int().positive(),
  versionStatus: z.string(),
  validFrom: z.string(),
  validTo: z.string().nullable().optional(),
  prohibitSelfApproval: z.boolean().optional().default(true),
  evidenceRequired: z.boolean().optional().default(true),
  rules: z.record(z.string(), z.unknown()).optional().default({}),
  stages: z.array(approvalStageSchema).optional().default([]),
});

export const delegationSchema = z.object({
  id: z.string(),
  organizationId: z.string(),
  engagementId: z.string().nullable().optional(),
  projectId: z.string().nullable().optional(),
  delegatorUserId: z.string(),
  delegatorName: z.string().optional(),
  delegateUserId: z.string(),
  delegateName: z.string().optional(),
  actionCodes: z.array(z.string()),
  validFrom: z.string(),
  validTo: z.string(),
  status: z.string(),
  reason: z.string(),
  version: z.number().int().nonnegative(),
});

export const monthTransitionSchema = z.object({
  id: z.string(),
  engagementMonthId: z.string(),
  fromState: z.string(),
  toState: z.string(),
  fromVersion: z.number().int().nonnegative(),
  toVersion: z.number().int().nonnegative(),
  actorSubject: z.string(),
  reason: z.string(),
  correlationId: z.string().nullable().optional(),
  transitionedAt: z.string(),
});

export const approvalActionSchema = z.object({
  id: z.string(),
  stageOrder: z.number().int().positive(),
  decision: z.enum([
    "APPROVED",
    "REJECTED",
    "CHANGES_REQUESTED",
    "CANCELLED",
  ]),
  actorUserId: z.string(),
  actorSubject: z.string(),
  authoritySnapshot: z.record(z.string(), z.unknown()),
  delegatedFromUserId: z.string().nullable().optional(),
  delegationId: z.string().nullable().optional(),
  source: z.string(),
  reason: z.string().nullable().optional(),
  actedAt: z.string(),
});

export const approvalRequestSchema = z.object({
  id: z.string(),
  policyId: z.string(),
  policyVersionId: z.string(),
  engagementId: z.string(),
  projectId: z.string().nullable().optional(),
  objectType: z.string(),
  objectId: z.string(),
  objectVersion: z.number().int().nonnegative(),
  objectHash: z.string().regex(/^[0-9a-f]{64}$/),
  requiredPermissionCode: z.string(),
  currentStageOrder: z.number().int().positive(),
  status: z.enum([
    "PENDING",
    "APPROVED",
    "REJECTED",
    "CHANGES_REQUESTED",
    "CANCELLED",
    "EXPIRED",
    "SUPERSEDED",
  ]),
  version: z.number().int().nonnegative(),
  requestedBySubject: z.string(),
  requestedAt: z.string(),
  evidenceRequired: z.boolean(),
  stages: z.array(approvalStageSchema),
  actions: z.array(approvalActionSchema),
});

export type OrganizationView = z.infer<typeof organizationSchema>;
export type EngagementView = z.infer<typeof engagementSchema>;
export type EngagementAdministrationView = z.infer<
  typeof engagementAdministrationSchema
>;
export type EngagementMonthView = z.infer<typeof engagementMonthSchema>;
export type ConfigurationView = z.infer<typeof configurationSchema>;
export type ContactMemberView = z.infer<typeof contactMemberSchema>;
export type ContactGroupView = z.infer<typeof contactGroupSchema>;
export type ApprovalStageView = z.infer<typeof approvalStageSchema>;
export type ApprovalPolicyView = z.infer<typeof approvalPolicySchema>;
export type DelegationView = z.infer<typeof delegationSchema>;
export type MonthTransitionView = z.infer<typeof monthTransitionSchema>;
export type ApprovalActionView = z.infer<typeof approvalActionSchema>;
export type ApprovalRequestView = z.infer<typeof approvalRequestSchema>;

export type UpdateEngagementRequest = {
  name: string;
  status: string;
  defaultProjectId?: string | null;
  expectedVersion: number;
};

export type PublishConfigurationRequest = {
  validFrom: string;
  validTo?: string | null;
  timezone: string;
  planningDueDay?: number | null;
  certificationDueDay?: number | null;
  confirmationDueDay?: number | null;
  reopenPolicy: Record<string, unknown>;
  notificationPolicy: Record<string, unknown>;
  expectedEngagementVersion: number;
};

export type CreateContactGroupRequest = {
  code: string;
  name: string;
  groupType: string;
  projectId?: string | null;
};

export type AddContactMemberRequest = {
  email: string;
  displayName: string;
  roleAttribution: string;
  verified: boolean;
  validFrom: string;
  validTo?: string | null;
  userProfileId?: string | null;
  expectedGroupVersion: number;
};

export type ApprovalStageInput = {
  name: string;
  roleCode?: string | null;
  contactGroupId?: string | null;
  explicitAssigneeId?: string | null;
  quorumMode: "ANY_ONE" | "ALL" | "N_OF_M";
  quorumRequired: number;
  allowDelegation: boolean;
  dueDurationHours?: number | null;
};

export type CreateApprovalPolicyRequest = {
  code: string;
  name: string;
  actionType: string;
  projectId?: string | null;
  validFrom: string;
  validTo?: string | null;
  prohibitSelfApproval: boolean;
  evidenceRequired: boolean;
  rules: Record<string, unknown>;
  stages: ApprovalStageInput[];
};

export type PublishApprovalPolicyRequest = {
  expectedPolicyVersion: number;
};

export type ReviseApprovalPolicyRequest = {
  name: string;
  validFrom: string;
  validTo?: string | null;
  prohibitSelfApproval: boolean;
  evidenceRequired: boolean;
  rules: Record<string, unknown>;
  stages: ApprovalStageInput[];
  expectedPolicyVersion: number;
};

export type CreateApprovalRequestInput = {
  policyId: string;
  objectId: string;
  idempotencyKey: string;
};

export type ApprovalActionInput = {
  decision: "APPROVED" | "REJECTED" | "CHANGES_REQUESTED" | "CANCELLED";
  reason?: string | null;
  delegationId?: string | null;
  idempotencyKey: string;
  expectedRequestVersion: number;
};

export type CreateDelegationRequest = {
  organizationId: string;
  projectId?: string | null;
  delegatorUserId: string;
  delegateUserId: string;
  actionCodes: string[];
  validFrom: string;
  validTo: string;
  reason: string;
};

export type RevokeDelegationRequest = {
  expectedVersion: number;
  reason: string;
};

export type TransitionMonthRequest = {
  targetState: string;
  expectedVersion: number;
  reason: string;
};

export const eligibleUserSchema = z.object({
  id: z.string(),
  organizationId: z.string(),
  displayName: z.string(),
  email: z.string().email(),
  activeRoleCodes: z.array(z.string()),
});

export type EligibleUserView = z.infer<typeof eligibleUserSchema>;
