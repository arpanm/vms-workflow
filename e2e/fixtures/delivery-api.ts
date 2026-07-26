import type { Page, Route } from "@playwright/test";

type RecordedMutation = {
  method: string;
  path: string;
  body: Record<string, unknown>;
};

type RecordedRequest = {
  method: string;
  path: string;
  search: string;
};

type HealthScenario = "NOT_CONFIGURED" | "ACTION_REQUIRED";

const ids = {
  organization: "10000000-0000-0000-0000-000000000001",
  engagement: "20000000-0000-0000-0000-000000000001",
  month: "30000000-0000-0000-0000-000000000001",
  project: "40000000-0000-0000-0000-000000000001",
  employee: "50000000-0000-0000-0000-000000000001",
  draftPlan: "60000000-0000-0000-0000-000000000001",
  frozenPlan: "60000000-0000-0000-0000-000000000002",
  createdPlan: "60000000-0000-0000-0000-000000000003",
  revisionPlan: "60000000-0000-0000-0000-000000000004",
  deliverable: "70000000-0000-0000-0000-000000000001",
  deliverableVersion: "71000000-0000-0000-0000-000000000001",
  connection: "80000000-0000-0000-0000-000000000001",
  link: "90000000-0000-0000-0000-000000000001",
  inaccessibleLink: "90000000-0000-0000-0000-000000000002",
  issue: "a0000000-0000-0000-0000-000000000001",
  inaccessibleIssue: "a0000000-0000-0000-0000-000000000002",
};

const checksum = "sha256:5a71e9f03e79d6f9f03-exact-plan-v1";

const organization = {
  id: ids.organization,
  code: "ARROW",
  displayName: "ArrowFoundry",
};

const engagement = {
  id: ids.engagement,
  organizationId: ids.organization,
  name: "ArrowFoundry × Reliance",
};

const month = {
  id: ids.month,
  engagementId: ids.engagement,
  monthStartDate: "2026-08-01",
  state: "OPEN",
};

function issueLink(id = ids.link, issueUuid = ids.issue, identifier = "CAD-321") {
  return {
    id,
    deliverableVersionId: ids.deliverableVersion,
    connectionId: ids.connection,
    issueUuid,
    identifier,
    url: `https://linear.app/cadence/issue/${identifier}`,
    status: id === ids.inaccessibleLink ? "INACCESSIBLE" : "ACTIVE",
    rationale: null,
    currentNormalizedState: "COMPLETED",
    lastFetchedAt: "2026-08-26T09:00:00Z",
  };
}

function deliverable(linearLinks: ReturnType<typeof issueLink>[] = []) {
  return {
    id: ids.deliverable,
    deliverableVersionId: ids.deliverableVersion,
    deliverableCode: "DEL-001",
    title: "Immutable delivery evidence",
    description: "Expose attributable plan and execution evidence.",
    businessObjective: "Improve delivery governance without inferred acceptance.",
    projectId: ids.project,
    productOwnerSubject: "product.owner@reliance.example",
    vendorOwnerSubject: "delivery.owner@arrowfoundry.example",
    priority: "P1",
    targetCompletionDate: "2026-08-28",
    evidenceExpectations: "test report,release note",
    dependencyNoneDeclared: true,
    riskAndAssumptions: "None",
    deliveryCategory: "FEATURE",
    linkExceptionReason: "Fixture permits a draft before provider linking.",
    executionProjection: linearLinks.length ? "COMPLETED" : "UNSTARTED",
    criteria: [
      {
        id: "b0000000-0000-0000-0000-000000000001",
        sequence: 1,
        statement: "Exact immutable version is reviewable.",
        validationMethod: "Browser and API contract regression",
        expectedResult: "Checksum and evidence remain attributable.",
        mandatory: true,
      },
    ],
    dependencies: [],
    assignments: [
      {
        id: "c0000000-0000-0000-0000-000000000001",
        employeeId: ids.employee,
        effectiveFrom: "2026-08-01",
        effectiveTo: null,
        exceptionReason: null,
      },
    ],
    linearLinks,
  };
}

