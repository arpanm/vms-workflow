import { expect, test, type APIResponse } from "@playwright/test";

import "./fixtures/quality-gates";

const engagementId = "00000000-0000-0000-0000-000000000401";
const organizationId = "00000000-0000-0000-0000-000000000101";
const browserTokenKey = "__vms_system_e2e_access_token";
const employeeNumber = `AF-SYSTEM-${Date.now()}`;
const employeeHeader = [
  "template_version", "organization_code", "employee_number",
  "first_name", "last_name", "display_name", "work_email", "join_date",
  "exit_date", "employment_status", "designation", "skill_category",
  "manager_employee_number", "timezone", "working_calendar_code",
  "attendance_policy_code", "leave_policy_code", "attendance_source_mode",
  "greythr_employee_ref", "activation_status", "source_system",
  "source_reference", "notes",
].join(",");

const tokens = {
  lead: requiredEnvironment("VMS_E2E_TOKEN_USER_ARROW"),
  governance: requiredEnvironment("VMS_E2E_TOKEN_USER_GOVERNANCE"),
  outsider: requiredEnvironment("VMS_E2E_TOKEN_USER_NORTHSTAR"),
};

let jobId = "";
let jobVersion = 0;
let reconciliationId = "";
let reconciliationHash = "";

test.describe.configure({ mode: "serial" });

test("[E2E-F06-SYS-001] real browser discovers server-derived migration scope and templates", async ({
  page,
  request,
}) => {
  await page.addInitScript(
    ({ key, token }) => window.sessionStorage.setItem(key, token),
    { key: browserTokenKey, token: tokens.lead },
  );
  await page.goto("/migration");
  await expect(
    page.getByRole("heading", { name: "Historical migration center" }),
  ).toBeVisible();
  await expect(
    page.getByRole("table", { name: "Historical migration templates" }),
  ).toBeVisible();
  await expect(
    page.getByRole("table", { name: "Historical migration templates" })
      .getByText("01_employees_v1.csv"),
  ).toBeVisible();
  await expect(page.getByText(engagementId)).toBeVisible();

  const access = await json(
    await request.get("/api/v1/migrations/access", {
      headers: authorization(tokens.lead),
    }),
    200,
  );
  expect(access.engagementId).toBe(engagementId);
  expect(access.approvalRole).toBe("MIGRATION_LEAD");
  expect(access.permissions).toContain("MIGRATION_COMMIT");

  const templates = await json(
    await request.get(
      `/api/v1/migrations/templates?engagementId=${engagementId}`,
      { headers: authorization(tokens.lead) },
    ),
    200,
  );
  expect(templates).toHaveLength(14);
  expect(templates[0].code).toBe("01_employees");
});

