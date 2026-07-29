import { expect, test, type APIRequestContext, type APIResponse } from "@playwright/test";
import { execFileSync } from "node:child_process";

import "./fixtures/quality-gates";

const monthId = "00000000-0000-0000-0000-000000000602";
const engagementId = "00000000-0000-0000-0000-000000000401";
const f04ReadinessRunId = "e2000000-0000-0000-0000-000000000001";
const browserTokenKey = "__vms_system_e2e_access_token";

const tokens = {
  vendor: requiredEnvironment("VMS_E2E_TOKEN_USER_ARROW"),
  procurement: requiredEnvironment("VMS_E2E_TOKEN_USER_PROCUREMENT"),
  finance: requiredEnvironment("VMS_E2E_TOKEN_USER_FINANCE_AP"),
  governance: requiredEnvironment("VMS_E2E_TOKEN_USER_GOVERNANCE"),
  outsider: requiredEnvironment("VMS_E2E_TOKEN_USER_NORTHSTAR"),
};

let invoiceId = "";
let invoiceVersion = 0;
let packageId = "";
let packageArtifactId = "";
let invoiceReadinessRunId = "";
let invoiceRuleId = "";
let paymentExportId = "";
let auditExportId = "";

test.describe.configure({ mode: "serial" });

test("[E2E-F05-SYS-001] browser and vendor flow use real Spring, Flyway and PostgreSQL", async ({
  page,
  request,
}) => {
  await page.addInitScript(
    ({ key, token }) => window.sessionStorage.setItem(key, token),
    { key: browserTokenKey, token: tokens.vendor },
  );
  await page.goto("/finance");
  await expect(
    page.getByRole("heading", { name: "Finance evidence workspace" }),
  ).toBeVisible();
  const seededMonthRow = page.getByRole("row").filter({
    has: page.getByText("Northstar / ArrowFoundry Synthetic Engagement", {
      exact: true,
    }),
  });
  await expect(seededMonthRow).toContainText("2026-07-01");

  const created = await json(
    await request.post("/api/v1/finance/invoices", {
      headers: mutationHeaders(tokens.vendor, "system-invoice-create"),
      data: {
        monthId,
        documentKind: "PRIMARY",
        relatedInvoiceId: null,
        representedMetadata: {
          invoiceNumber: "SYSTEM E2E F05 001",
          invoiceDate: "2026-07-31",
          billingPeriodStart: "2026-07-01",
          billingPeriodEnd: "2026-07-31",
          currency: "INR",
          taxableValue: "100.00",
          taxValue: "18.00",
          totalValue: "118.00",
          purchaseOrderReference: "PO-SYSTEM-E2E",
          workOrderReference: "WO-SYSTEM-E2E",
        },
      },
    }),
    201,
  );
  invoiceId = String(created.invoiceId);
  invoiceVersion = Number(created.version);

  const metadata = JSON.stringify({
    expectedVersion: invoiceVersion,
    classification: "CONFIDENTIAL",
    retentionPolicy: "FINANCE_EVIDENCE",
    source: "VENDOR_UPLOAD",
    reason: "Real-system browser regression invoice",
  });
  const uploaded = await json(
    await request.post(`/api/v1/finance/invoices/${invoiceId}/documents`, {
      headers: {
        ...authorization(tokens.vendor),
        "If-Match": String(invoiceVersion),
        "Idempotency-Key": "system-invoice-upload",
      },
      multipart: {
        file: {
          name: "system-invoice.pdf",
          mimeType: "application/pdf",
          buffer: Buffer.from("%PDF-1.7\nreal system invoice evidence\n%%EOF"),
        },
        metadata: {
          name: "metadata.json",
          mimeType: "application/json",
          buffer: Buffer.from(metadata),
        },
      },
    }),
    200,
  );
  invoiceVersion = Number(uploaded.version);
  expect(uploaded.currentDocument.scanStatus).toBe("PASSED");

  const generatedPackage = await json(
    await request.post(`/api/v1/finance/months/${monthId}/packages`, {
      headers: {
        ...mutationHeaders(tokens.vendor, "system-package-generate"),
        "If-Match": "1",
      },
      data: {
        expectedMonthVersion: 1,
        readinessRunId: f04ReadinessRunId,
        reason: "Real-system exact evidence package",
      },
    }),
    200,
  );
  packageId = String(generatedPackage.packageId);

  const packageView = await json(
    await request.get(`/api/v1/finance/packages/${packageId}`, {
      headers: authorization(tokens.vendor),
    }),
    200,
  );
  packageArtifactId = String(
    packageView.artifacts.find(
      (artifact: { artifactId?: string; scanStatus?: string }) =>
        artifact.artifactId && artifact.scanStatus === "PASSED",
    )?.artifactId ?? "",
  );
  expect(packageArtifactId).not.toBe("");
  expect(packageView.integrityVerified).toBe(true);

  const readiness = await json(
    await request.post(`/api/v1/finance/invoices/${invoiceId}/readiness-runs`, {
      headers: {
        ...mutationHeaders(tokens.vendor, "system-readiness"),
        "If-Match": String(invoiceVersion),
      },
      data: { expectedVersion: invoiceVersion },
    }),
    200,
  );
  invoiceVersion = Number(readiness.version);
  invoiceReadinessRunId = String(readiness.readiness.runId);
  invoiceRuleId = String(readiness.readiness.rules[0].ruleId);
  expect(readiness.readiness.eligibleForSubmission).toBe(true);
  expect(readiness.readiness.rules).toHaveLength(9);

  const submitted = await json(
    await request.post(`/api/v1/finance/invoices/${invoiceId}/submit`, {
      headers: {
        ...mutationHeaders(tokens.vendor, "system-submit"),
        "If-Match": String(invoiceVersion),
      },
      data: {
        expectedVersion: invoiceVersion,
        packageId,
        packageVersion: 1,
        readinessRunId: invoiceReadinessRunId,
        acknowledgment: true,
        reason: "Real-system exact versions reviewed",
      },
    }),
    200,
  );
  invoiceVersion = Number(submitted.version);
  expect(submitted.state).toBe("SUBMITTED_TO_PROCUREMENT");

  await page.goto(`/finance?monthId=${monthId}&invoiceId=${invoiceId}`);
  await expect(
    page.getByRole("heading", { name: "Invoice SYSTEM E2E F05 001" }),
  ).toBeVisible();
  await expect(page.getByText(/submitted to procurement/i).first()).toBeVisible();
});

