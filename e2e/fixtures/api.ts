import type { Page, Route } from "@playwright/test";

const engagement = {
  id: "eng-reliance",
  name: "ArrowFoundry × Reliance",
  vendor: "ArrowFoundry",
  category: "app_development",
  monthly_capacity_hours: 160,
  approver: "Priya Approver",
  business_owner: "Ravi Owner",
  color: "#334155",
};

const requirements = [
  {
    id: "req-safe",
    engagement_id: engagement.id,
    title: "Create evidence dashboard",
    description: "Show attributable delivery evidence.",
    module: "Evidence",
    priority: "p1",
    rank: 1,
    status: "in_development",
    story_points: 8,
    estimated_hours: 40,
    business_owner: "Ravi Owner",
    acceptance_criteria: "Evidence is attributable and immutable.",
    uat_cases: "Business owner verifies source links.",
    business_justification: "Improve month-close governance.",
    carry_forward: false,
    target_month: "2026-06-01",
    created_at: "2026-05-01T09:00:00Z",
  },
  {
    id: "req-review",
    engagement_id: engagement.id,
    title: "Resolve legacy signoff",
    description: "Migrate an unverified legacy decision.",
    module: "Governance",
    priority: "p2",
    rank: 2,
    status: "uat",
    story_points: 5,
    estimated_hours: 24,
    business_owner: "Ravi Owner",
    acceptance_criteria: "A human decision is recorded.",
    uat_cases: "Approver explicitly accepts or rejects.",
    business_justification: "Do not infer approval from silence.",
    carry_forward: false,
    target_month: "2026-06-01",
    created_at: "2026-05-02T09:00:00Z",
  },
];

const apiResponses: Record<string, unknown> = {
  "/api/v1/me": {
    id: "demo-user",
    email: "demo@example.invalid",
    displayName: "Demo User",
    organizationIds: ["org-arrowfoundry"],
    permissions: ["catalog.read"],
  },
  "/api/v1/organizations": [
    {
      id: "org-reliance",
      code: "RELIANCE",
      displayName: "Reliance Intelligence",
    },
  ],
  "/api/v1/engagements": [
    {
      id: "eng-reliance",
      engagementCode: "AF-RIL",
      name: "ArrowFoundry × Reliance",
      clientOrganizationId: "org-reliance",
      vendorOrganizationId: "org-arrowfoundry",
      procurementOrganizationId: null,
      engagementModel: "DEDICATED_RESOURCE_MONTHLY",
      startDate: "2026-06-01",
      endDate: null,
      status: "ACTIVE",
    },
  ],
  "/api/v1/engagement-months": [
    {
      id: "month-reliance-june",
      engagementId: "eng-reliance",
      monthStartDate: "2026-06-01",
      state: "ACTIVE",
      riskStatus: "ON_TRACK",
      historicalFlag: false,
      governanceVersion: 1,
    },
  ],
  "/api/v1/legacy/engagements": [engagement],
  "/api/v1/legacy/requirements": requirements,
  "/api/v1/legacy/approvals": [
    {
      id: "approval-pending",
      requirement_id: "req-safe",
      approver: "Priya Approver",
      status: "pending",
      requested_at: "2026-07-20T09:00:00Z",
      acted_at: null,
      sla_hours: 24,
      notes: "",
    },
    {
      id: "approval-deemed",
      requirement_id: "req-review",
      approver: "Legacy migration",
      status: "deemed_approved",
      requested_at: "2026-05-10T09:00:00Z",
      acted_at: "2026-05-12T09:00:00Z",
      sla_hours: 24,
      notes: "Legacy value; no attributable human decision.",
    },
  ],
  "/api/v1/legacy/uat-items": [
    {
      id: "uat-progress",
      requirement_id: "req-safe",
      status: "in_progress",
      uat_owner: "Uma Tester",
      handover_date: "2026-07-20",
      signoff_date: null,
      defects_open: 1,
    },
    {
      id: "uat-deemed",
      requirement_id: "req-review",
      status: "deemed_signed_off",
      uat_owner: "",
      handover_date: null,
      signoff_date: null,
      defects_open: 0,
    },
  ],
  "/api/v1/legacy/invoices": [
    {
      id: "invoice-1",
      engagement_id: engagement.id,
      invoice_number: "INV-2026-006",
      amount: 250000,
      currency: "INR",
      status: "finance_approved",
      uploaded_at: "2026-07-01T09:00:00Z",
      tech_approved_at: "2026-07-02T09:00:00Z",
      finance_approved_at: "2026-07-03T09:00:00Z",
      paid_at: null,
      period_month: "2026-06-01",
    },
  ],
};

export async function mockLegacyApi(page: Page) {
  await page.route("**/api/v1/**", async (route: Route) => {
    const path = new URL(route.request().url()).pathname;
    if (!(path in apiResponses)) {
      await route.fulfill({
        status: 404,
        contentType: "application/json",
        body: JSON.stringify({ message: `No E2E fixture for ${path}` }),
      });
      return;
    }

    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(apiResponses[path]),
    });
  });
}

export async function mockUnauthenticatedSession(page: Page) {
  await page.route("**/api/v1/me", (route) =>
    route.fulfill({
      status: 401,
      contentType: "application/json",
      body: JSON.stringify({ message: "Authentication required." }),
    }),
  );
}
