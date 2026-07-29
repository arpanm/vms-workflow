import { expect, test } from "@playwright/test";

import { mockCoreAdminApi } from "./fixtures/core-admin-api";

test("[E2E-F01-BC-010] active scope persists only while server authority remains valid", async ({
  page,
}) => {
  const api = await mockCoreAdminApi(page);
  await page.goto("/administration/engagements");

  await expect(page.getByRole("heading", { name: "Organizations & engagements" })).toBeVisible();
  await page.getByRole("combobox", { name: "Active organization" }).click();
  await page.getByRole("option", { name: "Reliance Intelligence" }).click();
  await expect(page.getByRole("combobox", { name: "Active engagement" })).toContainText(
    "Reliance Operations",
  );

  await page.reload();
  await expect(page.getByRole("combobox", { name: "Active organization" })).toContainText(
    "Reliance Intelligence",
  );

  api.setAuthority({ organizations: [organizationsForTest()[0]] });
  await page.reload();
  await expect(page.getByRole("combobox", { name: "Active organization" })).toContainText(
    "ArrowFoundry",
  );
  await expect(page.getByText("Reliance Operations")).toHaveCount(0);
});

test("[E2E-F01-BC-011] permission-derived navigation and deep links deny without loading admin details", async ({
  page,
}) => {
  const api = await mockCoreAdminApi(page, { permissions: ["catalog.read"] });
  await page.goto("/administration/contact-groups");

  await expect(page.getByRole("heading", { name: "Contact groups" })).toBeVisible();
  await expect(page.getByText("Permission denied")).toBeVisible();
  await expect(page.getByRole("link", { name: "Contact groups" })).toHaveCount(0);
  expect(
    api.requests.filter((request) =>
      request.path.includes("/core/engagements/eng-a/contact-groups"),
    ),
  ).toEqual([]);
});

test("[E2E-F01-BC-012] contact membership uses the displayed group version", async ({ page }) => {
  const api = await mockCoreAdminApi(page);
  await page.goto("/administration/contact-groups");

  await expect(page.getByText("Central Procurement")).toBeVisible();
  await page.getByLabel("Display name").fill("Procurement Reviewer");
  await page.getByLabel("Verified email").fill("procurement@example.invalid");
  await page.getByLabel("Identity/email verified").check();
  await page.getByRole("button", { name: "Add member" }).click();
  await expect(page.getByText("Procurement Reviewer")).toBeVisible();

  await expect
    .poll(
      () =>
        api.requests.find(
          (request) => request.method === "POST" && request.path.endsWith("/members"),
        )?.body,
    )
    .toMatchObject({
      email: "procurement@example.invalid",
      verified: true,
      expectedGroupVersion: 3,
    });
});

test("[E2E-F01-BC-013] stale month transition never overwrites and offers reload", async ({
  page,
}) => {
  const api = await mockCoreAdminApi(page, { staleTransition: true });
  await page.goto("/administration/months");

  await page.getByRole("combobox", { name: "Allowed transition" }).click();
  await page.getByRole("option", { name: "PLANNING" }).click();
  await page
    .getByLabel("Reason and impact declaration")
    .fill("Configuration verified and planning may begin.");
  await page.getByRole("button", { name: "Review transition" }).click();
  await page.getByRole("button", { name: "Confirm guarded transition" }).click();

  await expect(page.getByText("This record changed")).toBeVisible();
  await expect(page.getByRole("button", { name: "Reload current version" })).toBeVisible();
  expect(
    api.requests.find(
      (request) => request.method === "POST" && request.path.endsWith("/transitions"),
    )?.body,
  ).toMatchObject({
    targetState: "PLANNING",
    expectedVersion: 2,
  });
});

