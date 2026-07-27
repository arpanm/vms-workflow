import { expect, test } from "@playwright/test";

import { mockLegacyApi } from "./fixtures/api";
import { mockFinanceApi } from "./fixtures/finance-api";
import "./fixtures/quality-gates";

test.beforeEach(async ({ page }) => {
  await mockLegacyApi(page);
  await mockFinanceApi(page);
});

test("[E2E-F00-001] demo shell identifies its safety boundary and exposes enabled routes", async ({
  page,
}) => {
  await page.goto("/");

  await expect(
    page.getByText("Demo mode: persona switching changes presentation only"),
  ).toBeVisible();
  await expect(page.getByRole("link", { name: "Dashboard" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Requirements" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Approvals" })).toBeVisible();
  await expect(page.getByText("Viewing as")).toBeVisible();
});

test("[E2E-F00-002] unknown routes fail safely and retain a dashboard recovery path", async ({
  page,
}) => {
  await page.goto("/not-a-real-feature");

  await expect(page.getByRole("heading", { name: "404" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Back to dashboard" })).toHaveAttribute(
    "href",
    "/",
  );
});

test("[E2E-F01-001] legacy requirements are searchable and strictly read-only", async ({
  page,
}) => {
  const mutationRequests: string[] = [];
  page.on("request", (request) => {
    if (!["GET", "HEAD", "OPTIONS"].includes(request.method())) {
      mutationRequests.push(`${request.method()} ${request.url()}`);
    }
  });

  await page.goto("/requirements");
  await expect(
    page.getByRole("heading", { name: "Legacy Requirements" }),
  ).toBeVisible();
  await expect(page.getByText("cannot create or change requirements")).toBeVisible();
  await expect(page.getByText("Create evidence dashboard")).toBeVisible();

  await page.getByPlaceholder("Search requirements…").fill("legacy signoff");
  await expect(page.getByText("Resolve legacy signoff")).toBeVisible();
  await expect(page.getByText("Create evidence dashboard")).toBeHidden();
  await expect(
    page.getByRole("button", { name: /create|edit|delete|approve|reject/i }),
  ).toHaveCount(0);
  expect(mutationRequests).toEqual([]);
});

test("[E2E-F01-002] unsafe legacy approval values require explicit human review", async ({
  page,
}) => {
  await page.goto("/approvals");

  await expect(page.getByRole("heading", { name: "Approvals" })).toBeVisible();
  await expect(page.getByText("Read-only legacy record")).toBeVisible();
  await expect(
    page.getByText("Legacy status: explicit review required"),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: /approve|reject|sign off/i }),
  ).toHaveCount(0);
});

test("[E2E-F01-003] unverified UAT states remain in the explicit-review queue", async ({
  page,
}) => {
  await page.goto("/uat");

  await expect(
    page.getByRole("heading", { name: "UAT Management" }),
  ).toBeVisible();
  await expect(page.getByText("Resolve legacy signoff")).toBeVisible();
  await expect(
    page.getByText("Legacy status: explicit review required"),
  ).toBeVisible();
});

test("[E2E-F01-004] every enabled core screen renders its fixture-backed primary content", async ({
  page,
}) => {
  const screens = [
    {
      path: "/",
      heading: "Executive Dashboard",
      evidence: "ArrowFoundry × Reliance",
    },
    {
      path: "/engagements",
      heading: "Engagements",
      evidence: "ArrowFoundry × Reliance",
    },
    {
      path: "/scope",
      heading: "Monthly Scope Engine",
      evidence: "Auto-planned scope",
    },
    {
      path: "/invoices",
      heading: "Finance evidence workspace",
      evidence: "AF-2026-071",
    },
  ];

  for (const screen of screens) {
    await page.goto(screen.path);
    await expect(
      page.getByRole("heading", { name: screen.heading }).first(),
    ).toBeVisible();
    await expect(page.getByText(screen.evidence).first()).toBeVisible();
  }
});

test("[E2E-F01-005] browser accessibility smoke exposes landmarks, titles, headings and named controls", async ({
  page,
}) => {
  await page.goto("/requirements");

  await expect(page).toHaveTitle(/Requirements — Cadence/);
  await expect(page.getByRole("main")).toHaveCount(1);
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(
    "Legacy Requirements",
  );
  await expect(page.getByRole("link", { name: "Dashboard" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Toggle Sidebar" })).toBeVisible();
  await expect(page.getByPlaceholder("Search requirements…")).toHaveJSProperty(
    "type",
    "text",
  );
  await expect(page.locator("img:not([alt])")).toHaveCount(0);
});
