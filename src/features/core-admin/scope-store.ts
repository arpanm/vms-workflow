export type ScopeSelection = {
  organizationId: string;
  engagementId: string;
  monthId: string;
};

export type PersistedScope = ScopeSelection & {
  authorityFingerprint: string;
};

export const EMPTY_SCOPE: ScopeSelection = {
  organizationId: "",
  engagementId: "",
  monthId: "",
};

export const ACTIVE_SCOPE_STORAGE_KEY = "cadence.active-scope.v1";

export function authorityFingerprint(user: {
  id: string;
  permissions?: readonly string[];
  organizationIds?: readonly string[];
  memberships?: ReadonlyArray<{
    organizationId: string;
    roleCode?: string;
    validFrom?: string;
    validTo?: string | null;
  }>;
}) {
  const memberships = (user.memberships ?? [])
    .map(
      (membership) =>
        `${membership.organizationId}:${membership.roleCode ?? ""}:${membership.validFrom ?? ""}:${membership.validTo ?? ""}`,
    )
    .sort();
  return JSON.stringify([
    user.id,
    [...(user.organizationIds ?? [])].sort(),
    [...(user.permissions ?? [])].sort(),
    memberships,
  ]);
}

export function readPersistedScope(
  storage: Pick<Storage, "getItem"> | undefined,
  fingerprint: string,
): ScopeSelection {
  if (!storage) return EMPTY_SCOPE;
  try {
    const raw = storage.getItem(ACTIVE_SCOPE_STORAGE_KEY);
    if (!raw) return EMPTY_SCOPE;
    const value = JSON.parse(raw) as Partial<PersistedScope>;
    if (
      value.authorityFingerprint !== fingerprint ||
      typeof value.organizationId !== "string" ||
      typeof value.engagementId !== "string" ||
      typeof value.monthId !== "string"
    ) {
      return EMPTY_SCOPE;
    }
    return {
      organizationId: value.organizationId,
      engagementId: value.engagementId,
      monthId: value.monthId,
    };
  } catch {
    return EMPTY_SCOPE;
  }
}

export function writePersistedScope(
  storage: Pick<Storage, "setItem"> | undefined,
  fingerprint: string,
  selection: ScopeSelection,
) {
  if (!storage) return;
  try {
    storage.setItem(
      ACTIVE_SCOPE_STORAGE_KEY,
      JSON.stringify({ authorityFingerprint: fingerprint, ...selection }),
    );
  } catch {
    // Storage is an optional optimization and can be disabled by policy.
  }
}

export function reconcileScope(
  selection: ScopeSelection,
  organizations: readonly { id: string }[],
  engagements: readonly { id: string }[],
  months: readonly { id: string }[],
): ScopeSelection {
  const organizationId = organizations.some((item) => item.id === selection.organizationId)
    ? selection.organizationId
    : (organizations[0]?.id ?? "");
  const engagementId = engagements.some((item) => item.id === selection.engagementId)
    ? selection.engagementId
    : (engagements[0]?.id ?? "");
  const monthId = months.some((item) => item.id === selection.monthId)
    ? selection.monthId
    : (months[0]?.id ?? "");
  return { organizationId, engagementId, monthId };
}
