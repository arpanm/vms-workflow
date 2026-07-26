import { expect, test, type Locator, type Page } from "@playwright/test";

import { deliveryFixture, mockDeliveryApi } from "./fixtures/delivery-api";
import "./fixtures/quality-gates";

function field(page: Page, label: string): Locator {
  return page
    .locator("label", { hasText: label })
    .filter({ hasText: new RegExp(`^${label.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}$`) })
    .locator("..")
    .locator("input, textarea");
}

async function completePlanForm(page: Page) {
  await field(page, "Plan title").fill("September governed delivery plan");
  await field(page, "Coordinator subject").fill("coordinator@arrowfoundry.example");
  await field(page, "Summary").fill("A complete exact-version plan.");
  await field(page, "Business outcomes").fill("Explicit approval and attributable evidence.");
  await field(page, "Approver subjects (comma-separated)").fill("approver@reliance.example");
  await field(page, "ArrowFoundry recipients").fill("delivery.owner@arrowfoundry.example");
  await field(page, "Reliance product stakeholders").fill("product.owner@reliance.example");
  await field(page, "Central Procurement CC").fill("procurement@reliance.example");
  await field(page, "Deliverable code").fill("DEL-SEP-001");
  await field(page, "Title").fill("September release evidence");
  await field(page, "Description").fill("Publish exact, attributable delivery evidence.");
  await field(page, "Business objective").fill("Support explicit customer review.");
  await field(page, "Project ID").fill(deliveryFixture.ids.project);
  await field(page, "Reliance product-owner subject").fill("product.owner@reliance.example");
  await field(page, "ArrowFoundry owner subject").fill("delivery.owner@arrowfoundry.example");
  await field(page, "Target completion date").fill("2026-09-25");
  await field(page, "Evidence expectations (comma-separated)").fill("test report, release note");
  await field(page, "Assigned employee ID").fill(deliveryFixture.ids.employee);
  await field(page, "Assignment effective from").fill("2026-09-01");
  await field(page, "Risks and assumptions (enter None when applicable)").fill("None");
  await field(page, "Acceptance criterion").fill("The evidence package matches the exact plan.");
  await field(page, "Criterion validation method").fill("Browser and API contract regression");
  await field(page, "Criterion expected result").fill("Checksum and content match.");
  await page.getByRole("checkbox", { name: "Explicitly declare no dependencies" }).check();
}

test("[E2E-F03-001] plan list opens a complete new-plan creation with the exact request contract", async ({
  page,
}) => {
  const api = await mockDeliveryApi(page);
  await page.goto("/delivery/plans");

  await expect(page.getByRole("heading", { name: "Monthly delivery plans" })).toBeVisible();
  await expect(
    page.getByRole("link", { name: "August governed delivery plan" }).first(),
  ).toBeVisible();
  await expect(page.getByText("Version 1 · ON_TIME").first()).toBeVisible();
  await page.getByRole("link", { name: "Create plan" }).click();

  await expect(page.getByRole("heading", { name: "Create monthly plan" })).toBeVisible();
  await completePlanForm(page);
  await page.getByRole("button", { name: "Create draft" }).click();

  await expect(page).toHaveURL(new RegExp(`/delivery/plans/${deliveryFixture.ids.createdPlan}$`));
  await expect(
    page.getByRole("heading", { name: "September governed delivery plan" }),
  ).toBeVisible();
  await expect
    .poll(() =>
      api.mutations.find(
        (mutation) => mutation.path === "/api/v1/delivery/plans" && mutation.method === "POST",
      ),
    )
    .toMatchObject({
      body: {
        engagementMonthId: deliveryFixture.ids.month,
        title: "September governed delivery plan",
        baselineType: "ON_TIME",
        quorumMode: "ANY_ONE",
        quorumRequired: 1,
        approverSubjects: ["approver@reliance.example"],
        recipients: {
          arrowFoundry: ["delivery.owner@arrowfoundry.example"],
          relianceStakeholders: ["product.owner@reliance.example"],
          procurementCc: ["procurement@reliance.example"],
        },
        deliverables: [
          {
            deliverableCode: "DEL-SEP-001",
            dependencyNoneDeclared: true,
            dependencies: [],
            evidenceExpectations: "test report, release note",
            assignments: [
              {
                employeeId: deliveryFixture.ids.employee,
                effectiveFrom: "2026-09-01",
              },
            ],
          },
        ],
      },
    });
});