test("[E2E-F06-SYS-002] real upload scans, validates and produces exact pre-commit reconciliation", async ({
  request,
}) => {
  const row = [
    "1", "ARROWFOUNDRY", employeeNumber, "System", "Migration",
    "System Migration", `${employeeNumber.toLowerCase()}@example.test`,
    "2026-06-01", "", "ACTIVE", "Engineer", "Platform", "",
    "Asia/Kolkata", "AF_STANDARD", "AF_ATTENDANCE", "AF_LEAVE",
    "HISTORICAL_IMPORT", "", "ENABLED", "APPROVED_SPREADSHEET",
    "real-system-f06", "Playwright real system",
  ].join(",");
  const metadata = JSON.stringify({
    engagementId,
    organizationId,
    engagementMonthId: null,
    templateCode: "01_employees",
    templateVersion: "1",
    mode: "DRY_RUN",
    partialCommit: false,
    sourceType: "APPROVED_SPREADSHEET",
    confidence: "HIGH",
    sourceDescription: "Real-system governed employee migration",
  });
  const uploaded = await json(
    await request.post("/api/v1/migrations/jobs", {
      headers: authorization(tokens.lead),
      multipart: {
        file: {
          name: "system-employees.csv",
          mimeType: "text/csv",
          buffer: Buffer.from(`${employeeHeader}\r\n${row}\r\n`),
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
  jobId = String(uploaded.jobId);
  jobVersion = Number(uploaded.version);
  expect(uploaded.scanStatus).toBe("PASSED");

  const validated = await json(
    await request.post(`/api/v1/migrations/jobs/${jobId}/validate`, {
      headers: versionHeaders(tokens.lead, jobVersion, "f06-system-validate"),
      data: { expectedVersion: jobVersion, partialCommit: false },
    }),
    200,
  );
  jobVersion = Number(validated.version);
  expect(validated.state).toBe("READY_TO_COMMIT");
  expect(validated.validRows).toBe(1);
  expect(validated.reconciliation).not.toBeNull();
  reconciliationId = String(validated.reconciliation.reconciliationId);
  reconciliationHash = String(validated.reconciliation.sha256);
  expect(reconciliationHash).toMatch(/^[0-9a-f]{64}$/);

  const exactReport = await json(
    await request.get(
      `/api/v1/migrations/jobs/${jobId}/reconciliation`,
      { headers: authorization(tokens.lead) },
    ),
    200,
  );
  expect(exactReport.id).toBe(reconciliationId);
  expect(exactReport.reportHash).toBe(reconciliationHash);
  expect(exactReport.counts.total).toBe(1);
});

test("[E2E-F06-SYS-003] server-derived lead and governance authorities approve and commit one domain effect", async ({
  request,
}) => {
  const forged = await request.post(
    `/api/v1/migrations/jobs/${jobId}/approvals`,
    {
      headers: versionHeaders(
        tokens.lead, jobVersion, "f06-system-forged-governance",
      ),
      data: {
        expectedVersion: jobVersion,
        role: "GOVERNANCE_REVIEWER",
        decision: "APPROVED",
        reconciliationId,
        reconciliationHash,
        reason: "This client-selected authority must be rejected",
      },
    },
  );
  expect(forged.status()).toBe(403);

  await json(
    await request.post(`/api/v1/migrations/jobs/${jobId}/approvals`, {
      headers: versionHeaders(
        tokens.lead, jobVersion, "f06-system-lead-approval",
      ),
      data: {
        expectedVersion: jobVersion,
        role: "MIGRATION_LEAD",
        decision: "APPROVED",
        reconciliationId,
        reconciliationHash,
        reason: "Exact proposed employee version reviewed",
      },
    }),
    200,
  );
  await json(
    await request.post(`/api/v1/migrations/jobs/${jobId}/approvals`, {
      headers: versionHeaders(
        tokens.governance, jobVersion, "f06-system-governance-approval",
      ),
      data: {
        expectedVersion: jobVersion,
        role: "GOVERNANCE_REVIEWER",
        decision: "APPROVED",
        reconciliationId,
        reconciliationHash,
        reason: "Independent exact reconciliation reviewed",
      },
    }),
    200,
  );

  const committed = await json(
    await request.post(`/api/v1/migrations/jobs/${jobId}/commit`, {
      headers: versionHeaders(tokens.lead, jobVersion, "f06-system-commit"),
      data: { expectedVersion: jobVersion, partialCommit: false },
    }),
    200,
  );
  jobVersion = Number(committed.version);
  expect(committed.state).toBe("COMPLETED");
  expect(committed.committedRows).toBe(1);

  const employees = await json(
    await request.get(
      `/api/v1/workforce/employees?organizationId=${organizationId}`,
      { headers: authorization(tokens.lead) },
    ),
    200,
  );
  expect(employees).toContainEqual(
    expect.objectContaining({ employeeNumber }),
  );

  const outsider = await request.get(
    `/api/v1/migrations/jobs/${jobId}`,
    { headers: authorization(tokens.outsider) },
  );
  expect([403, 404]).toContain(outsider.status());
});

test("[E2E-F06-SYS-004] real audit and unconsumed rollback remain traceable", async ({
  request,
}) => {
  const beforeRollback = await json(
    await request.get(`/api/v1/migrations/jobs/${jobId}/audit`, {
      headers: authorization(tokens.lead),
    }),
    200,
  );
  expect(beforeRollback.map(
    (event: { eventType: string }) => event.eventType,
  )).toEqual(expect.arrayContaining([
    "MIGRATION_SOURCE_UPLOADED",
    "MIGRATION_VALIDATED",
    "MIGRATION_APPROVAL_RECORDED",
    "MIGRATION_JOB_COMMITTED",
  ]));

  const rolledBack = await json(
    await request.post(`/api/v1/migrations/jobs/${jobId}/rollback`, {
      headers: versionHeaders(
        tokens.lead, jobVersion, "f06-system-rollback",
      ),
      data: {
        expectedVersion: jobVersion,
        reason: "Real-system unconsumed compensation proof",
      },
    }),
    200,
  );
  expect(rolledBack.state).toBe("ROLLED_BACK");

  const afterRollback = await json(
    await request.get(`/api/v1/migrations/jobs/${jobId}/audit`, {
      headers: authorization(tokens.lead),
    }),
    200,
  );
  expect(afterRollback.at(-1).eventType)
    .toBe("MIGRATION_BATCH_COMPENSATED");
});

test("[E2E-F06-SYS-005] safe error export and rejected-row reprocess retain only rejects", async ({
  request,
}) => {
  const suffix = Date.now();
  const validEmployee = `AF-SYSTEM-PARTIAL-${suffix}`;
  const rejectedEmployee = `AF-SYSTEM-REJECT-${suffix}`;
  const row = (number: string, email: string) => [
    "1", "ARROWFOUNDRY", number, "Partial", "Migration",
    "Partial Migration", email, "2026-06-01", "", "ACTIVE",
    "Engineer", "Platform", "", "Asia/Kolkata", "AF_STANDARD",
    "AF_ATTENDANCE", "AF_LEAVE", "HISTORICAL_IMPORT", "", "ENABLED",
    "APPROVED_SPREADSHEET", "real-system-partial", "=unsafe-note",
  ].join(",");
  const metadata = JSON.stringify({
    engagementId,
    organizationId,
    engagementMonthId: null,
    templateCode: "01_employees",
    templateVersion: "1",
    mode: "DRY_RUN",
    partialCommit: true,
    sourceType: "APPROVED_SPREADSHEET",
    confidence: "HIGH",
    sourceDescription: "Real-system partial employee migration",
  });
  const parent = await json(
    await request.post("/api/v1/migrations/jobs", {
      headers: authorization(tokens.lead),
      multipart: {
        file: {
          name: "system-partial-employees.csv",
          mimeType: "text/csv",
          buffer: Buffer.from([
            employeeHeader,
            row(validEmployee, `${validEmployee.toLowerCase()}@example.test`),
            row(rejectedEmployee, "not-an-email"),
            "",
          ].join("\r\n")),
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
  let parentVersion = Number(parent.version);
  const validated = await json(
    await request.post(`/api/v1/migrations/jobs/${parent.jobId}/validate`, {
      headers: versionHeaders(
        tokens.lead, parentVersion, "f06-system-partial-validate",
      ),
      data: { expectedVersion: parentVersion, partialCommit: true },
    }),
    200,
  );
  parentVersion = Number(validated.version);
  expect(validated.totalRows).toBe(2);
  expect(validated.validRows).toBe(1);
  expect(validated.invalidRows).toBe(1);

  const errorExport = await request.get(
    `/api/v1/migrations/jobs/${parent.jobId}/errors/download`,
    { headers: authorization(tokens.lead) },
  );
  expect(errorExport.status()).toBe(200);
  expect(errorExport.headers()["content-disposition"]).toContain("attachment");
  const errorCsv = await errorExport.text();
  expect(errorCsv).toContain("FIELD_INVALID_EMAIL");
  expect(errorCsv).not.toMatch(/(?:^|,)[=+@-]/m);

  const report = validated.reconciliation;
  for (const [token, role, key] of [
    [tokens.lead, "MIGRATION_LEAD", "partial-lead"],
    [tokens.governance, "GOVERNANCE_REVIEWER", "partial-governance"],
  ] as const) {
    await json(
      await request.post(
        `/api/v1/migrations/jobs/${parent.jobId}/approvals`,
        {
          headers: versionHeaders(
            token, parentVersion, `f06-system-${key}`,
          ),
          data: {
            expectedVersion: parentVersion,
            role,
            decision: "APPROVED",
            reconciliationId: report.reconciliationId,
            reconciliationHash: report.sha256,
            reason: "Partial commit reconciliation reviewed",
          },
        },
      ),
      200,
    );
  }
  const committed = await json(
    await request.post(`/api/v1/migrations/jobs/${parent.jobId}/commit`, {
      headers: versionHeaders(
        tokens.lead, parentVersion, "f06-system-partial-commit",
      ),
      data: { expectedVersion: parentVersion, partialCommit: true },
    }),
    200,
  );
  expect(committed.state).toBe("COMPLETED_WITH_ERRORS");
  expect(committed.committedRows).toBe(1);
  expect(committed.rejectedRows ?? committed.rejectedCount).toBe(1);

  const child = await json(
    await request.post(
      `/api/v1/migrations/jobs/${parent.jobId}/reprocess`,
      {
        headers: versionHeaders(
          tokens.lead,
          Number(committed.version),
          "f06-system-reprocess",
        ),
        data: { expectedVersion: Number(committed.version) },
      },
    ),
    200,
  );
  const revalidated = await json(
    await request.post(
      `/api/v1/migrations/jobs/${child.jobId}/validate`,
      {
        headers: versionHeaders(
          tokens.lead,
          Number(child.version),
          "f06-system-reprocess-validate",
        ),
        data: { expectedVersion: Number(child.version) },
      },
    ),
    200,
  );
  expect(revalidated.totalRows).toBe(1);
  expect(revalidated.validRows).toBe(0);
  expect(revalidated.invalidRows).toBe(1);
  const reprocessedRows = await json(
    await request.get(
      `/api/v1/migrations/jobs/${child.jobId}/rows?limit=20`,
      { headers: authorization(tokens.lead) },
    ),
    200,
  );
  expect(reprocessedRows.items ?? reprocessedRows).toHaveLength(1);
});

test("[E2E-F06-SYS-006] real retro request records current authenticated time", async ({
  request,
}) => {
  const before = Date.now();
  const retro = await json(
    await request.post("/api/v1/migrations/retro-requests", {
      headers: {
        ...authorization(tokens.governance),
        "Idempotency-Key": "f06-system-retro-request",
      },
      data: {
        engagementId,
        engagementMonthId: "00000000-0000-0000-0000-000000000601",
        requestType: "CONFIRMATION",
        representedMonth: "2026-06-01",
        reason: "Original historical actor unavailable",
        originalActorUnavailable: true,
        delegationEvidenceReference: "system-e2e-delegation",
      },
    }),
    201,
  );
  expect(retro.state).toBe("PENDING");
  expect(retro.representedMonth).toBe("2026-06-01");
  expect(Date.parse(retro.createdAt)).toBeGreaterThanOrEqual(before);
  expect(retro.decisionAt).toBeNull();
});

test("[E2E-F06-SYS-007] real quarantine blocks parsing even for malformed CSV", async ({
  request,
}) => {
  const metadata = JSON.stringify({
    engagementId,
    organizationId,
    engagementMonthId: null,
    templateCode: "01_employees",
    templateVersion: "1",
    mode: "DRY_RUN",
    partialCommit: false,
    sourceType: "OTHER",
    confidence: "UNVERIFIED",
    sourceDescription: "Real-system quarantine ordering proof",
  });
  const uploaded = await json(
    await request.post("/api/v1/migrations/jobs", {
      headers: authorization(tokens.lead),
      multipart: {
        file: {
          name: "system-quarantined-malformed.csv",
          mimeType: "text/csv",
          buffer: Buffer.from(
            "salary,malformed\r\n"
              + 'EICAR-STANDARD-ANTIVIRUS-TEST-FILE,"unterminated\r\n',
          ),
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
  expect(uploaded.scanStatus).toBe("QUARANTINED");
  expect(uploaded.totalRows).toBe(0);

  const blocked = await request.post(
    `/api/v1/migrations/jobs/${uploaded.jobId}/validate`,
    {
      headers: versionHeaders(
        tokens.lead,
        Number(uploaded.version),
        "f06-system-quarantine-validate",
      ),
      data: { expectedVersion: Number(uploaded.version) },
    },
  );
  expect(blocked.status()).toBe(409);
  expect((await blocked.json()).code).toBe("SOURCE_SCAN_NOT_PASSED");
});

function authorization(token: string) {
  return { Authorization: `Bearer ${token}` };
}

function versionHeaders(
  token: string,
  version: number,
  idempotencyKey: string,
) {
  return {
    ...authorization(token),
    "If-Match": String(version),
    "Idempotency-Key": idempotencyKey,
  };
}

async function json(response: APIResponse, expectedStatus: number) {
  expect(response.status(), await response.text()).toBe(expectedStatus);
  return response.json();
}

function requiredEnvironment(name: string) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required.`);
  return value;
}