function plan(overrides: Record<string, unknown> = {}) {
  return {
    id: ids.draftPlan,
    engagementMonthId: ids.month,
    currentVersionId: ids.draftPlan,
    version: 1,
    state: "DRAFT",
    title: "August governed delivery plan",
    summary: "An exact, reviewable delivery commitment.",
    businessOutcomes: "Attributable execution evidence and explicit decisions.",
    coordinatorSubject: "coordinator@arrowfoundry.example",
    baselineType: "ON_TIME",
    checksum,
    priorVersionId: null,
    revisionReason: null,
    revisionImpact: null,
    createdBySubject: "planner@arrowfoundry.example",
    createdAt: "2026-08-01T08:00:00Z",
    submittedAt: null,
    frozenAt: null,
    completenessBlockers: [],
    recipients: {
      arrowFoundry: ["delivery.owner@arrowfoundry.example"],
      relianceStakeholders: ["product.owner@reliance.example"],
      procurementCc: ["procurement@reliance.example"],
    },
    deliverables: [deliverable()],
    approvals: [],
    baselineId: null,
    commitmentStatus: null,
    ...overrides,
  };
}

function frozenPlan() {
  return plan({
    id: ids.frozenPlan,
    currentVersionId: ids.frozenPlan,
    state: "FROZEN",
    submittedAt: "2026-08-25T08:00:00Z",
    frozenAt: "2026-08-25T09:00:00Z",
    baselineId: "d0000000-0000-0000-0000-000000000001",
    commitmentStatus: "SENT",
    deliverables: [
      deliverable([issueLink(), issueLink(ids.inaccessibleLink, ids.inaccessibleIssue, "CAD-404")]),
    ],
    approvals: [
      {
        id: "e0000000-0000-0000-0000-000000000001",
        approverSubject: "approver@reliance.example",
        decision: "APPROVE",
        signedChecksum: checksum,
        comment: "Reviewed exact checksum.",
        decidedAt: "2026-08-25T09:00:00Z",
      },
    ],
  });
}

function summary(value: ReturnType<typeof plan>) {
  return {
    id: value.id,
    engagementMonthId: value.engagementMonthId,
    currentVersionId: value.currentVersionId,
    version: value.version,
    state: value.state,
    title: value.title,
    baselineType: value.baselineType,
    checksum: value.checksum,
    deliverableCount: value.deliverables.length,
    approvedCount: value.approvals.length,
    requiredApprovals: 1,
    createdAt: value.createdAt,
    frozenAt: value.frozenAt,
  };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify(body),
  });
}