test("[E2E-F03-002] incomplete plan shows blockers and performs no mutation", async ({ page }) => {
  const api = await mockDeliveryApi(page);
  await page.goto("/delivery/plans/new");
  await page.getByRole("button", { name: "Create draft" }).click();

  await expect(page.getByText("Complete the required plan fields")).toBeVisible();
  const errors = page.getByLabel("Plan validation errors");
  await expect(errors).toContainText("Plan title is required.");
  await expect(errors).toContainText("Add at least one approver subject.");
  await expect(errors).toContainText(
    "Every dependency needs a type, description, owner and target resolution date.",
  );
  expect(api.mutations.filter((mutation) => mutation.path === "/api/v1/delivery/plans")).toEqual(
    [],
  );
});

test("[E2E-F03-003] exact checksum submission and approval freeze preserve the recipient boundary", async ({
  page,
}) => {
  const api = await mockDeliveryApi(page);
  await page.goto(`/delivery/plans/${deliveryFixture.ids.draftPlan}`);

  await expect(page.getByText(deliveryFixture.checksum).first()).toBeVisible();
  await expect(
    page.getByText("delivery.owner@arrowfoundry.example", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText(
      "Sending, delivery, reading or silence never constitutes approval or confirmation.",
    ),
  ).toBeVisible();
  await page.getByRole("button", { name: "Submit exact version" }).click();
  await expect(page.getByRole("button", { name: "Approve checksum" })).toBeVisible();

  await page.getByLabel("Decision comment").fill("Reviewed exact immutable checksum.");
  await page.getByRole("button", { name: "Approve checksum" }).click();

  await expect(page.getByText("This version is immutable.")).toBeVisible();
  await expect(page.getByLabel("Decision comment")).toBeDisabled();
  await expect(
    page.getByText("approver@reliance.example · Reviewed exact immutable checksum.", {
      exact: true,
    }),
  ).toBeVisible();
  expect(api.mutations.map(({ path, body }) => ({ path, body }))).toEqual([
    {
      path: `/api/v1/delivery/plans/${deliveryFixture.ids.draftPlan}/submit`,
      body: {},
    },
    {
      path: `/api/v1/delivery/plans/${deliveryFixture.ids.draftPlan}/approvals`,
      body: {
        decision: "APPROVE",
        comment: "Reviewed exact immutable checksum.",
      },
    },
  ]);
});

test("[E2E-F03-004] frozen plans are non-editable and create reasoned revision lineage", async ({
  page,
}) => {
  const api = await mockDeliveryApi(page);
  await page.goto(`/delivery/plans/${deliveryFixture.ids.frozenPlan}`);

  await expect(page.getByText("This version is immutable.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Attach resolved Linear issue" })).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: /submit exact|approve checksum|reject/i }),
  ).toHaveCount(0);
  await field(page, "Revision reason").fill("Scope changed after baseline approval.");
  await field(page, "Impact on scope, dates, owners and evidence").fill(
    "Target date and evidence expectations require review.",
  );
  await page.getByRole("button", { name: "Clone into revision" }).click();

  await expect(page).toHaveURL(new RegExp(`/delivery/plans/${deliveryFixture.ids.revisionPlan}$`));
  await expect(page.getByText("Revision lineage", { exact: true })).toBeVisible();
  await expect(page.getByText(deliveryFixture.ids.frozenPlan)).toBeVisible();
  await expect(page.getByText("Scope changed after baseline approval.")).toBeVisible();
  await expect
    .poll(() => api.mutations.find((mutation) => mutation.path.endsWith("/revisions")))
    .toMatchObject({
      body: {
        reason: "Scope changed after baseline approval.",
        impact: "Target date and evidence expectations require review.",
      },
    });
});

test("[E2E-F03-005] resolved Linear linking records the exact provider-neutral request", async ({
  page,
}) => {
  const api = await mockDeliveryApi(page);
  await page.goto(`/delivery/plans/${deliveryFixture.ids.draftPlan}`);
  await page.getByRole("button", { name: "Attach resolved Linear issue" }).click();
  await field(page, "Connection ID").fill(deliveryFixture.ids.connection);
  await field(page, "Issue UUID").fill(deliveryFixture.ids.issue);
  await page.getByRole("button", { name: "Attach issue", exact: true }).click();

  await expect(page.getByRole("link", { name: /CAD-321/ })).toBeVisible();
  await expect
    .poll(
      () =>
        api.mutations.find((mutation) => mutation.path === "/api/v1/integrations/linear/links")
          ?.body,
    )
    .toEqual({
      deliverableVersionId: deliveryFixture.ids.deliverableVersion,
      connectionId: deliveryFixture.ids.connection,
      issueUuid: deliveryFixture.ids.issue,
    });
});

