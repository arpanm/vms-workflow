import type { Page, Route } from "@playwright/test";

type RequestRecord = {
  method: string;
  path: string;
  search: string;
  body?: Record<string, unknown>;
};

const organizations = [
  { id: "org-a", code: "ARROW", displayName: "ArrowFoundry" },
  { id: "org-b", code: "RELIANCE", displayName: "Reliance Intelligence" },
];

const engagements = {
  "org-a": [
    {
      id: "eng-a",
      engagementCode: "AF-RIL",
      name: "ArrowFoundry × Reliance",
      clientOrganizationId: "org-b",
      vendorOrganizationId: "org-a",
      procurementOrganizationId: null,
      engagementModel: "DEDICATED_RESOURCE_MONTHLY",
      startDate: "2026-06-01",
      endDate: null,
      status: "ACTIVE",
    },
  ],
  "org-b": [
    {
      id: "eng-b",
      engagementCode: "RIL-OPS",
      name: "Reliance Operations",
      clientOrganizationId: "org-b",
      vendorOrganizationId: "org-a",
      procurementOrganizationId: null,
      engagementModel: "HYBRID",
      startDate: "2026-07-01",
      endDate: null,
      status: "ACTIVE",
    },
  ],
} as const;

const months = {
  "eng-a": [
    {
      id: "month-a",
      engagementId: "eng-a",
      monthStartDate: "2026-07-01",
      state: "DRAFT",
      riskStatus: "ON_TRACK",
      historicalFlag: false,
      governanceVersion: 2,
    },
  ],
  "eng-b": [
    {
      id: "month-b",
      engagementId: "eng-b",
      monthStartDate: "2026-08-01",
      state: "PLAN_APPROVED",
      riskStatus: "AT_RISK",
      historicalFlag: false,
      governanceVersion: 5,
    },
  ],
} as const;

const allPermissions = [
  "catalog.read",
  "engagement.read",
  "engagement.update",
  "engagement.configure",
  "contacts.manage",
  "approval.policy.manage",
  "approval.request.create",
  "approval.request.act",
  "delegation.manage",
  "month.transition",
];

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: status >= 400 ? "application/problem+json" : "application/json",
    body: JSON.stringify(body),
    headers: { "x-correlation-id": "f01-browser-correlation" },
  });
}

