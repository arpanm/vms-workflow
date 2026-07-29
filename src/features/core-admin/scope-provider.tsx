import { useQueryClient } from "@tanstack/react-query";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";

import { useSession } from "@/features/auth/session-provider";

import type { EngagementMonthView, EngagementView, OrganizationView } from "./contracts";
import { useCoreEngagements, useCoreMonths, useCoreOrganizations } from "./hooks";
import {
  canUseCoreAdministrativeScope,
  hasPermission,
  type PermissionRequirement,
} from "./permissions";
import {
  EMPTY_SCOPE,
  authorityFingerprint,
  readPersistedScope,
  reconcileScope,
  writePersistedScope,
  type ScopeSelection,
} from "./scope-store";

type ActiveScopeContextValue = {
  enabled: boolean;
  selection: ScopeSelection;
  organization: OrganizationView | null;
  engagement: EngagementView | null;
  month: EngagementMonthView | null;
  organizations: OrganizationView[];
  engagements: EngagementView[];
  months: EngagementMonthView[];
  permissions: string[];
  loading: boolean;
  error: Error | null;
  selectOrganization: (organizationId: string) => void;
  selectEngagement: (engagementId: string) => void;
  selectMonth: (monthId: string) => void;
  can: (requirement: PermissionRequirement) => boolean;
};

const ActiveScopeContext = createContext<ActiveScopeContextValue | null>(null);

function browserStorage() {
  if (typeof window === "undefined") return undefined;
  try {
    return window.localStorage;
  } catch {
    return undefined;
  }
}

export function ActiveScopeProvider({ children }: { children: ReactNode }) {
  const { user } = useSession();
  const queryClient = useQueryClient();
  const permissions = user?.permissions ?? [];
  const enabled = canUseCoreAdministrativeScope(permissions);
  const fingerprint = authorityFingerprint(
    user ?? { id: "demo-or-anonymous", permissions: [], organizationIds: [] },
  );
  const [selection, setSelection] = useState<ScopeSelection>(() =>
    user ? readPersistedScope(browserStorage(), fingerprint) : EMPTY_SCOPE,
  );
  const [selectionFingerprint, setSelectionFingerprint] = useState(user ? fingerprint : "");
  const priorFingerprint = useRef(fingerprint);

  useEffect(() => {
    if (priorFingerprint.current === fingerprint) return;
    priorFingerprint.current = fingerprint;
    queryClient.removeQueries({ queryKey: ["core-admin"] });
    setSelection(readPersistedScope(browserStorage(), fingerprint));
    setSelectionFingerprint(fingerprint);
  }, [fingerprint, queryClient]);

  const organizationsQuery = useCoreOrganizations(enabled);
  const organizations = organizationsQuery.data ?? [];
  const organizationId = organizations.some((item) => item.id === selection.organizationId)
    ? selection.organizationId
    : (organizations[0]?.id ?? "");

  const engagementsQuery = useCoreEngagements(organizationId);
  const engagements = engagementsQuery.data ?? [];
  const engagementId = engagements.some((item) => item.id === selection.engagementId)
    ? selection.engagementId
    : (engagements[0]?.id ?? "");

  const monthsQuery = useCoreMonths(engagementId);
  const months = monthsQuery.data ?? [];
  const monthId = months.some((item) => item.id === selection.monthId)
    ? selection.monthId
    : (months[0]?.id ?? "");

  useEffect(() => {
    if (!enabled) return;
    if (
      organizationsQuery.isPending ||
      (Boolean(organizationId) && engagementsQuery.isPending) ||
      (Boolean(engagementId) && monthsQuery.isPending)
    ) {
      return;
    }
    const resolved = reconcileScope(
      { organizationId, engagementId, monthId },
      organizations,
      engagements,
      months,
    );
    setSelection((current) =>
      current.organizationId === resolved.organizationId &&
      current.engagementId === resolved.engagementId &&
      current.monthId === resolved.monthId
        ? current
        : resolved,
    );
  }, [
    enabled,
    engagementId,
    engagements,
    engagementsQuery.isPending,
    monthId,
    months,
    monthsQuery.isPending,
    organizationId,
    organizations,
    organizationsQuery.isPending,
  ]);

  useEffect(() => {
    if (user && selectionFingerprint === fingerprint) {
      writePersistedScope(browserStorage(), fingerprint, selection);
    }
  }, [fingerprint, selection, selectionFingerprint, user]);

  const selectOrganization = useCallback((next: string) => {
    setSelection({
      organizationId: next,
      engagementId: "",
      monthId: "",
    });
  }, []);
  const selectEngagement = useCallback((next: string) => {
    setSelection((current) => ({
      organizationId: current.organizationId,
      engagementId: next,
      monthId: "",
    }));
  }, []);
  const selectMonth = useCallback((next: string) => {
    setSelection((current) => ({ ...current, monthId: next }));
  }, []);

  const value = useMemo<ActiveScopeContextValue>(
    () => ({
      enabled,
      selection: { organizationId, engagementId, monthId },
      organization: organizations.find((item) => item.id === organizationId) ?? null,
      engagement: engagements.find((item) => item.id === engagementId) ?? null,
      month: months.find((item) => item.id === monthId) ?? null,
      organizations,
      engagements,
      months,
      permissions,
      loading:
        enabled &&
        (organizationsQuery.isPending ||
          (Boolean(organizationId) && engagementsQuery.isPending) ||
          (Boolean(engagementId) && monthsQuery.isPending)),
      error: enabled
        ? (organizationsQuery.error ?? engagementsQuery.error ?? monthsQuery.error ?? null)
        : null,
      selectOrganization,
      selectEngagement,
      selectMonth,
      can: (requirement) => hasPermission(permissions, requirement),
    }),
    [
      enabled,
      engagementId,
      engagements,
      engagementsQuery.error,
      engagementsQuery.isPending,
      monthId,
      months,
      monthsQuery.error,
      monthsQuery.isPending,
      organizationId,
      organizations,
      organizationsQuery.error,
      organizationsQuery.isPending,
      permissions,
      selectEngagement,
      selectMonth,
      selectOrganization,
    ],
  );

  return <ActiveScopeContext.Provider value={value}>{children}</ActiveScopeContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useActiveScope() {
  const context = useContext(ActiveScopeContext);
  if (!context) {
    throw new Error("useActiveScope must be used inside ActiveScopeProvider.");
  }
  return context;
}
