import { expect, test, type Page } from "@playwright/test";

import {
  financeFixture,
  mockFinanceApi,
  type FinanceApiOptions,
} from "./fixtures/finance-api";
import { allowExpectedConsoleError } from "./fixtures/quality-gates";

const ids = financeFixture.ids;

function mutations(api: Awaited<ReturnType<typeof mockFinanceApi>>) {
  return api.requests.filter(
    (request) => request.method !== "GET" && request.path.startsWith("/api/v1/finance/"),
  );
}

async function openFinance(page: Page, options: FinanceApiOptions = {}, search = "") {
  const api = await mockFinanceApi(page, options);
  await page.goto(`/finance${search}`);
  return api;
}

test("[E2E-F05-FIN-001] dashboard, scoped queues and opaque cursor navigation", async ({
  page,
}) => {
  const api = await openFinance(page);

  await expect(
    page.getByRole("heading", { name: "Finance evidence workspace" }),
  ).toBeVisible();
  await expect(page.getByText("Invoices in scope")).toBeVisible();
  await expect(page.getByText("Procurement review required")).toBeVisible();
  const monthTable = page.getByRole("table", {
    name: "Authorized finance month readiness",
  });
  await expect(monthTable.getByText("July 2026", { exact: true })).toBeVisible();

  await page
    .getByRole("navigation", { name: "Finance months pages" })
    .getByRole("button", { name: "Next" })
    .click();
  await expect(monthTable.getByText("June 2026", { exact: true })).toBeVisible();
  expect(
    api.requests.some(
      (request) =>
        request.path === "/api/v1/finance/months" &&
        request.search === "?cursor=second-month-page",
    ),
  ).toBe(true);

  await page
    .getByRole("navigation", { name: "Finance months pages" })
    .getByRole("button", { name: "Previous" })
    .click();
  await expect(monthTable.getByText("July 2026", { exact: true })).toBeVisible();
});

test("[E2E-F05-FIN-002] document upload, exact readiness and submission preserve version headers", async ({
  page,
}) => {
  const api = await openFinance(
    page,
    { withoutDocument: true },
    `?monthId=${ids.month}&invoiceId=${ids.invoice}`,
  );

  await expect(page.getByRole("heading", { name: "Invoice AF-2026-071" })).toBeVisible();
  await expect(page.getByLabel("Retention policy"))
    .toHaveValue("FINANCE_EVIDENCE");
  await expect(page.getByText("Controlled by f05-policy-v1.")).toBeVisible();
  await page.getByLabel("Invoice file").setInputFiles({
    name: "replacement-invoice.pdf",
    mimeType: "application/pdf",
    buffer: Buffer.from("%PDF-1.7 governed invoice evidence"),
  });
  await page.getByLabel("Reason", { exact: true }).fill("Corrected source document received");
  await page
    .getByLabel(/I confirm this action creates a new immutable document version/)
    .check();
  await page.getByRole("button", { name: "Upload for scan" }).click();
  await expect(page.getByText("replacement-invoice.pdf")).toBeVisible();

  await page.getByRole("button", { name: "Evaluate exact v5" }).click();
  await expect(page.getByText(/Run readiness-run-v5 · policy/)).toBeVisible();

  await page.getByLabel("Submission reason").fill("Exact evidence set reviewed");
  await page
    .getByLabel(/I reviewed the exact source, invoice, package and readiness versions/)
    .check();
  await page.getByRole("button", { name: "Submit to Procurement" }).click();
  await expect(page.getByText("submitted to procurement", { exact: false })).toBeVisible();

  const calls = mutations(api);
  expect(calls.map((request) => request.path)).toEqual(
    expect.arrayContaining([
      `/api/v1/finance/invoices/${ids.invoice}/documents`,
      `/api/v1/finance/invoices/${ids.invoice}/readiness-runs`,
      `/api/v1/finance/invoices/${ids.invoice}/submit`,
    ]),
  );
  for (const request of calls) {
    expect(request.headers["idempotency-key"]).toMatch(/^[0-9a-f-]{36}$/i);
  }
  expect(calls.find((request) => request.path.endsWith("/documents"))?.headers)
    .toMatchObject({ "if-match": '"4"' });
  expect(calls.find((request) => request.path.endsWith("/readiness-runs"))?.headers)
    .toMatchObject({ "if-match": '"5"' });
});

