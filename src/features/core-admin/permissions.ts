export type PermissionRequirement = string | readonly string[];

export function hasPermission(permissions: readonly string[], requirement: PermissionRequirement) {
  const required = typeof requirement === "string" ? [requirement] : requirement;
  const granted = new Set(permissions);
  return required.some((permission) => granted.has(permission));
}

export const coreAdminPermissions = {
  read: ["catalog.read", "engagement.read"] as const,
  engagementUpdate: "engagement.update",
  engagementConfigure: "engagement.configure",
  contactsManage: "contacts.manage",
  approvalPolicyManage: "approval.policy.manage",
  approvalRequestRead: ["approval.request.create", "approval.request.act"] as const,
  approvalRequestCreate: "approval.request.create",
  approvalRequestAct: "approval.request.act",
  delegationManage: "delegation.manage",
  monthTransition: "month.transition",
} as const;

const coreAdministrativeScopePermissions = [
  coreAdminPermissions.engagementUpdate,
  coreAdminPermissions.engagementConfigure,
  coreAdminPermissions.contactsManage,
  coreAdminPermissions.approvalPolicyManage,
  coreAdminPermissions.approvalRequestCreate,
  coreAdminPermissions.approvalRequestAct,
  coreAdminPermissions.delegationManage,
  coreAdminPermissions.monthTransition,
] as const;

export function canUseCoreAdministrativeScope(permissions: readonly string[]) {
  return hasPermission(permissions, coreAdministrativeScopePermissions);
}