test("[E2E-F01-BC-014] configuration publish is prospective and version-bound", async ({
  page,
}) => {
  const api = await mockCoreAdminApi(page);
  await page.goto("/administration/engagements");

  await expect(page.getByText("Effective configuration history")).toBeVisible();
  await page.getByLabel("Effective from").fill("2026-09-01");
  await page.getByRole("button", { name: "Publish configuration version" }).click();

  await expect
    .poll(
      () =>
        api.requests.find(
          (request) => request.method === "POST" && request.path.endsWith("/configurations"),
        )?.body,
    )
    .toMatchObject({
      validFrom: "2026-09-01",
      timezone: "Asia/Kolkata",
      expectedEngagementVersion: 4,
      reopenPolicy: { reasonRequired: true, approvalRequired: true },
      notificationPolicy: { recipientSnapshotRequired: true },
    });
});

test("[E2E-F01-BC-015] approval request creation binds policy, object evidence and idempotency", async ({
  page,
}) => {
  const api = await mockCoreAdminApi(page);
  await page.goto("/administration/approval-requests");

  await page.getByRole("combobox", { name: "Published policy" }).click();
  await page.getByRole("option", { name: /Reopen approval/ }).click();
  await page.getByLabel("Reopen-requested month UUID").fill("22222222-2222-2222-2222-222222222222");
  await page.getByRole("button", { name: "Create approval request" }).click();

  const requestBody = await expect
    .poll(
      () =>
        api.requests.find(
          (request) =>
            request.method === "POST" && request.path.endsWith("/eng-a/approval-requests"),
        )?.body,
    )
    .toMatchObject({
      policyId: "policy-plan",
      objectId: "22222222-2222-2222-2222-222222222222",
    });
  void requestBody;
  const recorded = api.requests.find(
    (request) => request.method === "POST" && request.path.endsWith("/eng-a/approval-requests"),
  )?.body;
  expect(recorded?.idempotencyKey).toEqual(expect.any(String));
  expect(String(recorded?.idempotencyKey).length).toBeGreaterThan(8);
});

test("[E2E-F01-BC-016] delegated vote records attribution but does not fake quorum", async ({
  page,
}) => {
  const api = await mockCoreAdminApi(page);
  await page.goto("/administration/approval-requests/approval-request-1");

  await page.getByRole("combobox", { name: "Authority source" }).click();
  await page.getByRole("option", { name: /Delegated from Admin User/ }).click();
  await page.getByLabel("Reason").fill("Reviewed the exact version and evidence hash.");
  await page.getByRole("button", { name: "Submit exact-version decision" }).click();

  await expect(page.getByText("oidc|approver")).toBeVisible();
  await expect(page.getByLabel("Request status PENDING")).toBeVisible();
  await expect
    .poll(
      () =>
        api.requests.find(
          (request) => request.method === "POST" && request.path.endsWith("/actions"),
        )?.body,
    )
    .toMatchObject({
      decision: "APPROVED",
      delegationId: "delegation-approval",
      expectedRequestVersion: 0,
    });
  const action = api.requests.find(
    (request) => request.method === "POST" && request.path.endsWith("/actions"),
  )?.body;
  expect(action?.idempotencyKey).toEqual(expect.any(String));
});

test("[E2E-F01-BC-017] stale approval action refuses overwrite and reloads current request", async ({
  page,
}) => {
  await mockCoreAdminApi(page, { staleApprovalAction: true });
  await page.goto("/administration/approval-requests/approval-request-1");

  await page
    .getByLabel("Reason")
    .fill("Attempt against the exact version after concurrent action.");
  await page.getByRole("button", { name: "Submit exact-version decision" }).click();
  await expect(page.getByText("This record changed")).toBeVisible();
  await expect(page.getByRole("button", { name: "Reload current version" })).toBeVisible();
});

test("[E2E-F01-BC-018] denied approval deep link does not fetch request details", async ({
  page,
}) => {
  const api = await mockCoreAdminApi(page, { permissions: ["catalog.read"] });
  await page.goto("/administration/approval-requests/approval-request-1");

  await expect(page.getByText("Permission denied")).toBeVisible();
  expect(
    api.requests.filter((request) =>
      request.path.includes("/core/approval-requests/approval-request-1"),
    ),
  ).toEqual([]);
});

function organizationsForTest() {
  return [
    { id: "org-a", code: "ARROW", displayName: "ArrowFoundry" },
    {
      id: "org-b",
      code: "RELIANCE",
      displayName: "Reliance Intelligence",
    },
  ];
}