test("[E2E-F05-FIN-003] package generation, immutable manifest, share and revoke are auditable", async ({
  page,
}) => {
  const api = await openFinance(
    page,
    {},
    `?monthId=${ids.month}&packageId=${ids.package}`,
  );

  await page.getByLabel("Generation reason").fill("Month-close evidence refresh");
  await page.getByLabel(/I reviewed month server v8 and readiness run/).check();
  await page.getByRole("button", { name: "Queue package generation" }).click();
  await expect(page.getByRole("heading", { name: "Immutable manifest items" })).toBeVisible();
  await expect(page.getByText("invoice-object-v4")).toBeVisible();

  await page.getByLabel("Authenticated recipient subject").fill("buyer@reliance.example");
  await page.getByLabel("Access scope").selectOption("DOWNLOAD");
  await page.getByLabel("Expires at").fill("2026-08-28T18:30");
  await page.getByLabel("Business reason").fill("Time-bound audit review");
  await page.getByLabel(/I confirm the recipient identity/).check();
  await page.getByRole("button", { name: "Create controlled share" }).click();
  await expect(page.getByText(/buyer@reliance\.example/)).toBeVisible();

  const existingShare = page.getByRole("listitem").filter({
    hasText: "auditor@reliance.example",
  });
  await existingShare.getByLabel("Revocation reason for auditor@reliance.example")
    .fill("Review window closed");
  await existingShare.getByLabel("Confirm revoke").check();
  await existingShare.getByRole("button", { name: "Revoke share" }).click();
  await expect(existingShare.getByText(/Revoked/)).toBeVisible();

  const calls = mutations(api);
  expect(calls.find((request) => request.path.endsWith("/packages"))?.headers)
    .toMatchObject({ "if-match": '"8"' });
  expect(
    calls.find(
      (request) =>
        request.path === `/api/v1/finance/packages/${ids.package}/shares`,
    )?.body,
  ).toMatchObject({
    recipientSubject: "buyer@reliance.example",
    accessScope: "DOWNLOAD",
    reason: "Time-bound audit review",
  });
  expect(calls.some((request) => request.path.endsWith(`/${ids.share}/revoke`))).toBe(true);
});

test("[E2E-F05-FIN-004] Procurement review, assigned query and payment update append history", async ({
  page,
}) => {
  const api = await mockFinanceApi(page);
  await page.goto(`/finance/procurement?invoiceId=${ids.invoice}`);

  await expect(page.getByRole("heading", { name: "Review AF-2026-071" })).toBeVisible();

  const review = page.getByRole("heading", { name: "Record Procurement decision" })
    .locator("xpath=../..");
  await review.getByLabel(/I confirm this immutable decision/).check();
  await review.getByRole("button", { name: "Record decision" }).click();
  await expect(page.getByText("Procurement Reviewer · Reliance Procurement")).toBeVisible();

  const query = page.getByRole("heading", { name: "Create assigned query" })
    .locator("xpath=../..");
  await query.getByLabel("Category").fill("DOCUMENT_CLARIFICATION");
  await query.getByLabel("Responsible owner ID").fill("vendor.owner@arrowfoundry.example");
  await query.getByLabel("Due at").fill("2026-08-05T17:00");
  await query.getByLabel("Requested change").fill("Confirm the PO reference on the source PDF.");
  await query.getByLabel("Reason").fill("Exact source clarification required");
  await query.getByLabel(/I understand source correction is assigned/).check();
  await query.getByRole("button", { name: "Create query" }).click();
  await expect(page.getByText("Confirm the PO reference on the source PDF.")).toBeVisible();

  const payment = page.getByRole("heading", { name: "Append payment status" })
    .locator("xpath=../..");
  await payment.getByLabel("Status", { exact: true }).selectOption("PAYMENT_SCHEDULED");
  await payment.getByLabel("Status timestamp").fill("2026-07-28T12:00");
  await payment.getByLabel("Expected date").fill("2026-08-10");
  await payment.getByLabel("AP / ERP reference").fill("AP-10002");
  await payment.getByLabel("Sanitized comment").fill("Scheduled by AP after review.");
  await payment.getByLabel(/I confirm this appends status history only/).check();
  await payment.getByRole("button", { name: "Append status" }).click();
  await expect(page.getByText("Scheduled by AP after review.")).toBeVisible();

  expect(
    mutations(api).map((request) => request.path),
  ).toEqual(
    expect.arrayContaining([
      `/api/v1/finance/procurement/invoices/${ids.invoice}/reviews`,
      `/api/v1/finance/procurement/invoices/${ids.invoice}/queries`,
      `/api/v1/finance/invoices/${ids.invoice}/payments`,
    ]),
  );
});

