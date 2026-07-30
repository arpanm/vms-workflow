import { expect, test } from "@playwright/test";

import { mockCoreAdminApi } from "./fixtures/core-admin-api";

test("[E2E-US-001] collaborative task workspace creates, filters, comments and updates delivery", async ({
  page,
}) => {
  const api = await mockCoreAdminApi(page);
  await page.goto("/work-items");

  await expect(page.getByRole("heading", { name: "Client work items" })).toBeVisible();
  await expect(page.getByText("CLIENT_TASK_001 · Client collaboration workspace")).toBeVisible();
  await expect(page.getByText("Estimate: 8h")).toBeVisible();
  await expect(page.getByRole("link", { name: "Product requirement" })).toHaveAttribute(
    "href",
    "https://docs.example.test/prd",
  );

  await page.getByRole("button", { name: "Add task" }).click();
  await page.getByLabel("Title", { exact: true }).fill("Next month governed task");
  await page.getByLabel("Description", { exact: true }).fill("Deliver the complete new client workflow");
  await page.getByLabel("Workflow", { exact: true }).fill("Design, implement, test and approve");
  await page.getByLabel("Acceptance criteria", { exact: true }).fill("All automated cases pass");
  await page.getByRole("button", { name: "Create task" }).click();
  await expect(page.getByText(/Next month governed task/)).toBeVisible();

  await page.getByLabel("Edit task title").first().fill("Updated collaboration workspace");
  await page.getByRole("button", { name: "Save task changes" }).first().click();
  await expect(page.getByText(/Updated collaboration workspace/)).toBeVisible();

  await page.getByPlaceholder("Comment").first().fill("Please review this delivery");
  await page.getByRole("button", { name: "Post comment" }).first().click();
  await expect(
    page.getByRole("paragraph").filter({ hasText: "Please review this delivery" }),
  ).toBeVisible();

  await page.getByRole("combobox", { name: "Delivery status" }).first().selectOption(
    "DELIVERED",
  );
  await page.getByLabel("Delivery summary").first().fill("All acceptance passed");
  await page.getByRole("button", { name: "Update status" }).first().click();
  await expect(page.getByText("DELIVERED").first()).toBeVisible();

  await page.getByRole("button", { name: "Bulk upload" }).click();
  await page.getByLabel("Task objects").fill(
    '[{"workItemCode":"BULK_UI_001","title":"Uploaded client task","description":"Created on behalf of client"}]',
  );
  await page.getByRole("button", { name: "Upload tasks atomically" }).click();
  await expect(page.getByText(/Uploaded client task/)).toBeVisible();

  expect(
    api.requests.some(
      (request) =>
        request.method === "POST" &&
        request.path === "/api/v1/collaboration/work-items",
    ),
  ).toBe(true);
  expect(
    api.requests.some((request) => request.path.endsWith("/comments")),
  ).toBe(true);
  expect(
    api.requests.some(
      (request) =>
        request.method === "POST" &&
        request.path === "/api/v1/collaboration/work-items/bulk",
    ),
  ).toBe(true);
});

test("[E2E-US-002] client onboarding creates scope then adds multiple role-bound users", async ({
  page,
}) => {
  await mockCoreAdminApi(page);
  let clientCreated = false;
  let userCount = 0;
  let roleGrantCount = 0;
  await page.route("**/api/v1/collaboration/clients**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() === "POST" && path === "/api/v1/collaboration/clients") {
      clientCreated = true;
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify({
          organizationId: "client-new",
          clientCode: "ACME",
          legalName: "Acme Limited",
          displayName: "Acme",
          status: "ACTIVE",
          engagementId: "eng-new",
          engagementCode: "ACME_AF",
          projectId: "project-new",
          projectCode: "ACME_CORE",
          provisionedMonthCount: 13,
        }),
      });
      return;
    }
    if (request.method() === "POST" && path.endsWith("/users")) {
      userCount += 1;
      const body = request.postDataJSON();
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify({
          userProfileId: `client-user-${userCount}`,
          organizationId: "client-new",
          identitySubject: body.identitySubject,
          email: body.email,
          displayName: body.displayName,
          status: "ACTIVE",
          roleCodes: body.roleCodes,
          permissions: ["workitem.read", "workitem.create"],
        }),
      });
      return;
    }
    if (request.method() === "POST" && path.endsWith("/role-grants")) {
      roleGrantCount += 1;
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify({
          userProfileId: "client-user-1",
          organizationId: "client-new",
          identitySubject: "acme-owner",
          email: "owner@acme.example",
          displayName: "Acme Owner",
          status: "ACTIVE",
          roleCodes: ["CLIENT_PRODUCT_OWNER", "CLIENT_APPROVER"],
          permissions: ["workitem.read", "workitem.create", "workitem.delivery.approve.l2"],
        }),
      });
      return;
    }
    await route.fallback();
  });

  await page.goto("/administration/clients");
  await page.getByLabel("Client code").fill("ACME");
  await page.locator("#client-display-name").fill("Acme");
  await page.getByLabel("Legal name").fill("Acme Limited");
  await page.getByLabel("Primary domain").fill("acme.example");
  await page.getByLabel("Engagement code").fill("ACME_AF");
  await page.getByLabel("Engagement name").fill("Acme / ArrowFoundry");
  await page.getByLabel("Project code").fill("ACME_CORE");
  await page.getByLabel("Project name").fill("Acme Core");
  await page.getByLabel("Start date").fill("2026-08-01");
  await page.getByRole("button", { name: "Onboard client" }).click();
  await expect(page.getByText(/13 months ready/)).toBeVisible();
  expect(clientCreated).toBe(true);

  await page.getByLabel("Identity subject").fill("acme-owner");
  await page.getByLabel("Email").fill("owner@acme.example");
  await page.locator("#client-user-display-name").fill("Acme Owner");
  await page.getByLabel("Role and action permissions").selectOption([
    "CLIENT_PRODUCT_OWNER",
    "CLIENT_APPROVER",
  ]);
  await page.getByRole("button", { name: "Add user" }).click();
  await expect(page.getByText(/owner@acme.example/)).toBeVisible();
  expect(userCount).toBe(1);

  await page.getByLabel("Permission scope for Acme Owner").selectOption("PROJECT");
  await page.getByRole("button", { name: "Grant" }).click();
  await expect(page.getByText(/3 effective permissions/)).toBeVisible();
  expect(roleGrantCount).toBe(1);
});
