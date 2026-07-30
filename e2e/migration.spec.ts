import { expect, test } from "@playwright/test";

import { migrationIds, mockMigrationApi } from "./fixtures/migration-api";
import { allowExpectedConsoleError } from "./fixtures/quality-gates";

test("[E2E-08] validates, reconciles, dual-approves and commits a staged historical batch", async ({
  page,
}) => {
  const api = await mockMigrationApi(page);
  await page.goto(`/migration?jobId=${migrationIds.job}`);

  await expect(page.getByRole("heading", { name: "Historical migration center" })).toBeVisible();
  await expect(page.getByRole("table", { name: "Historical migration templates" })).toBeVisible();
  await expect(page.getByText("employees-june.csv", { exact: true }).first()).toBeVisible();
  await page.getByRole("button", { name: "Validate staged rows" }).click();
  await expect(page.getByText("MIG-REF-EMPLOYEE-NOT-FOUND")).toBeVisible();
  await expect(page.getByText("Migration-lead approval is required.")).toBeVisible();

  await page.getByRole("button", { name: "Approve as migration lead" }).click();
  await expect(page.getByText("Distinct governance approval is required.")).toBeVisible();
  api.actAsGovernanceReviewer();
  await page.reload();
  await page.getByRole("button", { name: "Approve as governance reviewer" }).click();

  await page.getByLabel(/Reaffirm the immutable valid-rows-only commit policy/).check();
  await page.getByRole("button", { name: "Commit exact approved batch" }).click();
  await expect(page.getByText("COMPLETED WITH ERRORS").last()).toBeVisible();
  expect(api.requests.filter((request) => request.method === "POST").map((request) => request.path))
    .toEqual(expect.arrayContaining([
      `/api/v1/migrations/jobs/${migrationIds.job}/validate`,
      `/api/v1/migrations/jobs/${migrationIds.job}/approvals`,
      `/api/v1/migrations/jobs/${migrationIds.job}/commit`,
    ]));
  for (const request of api.requests.filter((item) => item.method === "POST")) {
    expect(request.headers["idempotency-key"]).toMatch(/^[0-9a-f-]{36}$/i);
  }
  for (const request of api.requests.filter((item) =>
    item.path.endsWith("/approvals"),
  )) {
    expect(request.body).toMatchObject({ decision: "APPROVED" });
  }
  expect(
    api.requests.find((request) => request.path.endsWith("/commit"))?.body,
  ).toMatchObject({ partialCommit: true });
});

test("[E2E-08A] upload records the immutable valid-rows-only commit policy", async ({
  page,
}) => {
  const api = await mockMigrationApi(page);
  await page.goto(`/migration?jobId=${migrationIds.job}`);
  await page.getByRole("combobox", { name: /^Template/ })
    .selectOption("01_employees");
  await page.getByLabel("CSV source file").setInputFiles({
    name: "employees.csv",
    mimeType: "text/csv",
    buffer: Buffer.from("template_version,organization_code\\n"),
  });
  await page.getByLabel("Organization ID").fill(migrationIds.organization);
  await page.getByLabel("Source description").fill("Governed historical employee register");
  await page.getByLabel(/Use valid-rows-only commit policy/).check();
  await page.getByRole("button", { name: "Upload for dry run" }).click();

  await expect.poll(() => api.requests.some((request) =>
    request.path.endsWith("/jobs") && request.method === "POST",
  )).toBe(true);
  await expect.poll(() => api.multipartMetadata()).toContainEqual({
      templateCode: "01_employees",
      organizationId: migrationIds.organization,
      engagementId: migrationIds.engagement,
      engagementMonthId: null,
      templateVersion: "1",
      mode: "DRY_RUN",
      partialCommit: true,
      sourceType: "APPROVED_SPREADSHEET",
      confidence: "HIGH",
      sourceDescription: "Governed historical employee register",
    });
  await expect.poll(() => api.multipartFileNames())
    .toContain("employees.csv");
});

test("[E2E-08B] creates a current-time retro request with explicit delegation evidence", async ({
  page,
}) => {
  const api = await mockMigrationApi(page);
  await page.goto(`/migration?jobId=${migrationIds.job}`);
  await page.getByLabel("Historical action").selectOption("CONFIRMATION");
  await page.getByLabel("Original approver unavailable").check();
  await page.getByLabel("Delegation / replacement authority reference").fill("delegation-2026-44");
  await page.getByRole("button", { name: "Create retro request now" }).click();
  await expect(page.getByText(/current authenticated timestamp/)).toBeVisible();
  const call = api.requests.find((request) =>
    request.method === "POST" && request.path.endsWith("/retro-requests"),
  );
  expect(call?.body).toMatchObject({
    requestType: "CONFIRMATION",
    originalActorUnavailable: true,
    delegationEvidenceReference: "delegation-2026-44",
  });
  await expect(page.getByText(/no backdate control/i)).toBeVisible();
});

test("[E2E-08C] cross-tenant migration scope is non-disclosing", async ({ page }) => {
  allowExpectedConsoleError(page, /status of 403/i);
  await mockMigrationApi(page, { denied: true });
  await page.goto(`/migration?jobId=${migrationIds.job}`);
  await expect(page.getByRole("alert").first()).toContainText(
    "Migration action could not be completed",
  );
  await expect(page.getByText("employees-june.csv", { exact: true })).toHaveCount(0);
});

test("[E2E-08D] failed migration exposes bounded retry without retrying commit", async ({
  page,
}) => {
  const api = await mockMigrationApi(page, { initialState: "FAILED" });
  await page.goto(`/migration?jobId=${migrationIds.job}`);
  await expect(
    page.getByRole("heading", { name: /employees-june\.csv failed/i }),
  ).toBeVisible();
  await page.getByRole("textbox", { name: "Reason", exact: true }).fill(
    "Retry the scanner and immutable validation pipeline only",
  );
  await page.getByRole("button", { name: "Retry safe recovery" }).click();
  await expect(
    page.getByRole("heading", { name: /employees-june\.csv uploaded/i }),
  ).toBeVisible();

  const retry = api.requests.find((request) =>
    request.path.endsWith("/retry"),
  );
  expect(retry?.body).toMatchObject({
    reason: "Retry the scanner and immutable validation pipeline only",
  });
  expect(api.requests.some((request) => request.path.endsWith("/commit")))
    .toBe(false);
});