export async function mockDeliveryApi(
  page: Page,
  options: { healthScenario?: HealthScenario } = {},
) {
  const mutations: RecordedMutation[] = [];
  const requests: RecordedRequest[] = [];
  let draft = plan();
  const frozen = frozenPlan();
  let created = plan({
    id: ids.createdPlan,
    currentVersionId: ids.createdPlan,
    title: "September governed delivery plan",
  });
  let revision = plan({
    id: ids.revisionPlan,
    currentVersionId: ids.revisionPlan,
    version: 2,
    priorVersionId: ids.frozenPlan,
    revisionReason: "Scope changed after baseline approval.",
    revisionImpact: "Target date and evidence expectations require review.",
    title: frozen.title,
  });

  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();
    requests.push({ method, path, search: url.search });

    if (path === "/api/v1/me") {
      await json(route, {
        id: "f0000000-0000-0000-0000-000000000001",
        email: "planner@arrowfoundry.example",
        displayName: "Delivery Planner",
        organizationIds: [ids.organization],
        permissions: ["catalog.read", "delivery.read", "delivery.write"],
      });
      return;
    }
    if (path === "/api/v1/organizations") {
      await json(route, [organization]);
      return;
    }
    if (path === "/api/v1/engagements") {
      await json(route, [engagement]);
      return;
    }
    if (path === "/api/v1/engagement-months") {
      await json(route, [month]);
      return;
    }
    if (path === "/api/v1/delivery/plans" && method === "GET") {
      await json(route, [summary(frozen), summary(draft)]);
      return;
    }
    if (path === "/api/v1/delivery/plans" && method === "POST") {
      const body = request.postDataJSON() as Record<string, unknown>;
      mutations.push({ method, path, body });
      created = plan({
        id: ids.createdPlan,
        currentVersionId: ids.createdPlan,
        title: body.title,
        summary: body.summary,
        businessOutcomes: body.businessOutcomes,
        coordinatorSubject: body.coordinatorSubject,
        baselineType: body.baselineType,
        recipients: body.recipients,
        deliverables: (body.deliverables as Record<string, unknown>[])?.map(
          (item, deliverableIndex) => ({
            ...deliverable(),
            ...item,
            id: `70000000-0000-0000-0000-${String(deliverableIndex + 2).padStart(12, "0")}`,
            deliverableVersionId: `71000000-0000-0000-0000-${String(deliverableIndex + 2).padStart(12, "0")}`,
            criteria: (item.criteria as Record<string, unknown>[]).map(
              (criterion, criterionIndex) => ({
                ...criterion,
                id: `b0000000-0000-0000-0000-${String(criterionIndex + 2).padStart(12, "0")}`,
                sequence: criterionIndex + 1,
              }),
            ),
            assignments: (item.assignments as Record<string, unknown>[]).map(
              (assignment, assignmentIndex) => ({
                ...assignment,
                id: `c0000000-0000-0000-0000-${String(assignmentIndex + 2).padStart(12, "0")}`,
              }),
            ),
            dependencies: [],
            linearLinks: [],
            executionProjection: "UNSTARTED",
          }),
        ),
      });
      await json(route, created, 201);
      return;
    }
    if (path === `/api/v1/delivery/plans/${ids.draftPlan}`) {
      await json(route, draft);
      return;
    }
    if (path === `/api/v1/delivery/plans/${ids.frozenPlan}`) {
      await json(route, frozen);
      return;
    }
    if (path === `/api/v1/delivery/plans/${ids.createdPlan}`) {
      await json(route, created);
      return;
    }
    if (path === `/api/v1/delivery/plans/${ids.revisionPlan}`) {
      await json(route, revision);
      return;
    }
    if (path === `/api/v1/delivery/plans/${ids.draftPlan}/submit` && method === "POST") {
      mutations.push({ method, path, body: {} });
      draft = {
        ...draft,
        state: "PENDING_APPROVAL",
        submittedAt: "2026-08-25T08:00:00Z",
      };
      await json(route, draft);
      return;
    }
    if (path === `/api/v1/delivery/plans/${ids.draftPlan}/approvals` && method === "POST") {
      const body = request.postDataJSON() as Record<string, unknown>;
      mutations.push({ method, path, body });
      draft = {
        ...draft,
        state: "FROZEN",
        frozenAt: "2026-08-25T09:00:00Z",
        baselineId: "d0000000-0000-0000-0000-000000000002",
        commitmentStatus: "PENDING",
        approvals: [
          {
            id: "e0000000-0000-0000-0000-000000000002",
            approverSubject: "approver@reliance.example",
            decision: body.decision,
            signedChecksum: checksum,
            comment: body.comment ?? null,
            decidedAt: "2026-08-25T09:00:00Z",
          },
        ],
      };
      await json(route, draft);
      return;
    }
    if (path === `/api/v1/delivery/plans/${ids.frozenPlan}/revisions` && method === "POST") {
      const body = request.postDataJSON() as Record<string, unknown>;
      mutations.push({ method, path, body });
      revision = {
        ...revision,
        revisionReason: body.reason,
        revisionImpact: body.impact,
      };
      await json(route, revision, 201);
      return;
    }
    if (path === "/api/v1/integrations/linear/links" && method === "POST") {
      const body = request.postDataJSON() as Record<string, unknown>;
      mutations.push({ method, path, body });
      const linked = issueLink();
      draft = {
        ...draft,
        deliverables: [deliverable([linked])],
      };
      await json(route, linked, 201);
      return;
    }
    if (path === `/api/v1/integrations/linear/links/${ids.link}/current`) {
      await json(route, {
        issueUuid: ids.issue,
        identifier: "CAD-321",
        url: "https://linear.app/cadence/issue/CAD-321",
        title: "Record governed delivery evidence",
        providerStateId: "linear-state-done",
        providerStateName: "Done",
        providerStateType: "completed",
        providerStateCategory: "Completed",
        normalizedState: "COMPLETED",
        updatedAt: "2026-08-26T08:55:00Z",
        fetchedAt: "2026-08-26T09:00:00Z",
        payloadHash: "sha256:linear-current-cad-321",
        stale: true,
        inaccessible: false,
        executionProjection: "COMPLETED",
      });
      return;
    }
    if (path === `/api/v1/integrations/linear/links/${ids.link}/snapshots`) {
      await json(route, [
        {
          id: "11000000-0000-0000-0000-000000000001",
          snapshotType: "PLAN_TIME",
          status: "CAPTURED",
          normalizedState: "STARTED",
          providerStateId: "linear-state-started",
          providerStateName: "In Progress",
          providerStateType: "started",
          providerStateCategory: "Started",
          fetchedAt: "2026-08-01T08:00:00Z",
          payloadHash: "sha256:linear-plan-time-cad-321",
          confidence: "SOURCE_EVENT_HISTORY",
          failureReason: null,
        },
      ]);
      return;
    }
    if (path === `/api/v1/integrations/linear/links/${ids.inaccessibleLink}/current`) {
      await json(route, {
        issueUuid: ids.inaccessibleIssue,
        identifier: "CAD-404",
        url: "https://linear.app/cadence/issue/CAD-404",
        title: "Restricted provider issue",
        providerStateId: "linear-state-unknown",
        providerStateName: "Last known state",
        providerStateType: "unknown",
        providerStateCategory: null,
        normalizedState: "UNKNOWN",
        updatedAt: "2026-08-20T08:55:00Z",
        fetchedAt: "2026-08-20T09:00:00Z",
        payloadHash: "sha256:linear-last-known-cad-404",
        stale: true,
        inaccessible: true,
        executionProjection: "UNKNOWN",
      });
      return;
    }
    if (path === `/api/v1/integrations/linear/links/${ids.inaccessibleLink}/snapshots`) {
      await json(route, [
        {
          id: "11000000-0000-0000-0000-000000000002",
          snapshotType: "PLAN_TIME",
          status: "FETCH_FAILED",
          normalizedState: null,
          providerStateId: null,
          providerStateName: null,
          providerStateType: null,
          providerStateCategory: null,
          fetchedAt: null,
          payloadHash: null,
          confidence: "UNAVAILABLE",
          failureReason: "Provider access revoked; retained metadata only.",
        },
      ]);
      return;
    }
    if (path === "/api/v1/integrations/linear/health" && method === "GET") {
      const actionRequired = options.healthScenario === "ACTION_REQUIRED";
      await json(route, {
        connectionId: actionRequired ? ids.connection : null,
        status: actionRequired ? "ACTION_REQUIRED" : "NOT_CONFIGURED",
        providerRegistrationStatus: actionRequired ? "CONFIGURED" : "EXTERNALLY_BLOCKED",
        lastVerifiedDeliveryAt: actionRequired ? "2026-08-26T08:00:00Z" : null,
        lastReconciledAt: actionRequired ? "2026-08-26T08:15:00Z" : null,
        linkedIssueCount: actionRequired ? 4 : 0,
        staleIssueCount: actionRequired ? 2 : 0,
        queuedCount: actionRequired ? 3 : 0,
        deadLetterCount: actionRequired ? 1 : 0,
        lastError: actionRequired
          ? "WEBHOOK_REAUTH_REQUIRED; reference LIN-204."
          : "PROVIDER_NOT_CONFIGURED",
      });
      return;
    }

    await json(route, { message: `No delivery E2E fixture for ${path}` }, 404);
  });

  return { mutations, requests };
}

export const deliveryFixture = {
  ids,
  checksum,
};