export async function mockCoreAdminApi(
  page: Page,
  options: {
    permissions?: string[];
    staleTransition?: boolean;
    staleApprovalAction?: boolean;
  } = {},
) {
  const requests: RequestRecord[] = [];
  let activeOrganizations = [...organizations];
  let permissions = options.permissions ?? allPermissions;
  let transitionIsStale = options.staleTransition ?? false;
  let approvalActionIsStale = options.staleApprovalAction ?? false;
  let contactGroup = {
    id: "group-procurement",
    engagementId: "eng-a",
    projectId: null,
    code: "PROC_CC",
    name: "Central Procurement",
    groupType: "PROCUREMENT_CC",
    status: "ACTIVE",
    version: 3,
    members: [] as Array<Record<string, unknown>>,
  };
  const policy = {
    id: "policy-plan",
    engagementId: "eng-a",
    projectId: null,
    code: "REOPEN_APPROVAL",
    name: "Reopen approval",
    actionType: "REOPEN",
    status: "ACTIVE",
    version: 4,
    policyVersionId: "policy-version-1",
    policyVersion: 1,
    versionStatus: "PUBLISHED",
    validFrom: "2026-07-01",
    validTo: null,
    prohibitSelfApproval: true,
    evidenceRequired: true,
    rules: {},
    stages: [
      {
        id: "stage-1",
        stageOrder: 1,
        name: "Client approval",
        roleCode: "CLIENT_APPROVER",
        contactGroupId: null,
        explicitAssigneeId: null,
        quorumMode: "N_OF_M",
        quorumRequired: 2,
        allowDelegation: true,
        dueDurationHours: 24,
      },
    ],
  };
  let approvalRequest = {
    id: "approval-request-1",
    policyId: policy.id,
    policyVersionId: policy.policyVersionId,
    engagementId: "eng-a",
    projectId: null,
    objectType: "ENGAGEMENT_MONTH",
    objectId: "11111111-1111-1111-1111-111111111111",
    objectVersion: 7,
    objectHash: "a".repeat(64),
    requiredPermissionCode: "month.transition",
    currentStageOrder: 1,
    status: "PENDING",
    version: 0,
    requestedBySubject: "oidc|admin",
    requestedAt: "2026-07-29T08:00:00Z",
    evidenceRequired: true,
    stages: policy.stages,
    actions: [] as Array<Record<string, unknown>>,
  };

  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method();
    const body = request.postData()
      ? (request.postDataJSON() as Record<string, unknown>)
      : undefined;
    requests.push({ method, path: url.pathname, search: url.search, body });

    if (url.pathname === "/api/v1/me") {
      await json(route, {
        id: "admin-user",
        subject: "oidc|admin",
        email: "admin@example.invalid",
        displayName: "Admin User",
        memberships: activeOrganizations.map((organization) => ({
          organizationId: organization.id,
          organizationCode: organization.code,
          organizationName: organization.displayName,
          roleCode: "ENGAGEMENT_ADMIN",
          validFrom: "2026-01-01",
          validTo: null,
        })),
        organizationIds: activeOrganizations.map((organization) => organization.id),
        permissions,
      });
      return;
    }
    if (url.pathname === "/api/v1/organizations") {
      await json(route, activeOrganizations);
      return;
    }
    if (url.pathname === "/api/v1/engagements") {
      const organizationId = url.searchParams.get("organizationId") ?? "org-a";
      await json(
        route,
        engagements[organizationId as keyof typeof engagements] ?? [],
      );
      return;
    }
    if (url.pathname === "/api/v1/engagement-months") {
      const engagementId = url.searchParams.get("engagementId") ?? "eng-a";
      await json(route, months[engagementId as keyof typeof months] ?? []);
      return;
    }
    if (/\/api\/v1\/core\/engagements\/[^/]+$/.test(url.pathname)) {
      const engagementId = url.pathname.split("/").at(-1) ?? "eng-a";
      const source =
        Object.values(engagements)
          .flat()
          .find((item) => item.id === engagementId) ?? engagements["org-a"][0];
      await json(route, {
        id: source.id,
        engagementCode: source.engagementCode,
        name: method === "PATCH" ? body?.name : source.name,
        status: method === "PATCH" ? body?.status : source.status,
        defaultProjectId: null,
        configurationVersionId: "config-v1",
        version: method === "PATCH" ? 5 : 4,
      });
      return;
    }
    if (url.pathname.endsWith("/configurations")) {
      const configuration = {
        id: "config-v1",
        engagementId: "eng-a",
        version: method === "POST" ? 2 : 1,
        status: "PUBLISHED",
        validFrom: method === "POST" ? body?.validFrom : "2026-06-01",
        validTo: null,
        timezone: method === "POST" ? body?.timezone : "Asia/Kolkata",
        planningDueDay: method === "POST" ? body?.planningDueDay : 25,
        certificationDueDay: method === "POST" ? body?.certificationDueDay : 5,
        confirmationDueDay: method === "POST" ? body?.confirmationDueDay : 7,
        reopenPolicy: { reasonRequired: true, approvalRequired: true },
        notificationPolicy: { recipientSnapshotRequired: true },
        publishedAt: "2026-07-29T09:00:00Z",
      };
      await json(route, method === "POST" ? configuration : [configuration]);
      return;
    }
    if (url.pathname.endsWith("/contact-groups")) {
      if (method === "POST") {
        contactGroup = {
          ...contactGroup,
          id: "group-created",
          code: String(body?.code),
          name: String(body?.name),
          groupType: String(body?.groupType),
          version: 0,
        };
        await json(route, contactGroup, 201);
        return;
      }
      await json(route, [contactGroup]);
      return;
    }
    if (url.pathname.endsWith("/members") && method === "POST") {
      contactGroup = {
        ...contactGroup,
        version: contactGroup.version + 1,
        members: [
          {
            id: "contact-member-1",
            userProfileId: null,
            email: body?.email,
            displayName: body?.displayName,
            roleAttribution: body?.roleAttribution,
            verified: body?.verified,
            validFrom: body?.validFrom,
            validTo: null,
            status: "ACTIVE",
          },
        ],
      };
      await json(route, contactGroup, 201);
      return;
    }
    if (url.pathname.endsWith("/approval-policies")) {
      await json(route, [policy]);
      return;
    }
    if (
      url.pathname.endsWith("/approval-requests") &&
      url.pathname.includes("/engagements/")
    ) {
      if (method === "POST") {
        approvalRequest = {
          ...approvalRequest,
          id: "approval-request-created",
          policyId: String(body?.policyId),
          objectType: "ENGAGEMENT_MONTH",
          objectId: String(body?.objectId),
          objectVersion: 7,
          objectHash: "a".repeat(64),
          requestedAt: "2026-07-29T10:00:00Z",
          version: 0,
          actions: [],
        };
        await json(route, approvalRequest, 201);
        return;
      }
      await json(route, [approvalRequest]);
      return;
    }
    if (
      /\/api\/v1\/core\/approval-requests\/[^/]+\/actions$/.test(url.pathname)
    ) {
      if (approvalActionIsStale) {
        await json(
          route,
          {
            title: "Conflict",
            detail: "The approval request changed before the action.",
            code: "APPROVAL_REQUEST_VERSION_CONFLICT",
            correlationId: "stale-approval-f01",
          },
          409,
        );
        return;
      }
      approvalRequest = {
        ...approvalRequest,
        version: approvalRequest.version + 1,
        status: "PENDING",
        actions: [
          ...approvalRequest.actions,
          {
            id: `action-${approvalRequest.actions.length + 1}`,
            stageOrder: 1,
            decision: body?.decision,
            actorUserId: "user-approver",
            actorSubject: "oidc|approver",
            authoritySnapshot: { roleCode: "CLIENT_APPROVER" },
            delegatedFromUserId: body?.delegationId ? "user-admin" : null,
            delegationId: body?.delegationId ?? null,
            source: "IN_APP",
            reason: body?.reason ?? null,
            actedAt: "2026-07-29T10:05:00Z",
          },
        ],
      };
      await json(route, approvalRequest);
      return;
    }
    if (/\/api\/v1\/core\/approval-requests\/[^/]+$/.test(url.pathname)) {
      await json(route, approvalRequest);
      return;
    }
    if (url.pathname.endsWith("/delegations")) {
      await json(route, [
        {
          id: "delegation-approval",
          organizationId: "org-a",
          engagementId: "eng-a",
          projectId: null,
          delegatorUserId: "user-admin",
          delegatorName: "Admin User",
          delegateUserId: "user-approver",
          delegateName: "Client Approver",
          actionCodes: ["month.transition"],
          validFrom: "2026-07-01T00:00:00Z",
          validTo: "2026-12-31T00:00:00Z",
          status: "ACTIVE",
          reason: "Planned coverage",
          version: 0,
        },
      ]);
      return;
    }
    if (url.pathname.endsWith("/eligible-users")) {
      await json(route, [
        {
          id: "user-admin",
          organizationId: "org-a",
          displayName: "Admin User",
          email: "admin@example.invalid",
          activeRoleCodes: ["ENGAGEMENT_ADMIN"],
        },
        {
          id: "user-approver",
          organizationId: "org-a",
          displayName: "Client Approver",
          email: "approver@example.invalid",
          activeRoleCodes: ["CLIENT_APPROVER"],
        },
      ]);
      return;
    }
    if (url.pathname.endsWith("/transitions")) {
      if (method === "POST") {
        if (transitionIsStale) {
          await json(
            route,
            {
              title: "Conflict",
              detail: "The engagement month changed before the transition.",
              code: "MONTH_VERSION_CONFLICT",
              correlationId: "stale-f01",
            },
            409,
          );
          return;
        }
        await json(route, {
          id: "transition-new",
          engagementMonthId: "month-a",
          fromState: "DRAFT",
          toState: body?.targetState,
          fromVersion: body?.expectedVersion,
          toVersion: Number(body?.expectedVersion) + 1,
          actorSubject: "oidc|admin",
          reason: body?.reason,
          correlationId: "00000000-0000-0000-0000-000000000001",
          transitionedAt: "2026-07-29T10:00:00Z",
        });
        return;
      }
      await json(route, []);
      return;
    }
    await json(route, { title: "Not found", detail: "Fixture route missing." }, 404);
  });

  return {
    requests,
    setAuthority(next: { organizations?: typeof organizations; permissions?: string[] }) {
      if (next.organizations) activeOrganizations = [...next.organizations];
      if (next.permissions) permissions = next.permissions;
    },
    setTransitionStale(value: boolean) {
      transitionIsStale = value;
    },
    setApprovalActionStale(value: boolean) {
      approvalActionIsStale = value;
    },
  };
}