test("[E2E-F05-SYS-002] Procurement, AP and restricted reports enforce real authorization", async ({
  request,
}) => {
  const query = await json(
    await request.post(
      `/api/v1/finance/procurement/invoices/${invoiceId}/queries`,
      {
        headers: {
          ...mutationHeaders(tokens.procurement, "system-query"),
          "If-Match": String(invoiceVersion),
        },
        data: {
          expectedVersion: invoiceVersion,
          category: "DOCUMENT_CLARIFICATION",
          summary: "Confirm the represented PO reference",
          ownerId: "user-arrow",
          dueAt: new Date(Date.now() + 86_400_000).toISOString(),
          reason: "Real-system assigned query regression",
        },
      },
    ),
    200,
  );
  invoiceVersion = Number(query.version);
  const queriedInvoice = await json(
    await request.get(`/api/v1/finance/invoices/${invoiceId}`, {
      headers: authorization(tokens.procurement),
    }),
    200,
  );
  expect(queriedInvoice.queries[0].ownerDisplay).toBe("user-arrow");
  const queryId = String(queriedInvoice.queries[0].queryId);

  const blockedException = await request.post(
    `/api/v1/finance/procurement/invoices/${invoiceId}/exceptions`,
    {
      headers: {
        ...mutationHeaders(tokens.procurement, "system-exception-guard"),
        "If-Match": String(invoiceVersion),
      },
      data: {
        expectedVersion: invoiceVersion,
        ruleId: invoiceRuleId,
        readinessRunId: invoiceReadinessRunId,
        packageId,
        packageVersion: 1,
        rationale: "A passing rule must never be overridden",
        validUntil: new Date(Date.now() + 86_400_000).toISOString(),
      },
    },
  );
  expect(blockedException.status()).toBe(409);

  const respondedQuery = await json(
    await request.post(
      `/api/v1/finance/procurement/queries/${queryId}/responses`,
      {
        headers: mutationHeaders(tokens.vendor, "system-query-response"),
        data: {
          response: "The represented PO maps to the immutable attached evidence.",
        },
      },
    ),
    200,
  );
  expect(respondedQuery.status).toBe("RESPONDED");
  expect(respondedQuery.responseCount).toBe(1);

  const closedQuery = await json(
    await request.post(`/api/v1/finance/procurement/queries/${queryId}/close`, {
      headers: mutationHeaders(tokens.procurement, "system-query-close"),
      data: {
        decision: "CLOSED",
        reason: "Clarification accepted without represented evidence mutation",
      },
    }),
    200,
  );
  expect(closedQuery.status).toBe("CLOSED");
  expect(closedQuery.responseCount).toBe(1);

  const approved = await json(
    await request.post(
      `/api/v1/finance/procurement/invoices/${invoiceId}/reviews`,
      {
        headers: {
          ...mutationHeaders(tokens.procurement, "system-review"),
          "If-Match": String(invoiceVersion),
        },
        data: {
          expectedVersion: invoiceVersion,
          decision: "APPROVED_FOR_PROCESSING",
          category: null,
          comment: "Exact real-system package and readiness approved",
          packageId,
          packageVersion: 1,
          readinessRunId: invoiceReadinessRunId,
        },
      },
    ),
    200,
  );
  invoiceVersion = Number(approved.version);
  expect(approved.state).toBe("APPROVED_FOR_PROCESSING");

  const payment = await json(
    await request.post(`/api/v1/finance/invoices/${invoiceId}/payments`, {
      headers: {
        ...mutationHeaders(tokens.finance, "system-payment"),
        "If-Match": String(invoiceVersion),
      },
      data: {
        expectedVersion: invoiceVersion,
        status: "SUBMITTED_TO_AP",
        statusAt: new Date(Date.now() - 60_000).toISOString(),
        expectedPaymentDate: "2026-08-15",
        actualPaymentDate: null,
        externalReference: "AP-SYSTEM-E2E",
        comment: "Approved real-system invoice submitted to AP",
      },
    }),
    200,
  );
  invoiceVersion = Number(payment.version);
  const payments = await json(
    await request.get(`/api/v1/finance/invoices/${invoiceId}/payments`, {
      headers: authorization(tokens.finance),
    }),
    200,
  );
  expect(payments[0].externalReference).toBe("AP-SYSTEM-E2E");

  const paymentExport = await json(
    await request.post("/api/v1/finance/exports", {
      headers: mutationHeaders(tokens.finance, "system-payment-export"),
      data: {
        reportId: "PAYMENT_AGING",
        reportVersion: "v1",
        format: "JSON",
        temporalMode: "CURRENT",
        filters: { engagementId, monthId },
        reason: "Restricted payment export regression",
      },
    }),
    200,
  );
  paymentExportId = String(paymentExport.exportId);
  await expectDenied(
    request.get(`/api/v1/finance/exports/${paymentExport.exportId}`, {
      headers: authorization(tokens.vendor),
    }),
  );

  const auditExport = await json(
    await request.post("/api/v1/finance/exports", {
      headers: mutationHeaders(tokens.governance, "system-audit-export"),
      data: {
        reportId: "COMMUNICATION_AUDIT",
        reportVersion: "v1",
        format: "JSON",
        temporalMode: "CURRENT",
        filters: { engagementId, monthId },
        reason: "Restricted communication audit regression",
      },
    }),
    200,
  );
  auditExportId = String(auditExport.exportId);
  await expectDenied(
    request.get(`/api/v1/finance/exports/${auditExport.exportId}`, {
      headers: authorization(tokens.finance),
    }),
  );
});