test("[E2E-F05-FIN-004B] exception request denies self-approval and accepts a distinct authenticated reviewer", async ({
  page,
}) => {
  allowExpectedConsoleError(page, /status of 409.*second-approval/i);
  const api = await mockFinanceApi(page, { blockedExceptionRule: true });
  await page.goto(`/finance/procurement?invoiceId=${ids.invoice}`);

  const request = page
    .getByRole("heading", { name: "Request authority-bound exception" })
    .locator("xpath=../..");
  await request.getByLabel("Exact failed rule").selectOption(ids.rule);
  await request
    .getByLabel("Rationale")
    .fill("Temporary exact-rule exception pending source correction.");
  await request.getByLabel("Valid until").fill("2026-08-10T18:30");
  await request
    .getByLabel(/I confirm this request remains disclosed/)
    .check();
  await request.getByRole("button", { name: "Request exception" }).click();

  const disclosure = page.locator("article").filter({
    hasText: "Temporary exact-rule exception pending source correction.",
  });
  await expect(disclosure.getByText("PENDING SECOND APPROVAL")).toBeVisible();
  await expect(
    disclosure.getByText(/uses the current authenticated Procurement actor/),
  ).toBeVisible();

  await disclosure
    .getByLabel(/I am a distinct authorized reviewer/)
    .check();
  await disclosure
    .getByRole("button", { name: "Approve as current signed-in reviewer" })
    .click();
  await expect(
    disclosure.getByText(/requester cannot approve their own request/i),
  ).toBeVisible();

  api.actAsDistinctProcurementApprover();
  await disclosure
    .getByRole("button", { name: "Approve as current signed-in reviewer" })
    .click();
  await expect(disclosure.getByText("accepted", { exact: true })).toBeVisible();
  await expect(
    disclosure.getByText(/Approved by Distinct Procurement Approver/),
  ).toBeVisible();

  const calls = mutations(api);
  const exceptionRequest = calls.find(
    (candidate) =>
      candidate.path ===
      `/api/v1/finance/procurement/invoices/${ids.invoice}/exceptions`,
  );
  const approvals = calls.filter(
    (candidate) =>
      candidate.path ===
      `/api/v1/finance/procurement/exceptions/${ids.exception}/second-approval`,
  );
  expect(exceptionRequest?.body).toEqual({
    expectedVersion: 4,
    ruleId: ids.rule,
    readinessRunId: ids.readiness,
    packageId: ids.package,
    packageVersion: 2,
    rationale: "Temporary exact-rule exception pending source correction.",
    validUntil: "2026-08-10T13:00:00.000Z",
  });
  expect(exceptionRequest?.body).not.toHaveProperty("secondApproverId");
  expect(exceptionRequest?.headers["if-match"]).toBe('"4"');
  expect(approvals).toHaveLength(2);
  for (const approval of approvals) {
    expect(approval.headers["if-match"]).toBe('"5"');
    expect(approval.headers["idempotency-key"]).toMatch(/^[0-9a-f-]{36}$/i);
    expect(approval.body).toEqual({
      expectedVersion: 5,
      invoiceId: ids.invoice,
      ruleId: ids.rule,
      readinessRunId: ids.readiness,
      packageId: ids.package,
      packageVersion: 2,
      policyVersionId: ids.policy,
      policyVersion: 2,
    });
    expect(approval.body).not.toHaveProperty("secondApproverId");
  }
  expect(approvals[0].headers["idempotency-key"]).not.toBe(
    approvals[1].headers["idempotency-key"],
  );
});

test("[E2E-F05-FIN-005] report export uses exact definition, filters and idempotency", async ({
  page,
}) => {
  const api = await mockFinanceApi(page);
  await page.goto("/finance/reports");

  await expect(
    page.getByRole("heading", { name: "Finance dashboards, reports and exports" }),
  ).toBeVisible();
  await page.getByLabel("Report and version").selectOption("INVOICE_READINESS");
  await page.getByLabel("Format").selectOption("JSON");
  await page.getByLabel("Data mode").selectOption("SNAPSHOT");
  await page.getByLabel("Month filter").fill("2026-07");
  await page.getByLabel("Engagement ID filter").fill("eng-reliance");
  await page.getByLabel("Export reason").fill("Audited July month-close report");
  await page.getByLabel(/I confirm the exact filters/).check();
  await page.getByRole("button", { name: "Queue private export" }).click();

  await expect(page.getByText("snapshot · {\"month\":\"2026-07\"")).toBeVisible();
  const exportRequest = mutations(api).find(
    (request) => request.path === "/api/v1/finance/exports",
  );
  expect(exportRequest?.headers["idempotency-key"]).toMatch(/^[0-9a-f-]{36}$/i);
  expect(exportRequest?.body).toMatchObject({
    reportId: "INVOICE_READINESS",
    reportVersion: "v1",
    format: "JSON",
    temporalMode: "SNAPSHOT",
    filters: { month: "2026-07", engagementId: "eng-reliance" },
    reason: "Audited July month-close report",
  });
});

test("[E2E-F05-FIN-006] RBAC, read-only and quarantine states remain fail-closed", async ({
  page,
}) => {
  await openFinance(
    page,
    { restrictPayment: true },
    `?invoiceId=${ids.invoice}`,
  );
  await expect(page.getByRole("heading", { name: "Payment status timeline" })).toBeVisible();
  await expect(page.getByText(/did not grant payment-view authority/)).toBeVisible();

  const readOnlyPage = await page.context().newPage();
  await mockFinanceApi(readOnlyPage, { readOnly: true });
  await readOnlyPage.goto(`/finance?invoiceId=${ids.invoice}`);
  await expect(readOnlyPage.getByRole("button", { name: /Evaluate exact/ })).toBeDisabled();
  await expect(readOnlyPage.getByRole("button", { name: "Create replacement version" }))
    .toBeDisabled();

  const quarantinePage = await page.context().newPage();
  await mockFinanceApi(quarantinePage, { quarantined: true });
  await quarantinePage.goto(`/finance?invoiceId=${ids.invoice}&packageId=${ids.package}`);
  await expect(quarantinePage.getByText(/current document is quarantined/)).toBeVisible();
  await expect(quarantinePage.getByRole("button", { name: "Create replacement version" }))
    .toBeEnabled();
  await expect(quarantinePage.getByRole("button", { name: "Download" })).toBeDisabled();
});