test("[E2E-F03-006] current, plan-time and inaccessible stale Linear evidence stay distinct", async ({
  page,
}) => {
  await mockDeliveryApi(page);
  await page.goto(`/delivery/plans/${deliveryFixture.ids.frozenPlan}`);

  const available = page
    .getByRole("link", { name: /CAD-321/ })
    .locator(
      "xpath=ancestor::div[contains(concat(' ', normalize-space(@class), ' '), ' rounded-xl ')][1]",
    );
  await expect(available).toContainText("Provider state: Done");
  await expect(available).toContainText("Normalized: Completed");
  await expect(available).toContainText("stale");
  await expect(available).toContainText("Plan snapshot");
  await expect(available).toContainText("In Progress");
  await expect(available).toContainText("Current");
  await expect(available).toContainText("Done");

  const unavailable = page
    .getByRole("link", { name: /CAD-404/ })
    .locator(
      "xpath=ancestor::div[contains(concat(' ', normalize-space(@class), ' '), ' rounded-xl ')][1]",
    );
  await expect(unavailable).toContainText("inaccessible");
  await expect(unavailable).toContainText(
    "Last-known evidence remains visible and submission may be blocked.",
  );
  await expect(unavailable).toContainText("Fetch failed");
  await expect(unavailable).toContainText("Last known state");
});

test("[E2E-F03-007] health renders not-configured and action-required states without secrets, polling or replay", async ({
  page,
}) => {
  const notConfigured = await mockDeliveryApi(page);
  await page.goto("/delivery/integration-health");

  await expect(page.getByRole("heading", { name: "Linear integration health" })).toBeVisible();
  await expect(page.getByText("Provider is not configured")).toBeVisible();
  await expect(page.getByText(/Provider registration is externally blocked/)).toBeVisible();
  await expect(page.getByText("PROVIDER_NOT_CONFIGURED")).toBeVisible();
  await expect(page.getByText("0 / 0")).toBeVisible();
  await page.waitForTimeout(350);
  expect(
    notConfigured.requests.filter(
      (request) => request.path === "/api/v1/integrations/linear/health",
    ),
  ).toHaveLength(1);
  await expect(page.getByRole("button", { name: /poll|refresh|replay|process/i })).toHaveCount(0);

  await page.unrouteAll({ behavior: "wait" });
  const actionRequired = await mockDeliveryApi(page, {
    healthScenario: "ACTION_REQUIRED",
  });
  await page.reload();
  await expect(page.getByText("Provider is action required")).toBeVisible();
  await expect(page.getByText(/Provider registration is configured/)).toBeVisible();
  await expect(page.getByText(/WEBHOOK_REAUTH_REQUIRED/)).toBeVisible();
  await expect(page.getByText("3 / 1")).toBeVisible();
  await expect(page.getByText("Last sanitized error", { exact: true })).toBeVisible();
  await expect(page.getByText(/reference LIN-204/)).toBeVisible();
  await expect(page.getByText(/super-secret|access-token-value/i)).toHaveCount(0);
  await page.waitForTimeout(350);
  expect(
    actionRequired.requests.filter(
      (request) => request.path === "/api/v1/integrations/linear/health",
    ),
  ).toHaveLength(1);
  await expect(page.getByRole("button", { name: /poll|refresh|replay|process/i })).toHaveCount(0);
});

test("[E2E-XF-001] completed Linear execution and commitment delivery never imply acceptance or certification", async ({
  page,
}) => {
  await mockDeliveryApi(page);
  await page.goto(`/delivery/plans/${deliveryFixture.ids.frozenPlan}`);

  await expect(page.getByText("completed").first()).toBeVisible();
  await expect(
    page.getByText(
      "Linear Done is execution evidence only. Acceptance and certification require a separate authorized decision.",
    ),
  ).toBeVisible();
  await expect(
    page.getByText(
      "A sent or read message never constitutes approval, acceptance or confirmation.",
    ),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: /accept|certif|confirm/i })).toHaveCount(0);
});