test("[E2E-09] route, body, cursor, export and artifact attacks remain non-disclosing and correlated", async ({
  request,
}) => {
  const container = requiredEnvironment("VMS_E2E_POSTGRES_CONTAINER");
  const before = Number(queryDatabase(container, `
    SELECT count(*) FROM f05_security_events
  `));
  const unknownInvoiceId = "ffffffff-ffff-4fff-8fff-ffffffffffff";
  const inaccessible = await request.get(
    `/api/v1/finance/invoices/${invoiceId}`,
    { headers: authorization(tokens.outsider) },
  );
  const unknown = await request.get(
    `/api/v1/finance/invoices/${unknownInvoiceId}`,
    { headers: authorization(tokens.outsider) },
  );
  expect(inaccessible.status()).toBe(unknown.status());
  const inaccessibleProblem = await inaccessible.json();
  const unknownProblem = await unknown.json();
  for (const field of ["title", "detail", "status"]) {
    expect(inaccessibleProblem[field]).toEqual(unknownProblem[field]);
  }
  expect(JSON.stringify(inaccessibleProblem)).not.toContain(invoiceId);
  expect(JSON.stringify(unknownProblem)).not.toContain(unknownInvoiceId);

  const forgedBody = await request.post("/api/v1/finance/invoices", {
    headers: mutationHeaders(tokens.outsider, "system-adversarial-body"),
    data: {
      monthId,
      documentKind: "PRIMARY",
      representedMetadata: {
        invoiceNumber: "ATTACKER",
        invoiceDate: "2026-07-31",
        billingPeriodStart: "2026-07-01",
        billingPeriodEnd: "2026-07-31",
        currency: "INR",
        taxableValue: "1.00",
        taxValue: "0.00",
        totalValue: "1.00",
      },
    },
  });
  expect([403, 404]).toContain(forgedBody.status());

  const cursorAttack = await request.get(
    `/api/v1/finance/invoices?monthId=${monthId}&cursor=attacker-controlled`,
    { headers: authorization(tokens.vendor) },
  );
  expect([400, 409]).toContain(cursorAttack.status());
  expect(await cursorAttack.text()).not.toContain("signature");

  for (const denied of [
    request.get(`/api/v1/finance/exports/${paymentExportId}`, {
      headers: authorization(tokens.outsider),
    }),
    request.get(`/api/v1/finance/exports/${auditExportId}`, {
      headers: authorization(tokens.outsider),
    }),
    request.post(
      `/api/v1/finance/packages/${packageId}/artifacts/${packageArtifactId}/download`,
      { headers: authorization(tokens.outsider) },
    ),
  ]) {
    const response = await denied;
    expect([403, 404]).toContain(response.status());
    const correlationId = response.headers()["x-correlation-id"];
    expect(correlationId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    expect(Number(queryDatabase(container, `
      SELECT count(*) FROM f05_security_events
      WHERE correlation_id = '${correlationId}'::uuid
        AND result = 'DENIED'
    `))).toBe(1);
  }
  const after = Number(queryDatabase(container, `
    SELECT count(*) FROM f05_security_events
  `));
  expect(after).toBeGreaterThan(before);
});

test("[E2E-F05-SYS-003] expired, revoked and cross-scope access fail closed", async ({
  request,
}) => {
  const shortShare = await json(
    await request.post(`/api/v1/finance/packages/${packageId}/shares`, {
      headers: mutationHeaders(tokens.vendor, "system-short-share"),
      data: {
        recipientSubject: "user-northstar",
        accessScope: "DOWNLOAD",
        expiresAt: new Date(Date.now() + 5_000).toISOString(),
        reason: "Bounded expiry regression",
      },
    }),
    201,
  );
  expect(shortShare.revoked).toBe(false);
  await expectStatus(
    request.post(
      `/api/v1/finance/packages/${packageId}/artifacts/${packageArtifactId}/download`,
      { headers: authorization(tokens.outsider) },
    ),
    200,
  );
  await new Promise((resolve) => setTimeout(resolve, 5_500));
  await expectDenied(
    request.post(
      `/api/v1/finance/packages/${packageId}/artifacts/${packageArtifactId}/download`,
      { headers: authorization(tokens.outsider) },
    ),
  );

  const revocableShare = await json(
    await request.post(`/api/v1/finance/packages/${packageId}/shares`, {
      headers: mutationHeaders(tokens.vendor, "system-revocable-share"),
      data: {
        recipientSubject: "user-northstar",
        accessScope: "DOWNLOAD",
        expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
        reason: "Explicit revocation regression",
      },
    }),
    201,
  );
  await json(
    await request.post(
      `/api/v1/finance/packages/${packageId}/shares/${revocableShare.shareId}/revoke`,
      {
        headers: mutationHeaders(tokens.vendor, "system-revoke-share"),
        data: { reason: "System E2E review window closed" },
      },
    ),
    200,
  );
  await expectDenied(
    request.post(
      `/api/v1/finance/packages/${packageId}/artifacts/${packageArtifactId}/download`,
      { headers: authorization(tokens.outsider) },
    ),
  );
  await expectDenied(
    request.get(`/api/v1/finance/invoices/${invoiceId}`, {
      headers: authorization(tokens.outsider),
    }),
  );
});

function requiredEnvironment(name: string) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required by the system-E2E runner.`);
  return value;
}

function authorization(token: string) {
  return { Authorization: `Bearer ${token}` };
}

function mutationHeaders(token: string, idempotencyKey: string) {
  return {
    ...authorization(token),
    "Content-Type": "application/json",
    "Idempotency-Key": idempotencyKey,
  };
}

async function json(response: APIResponse, expectedStatus: number) {
  expect(response.status(), await response.text()).toBe(expectedStatus);
  return response.json();
}

async function expectStatus(
  responsePromise: Promise<APIResponse>,
  expectedStatus: number,
) {
  const response = await responsePromise;
  expect(response.status(), await response.text()).toBe(expectedStatus);
}

async function expectDenied(responsePromise: Promise<APIResponse>) {
  const response = await responsePromise;
  expect([403, 404]).toContain(response.status());
  const body = await response.text();
  expect(body).not.toContain("\"filters\"");
  expect(body).not.toContain("\"objectKey\"");
}

function queryDatabase(container: string, sql: string) {
  return execFileSync(
    "docker",
    [
      "exec", container, "psql", "--no-psqlrc", "--tuples-only",
      "--no-align", "--username", "vms", "--dbname", "vms_workflow",
      "--command", sql,
    ],
    { encoding: "utf8", timeout: 10_000 },
  ).trim();
}
