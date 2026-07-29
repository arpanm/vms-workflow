import type { Page, Route } from "@playwright/test";

export const migrationIds = {
  job: "f6000000-0000-0000-0000-000000000001",
  organization: "00000000-0000-0000-0000-000000000101",
  engagement: "00000000-0000-0000-0000-000000000401",
  month: "00000000-0000-0000-0000-000000000602",
  reconciliation: "f6000000-0000-0000-0000-000000000101",
};

const permissions = [
  "MIGRATION_READ",
  "MIGRATION_UPLOAD",
  "MIGRATION_VALIDATE",
  "MIGRATION_APPROVE",
  "MIGRATION_COMMIT",
  "MIGRATION_ROLLBACK",
  "MIGRATION_RETRO",
];

const templates = [
  ["01_employees", "01_employees_v1.csv", 1, []],
  ["02_employee_allocations", "02_employee_allocations_v1.csv", 2, ["01_employees"]],
  ["03_holidays", "03_holidays_v1.csv", 2, []],
  ["04_employee_date_overrides", "04_employee_date_overrides_v1.csv", 2, ["01_employees", "03_holidays"]],
  ["05_leave_balances", "05_leave_balances_v1.csv", 2, ["01_employees"]],
  ["06_leave_requests", "06_leave_requests_v1.csv", 3, ["05_leave_balances"]],
  ["07a_attendance_punches", "07a_attendance_punches_v1.csv", 3, ["01_employees", "03_holidays"]],
  ["07b_attendance_daily", "07b_attendance_daily_v1.csv", 3, ["01_employees", "03_holidays"]],
  ["08_deliverables", "08_deliverables_v1.csv", 4, ["02_employee_allocations"]],
  ["09_deliverable_linear_links", "09_deliverable_linear_links_v1.csv", 4, ["08_deliverables"]],
  ["10_delivery_certifications", "10_delivery_certifications_v1.csv", 5, ["08_deliverables"]],
  ["11_business_confirmations", "11_business_confirmations_v1.csv", 6, ["10_delivery_certifications"]],
  ["12_invoices", "12_invoices_v1.csv", 7, ["11_business_confirmations"]],
  ["13_approval_history", "13_approval_history_v1.csv", 8, ["08_deliverables"]],
].map(([code, filename, wave, dependencies]) => ({
  code,
  filename,
  wave,
  dependencies,
  version: "1",
  referenceSampleSha256: "a".repeat(64),
  headers: ["template_version", "organization_code", "source_system"],
}));

type Approval = {
  approvalId: string;
  role: string;
  actorDisplay: string;
  recordedAt: string;
  reconciliationHash: string;
};

function payloadBody(route: Route) {
  const request = route.request();
  const contentType = request.headers()["content-type"] ?? "";
  if (contentType.startsWith("multipart/form-data")) {
    const boundary = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType);
    const raw = request.postDataBuffer()?.toString("utf8") ?? "";
    const marker = 'name="metadata"';
    const markerIndex = raw.indexOf(marker);
    const contentStart = markerIndex < 0
      ? -1
      : raw.indexOf("\r\n\r\n", markerIndex);
    const contentEnd = contentStart < 0 || !boundary
      ? -1
      : raw.indexOf(
          `\r\n--${boundary[1] ?? boundary[2]}`,
          contentStart + 4,
        );
    if (contentStart >= 0 && contentEnd > contentStart) {
      try {
        return JSON.parse(
          raw.slice(contentStart + 4, contentEnd),
        ) as Record<string, unknown>;
      } catch {
        // Preserve the raw body so the assertion fails with inspectable
        // cross-browser evidence instead of silently inventing metadata.
      }
    }
    return raw;
  }
  try {
    return request.postDataJSON() as Record<string, unknown>;
  } catch {
    return request.postData() ?? {};
  }
}

export async function mockMigrationApi(
  page: Page,
  options: { denied?: boolean; initialState?: string } = {},
) {
  await page.addInitScript(() => {
    const captured: Array<Record<string, unknown>> = [];
    const capturedFileNames: string[] = [];
    const testWindow = window as typeof window & {
      __vmsMigrationMultipartMetadata?: Array<Record<string, unknown>>;
      __vmsMigrationMultipartFileNames?: string[];
    };
    testWindow.__vmsMigrationMultipartMetadata = captured;
    testWindow.__vmsMigrationMultipartFileNames = capturedFileNames;
    const nativeFetch = window.fetch.bind(window);
    window.fetch = async (input, init) => {
      if (init?.body instanceof FormData) {
        const file = init.body.get("file");
        if (file instanceof File) {
          capturedFileNames.push(file.name);
        }
        const metadata = init.body.get("metadata");
        if (metadata instanceof Blob) {
          const decoded = JSON.parse(
            await metadata.text(),
          ) as Record<string, unknown>;
          captured.push(decoded);
        }
      }
      return nativeFetch(input, init);
    };
  });
  const requests: Array<{ method: string; path: string; body: unknown; headers: Record<string, string> }> = [];
  const approvals: Approval[] = [];
  let approvalRole: "MIGRATION_LEAD" | "GOVERNANCE" = "MIGRATION_LEAD";
  let state = options.initialState ?? "UPLOADED";
  let version = 1;
  let committedRows = 0;
  const issues = [
    {
      rowNumber: 3,
      field: "employee_number",
      code: "MIG-REF-EMPLOYEE-NOT-FOUND",
      severity: "ERROR",
      safeMessage: "Employee natural key is not available in the authorized organization.",
      state: "INVALID",
    },
    {
      rowNumber: 4,
      field: "work_email",
      code: "MIG-FIELD-EMAIL",
      severity: "WARNING",
      safeMessage: "Email requires operator review.",
      state: "WARNING",
    },
  ];
  const reconciliation = () => ({
    reconciliationId: migrationIds.reconciliation,
    version: 1,
    sha256: "b".repeat(64),
    sourceSha256: "c".repeat(64),
    expectedRows: 3,
    validRows: 2,
    invalidRows: 1,
    committedRows,
    lowConfidenceRows: 0,
    expectedEmployeeDays: 2,
    importedEmployeeDays: 2,
    approvals,
  });
  const job = () => ({
    jobId: migrationIds.job,
    templateCode: "01_employees",
    templateVersion: 1,
    originalFileName: "employees-june.csv",
    safeFileName: "employees-june.csv",
    sourceSha256: "c".repeat(64),
    mode: "DRY_RUN",
    partialCommit: true,
    state,
    organizationId: migrationIds.organization,
    engagementId: migrationIds.engagement,
    monthId: migrationIds.month,
    representedPeriod: "2026-06",
    totalRows: 3,
    validRows: 2,
    warningRows: 1,
    invalidRows: 1,
    committedRows,
    progressPercent: ["UPLOADED", "SCANNING", "PARSING", "VALIDATING"].includes(state)
      ? 30
      : 100,
    version,
    createdAt: "2026-07-27T10:00:00Z",
    updatedAt: "2026-07-27T10:01:00Z",
    permissions,
    approvalRole: "MIGRATION_LEAD",
    issues,
    reconciliation: state === "UPLOADED" ? null : reconciliation(),
  });

  await page.route("**/api/v1/me", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        id: "user-migration-lead",
        email: "migration.lead@example.test",
        displayName: "Mina Migration Lead",
        organizationIds: [migrationIds.organization],
        permissions,
      }),
    }),
  );
  await page.route("**/api/v1/migrations/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    requests.push({
      method: request.method(),
      path,
      body: request.method() === "GET" ? null : payloadBody(route),
      headers: request.headers(),
    });

    if (options.denied) {
      return route.fulfill({
        status: 403,
        contentType: "application/problem+json",
        body: JSON.stringify({
          code: "FORBIDDEN",
          detail: "The requested migration resource is not available.",
          correlationId: "corr-migration-denied",
        }),
      });
    }
    if (path.endsWith("/access")) {
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          permissions,
          engagementId: migrationIds.engagement,
          scopes: [{ engagementId: migrationIds.engagement }],
          approvalRole,
          scopeLabel: "ArrowFoundry governance",
          externalAcceptance: "ACTION_REQUIRED",
        }),
      });
    }
    if (path.endsWith("/templates")) {
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(templates) });
    }
    if (path.includes("/templates/") && path.endsWith("/download")) {
      return route.fulfill({
        status: 200,
        contentType: "text/csv",
        headers: { "content-disposition": 'attachment; filename="template.csv"' },
        body: "template_version,organization_code,source_system\n",
      });
    }
    if (path.endsWith("/jobs") && request.method() === "GET") {
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ items: [job()], nextCursor: null, totalCount: 1 }),
      });
    }
    if (path.endsWith("/jobs") && request.method() === "POST") {
      state = "UPLOADED";
      version++;
      return route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(job()) });
    }
    if (path.endsWith(`/jobs/${migrationIds.job}`) && request.method() === "GET") {
      return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(job()) });
    }
    if (path.endsWith("/validate")) {
      state = "READY_TO_COMMIT";
      version++;
    } else if (path.endsWith("/approvals")) {
      const body = payloadBody(route);
      approvals.push({
        approvalId: `approval-${approvals.length + 1}`,
        role: String(body.role),
        actorDisplay: body.role === "MIGRATION_LEAD" ? "Mina Migration Lead" : "Gita Governance Reviewer",
        recordedAt: `2026-07-27T10:0${approvals.length + 2}:00Z`,
        reconciliationHash: "b".repeat(64),
      });
      version++;
    } else if (path.endsWith("/commit")) {
      state = "COMPLETED_WITH_ERRORS";
      committedRows = 2;
      version++;
    } else if (path.endsWith("/reprocess")) {
      state = "READY_TO_COMMIT";
      version++;
    } else if (path.endsWith("/retry")) {
      state = "UPLOADED";
      version++;
    } else if (path.endsWith("/cancel")) {
      state = "CANCELLED";
      version++;
    } else if (path.endsWith("/rollback")) {
      state = "ROLLED_BACK";
      committedRows = 0;
      version++;
    } else if (path.endsWith("/retro-requests")) {
      return route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify({ requestId: "retro-1", recordedAt: "2026-07-27T10:10:00Z" }),
      });
    } else if (path.endsWith("/errors/download")) {
      return route.fulfill({
        status: 200,
        contentType: "text/csv",
        headers: { "content-disposition": 'attachment; filename="migration-errors.csv"' },
        body: "'=SAFE,row_number,error_code\n",
      });
    }
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(job()) });
  });

  return {
    requests,
    job,
    multipartMetadata: () => page.evaluate(() => {
      const testWindow = window as typeof window & {
        __vmsMigrationMultipartMetadata?: Array<Record<string, unknown>>;
      };
      return testWindow.__vmsMigrationMultipartMetadata ?? [];
    }),
    multipartFileNames: () => page.evaluate(() => {
      const testWindow = window as typeof window & {
        __vmsMigrationMultipartFileNames?: string[];
      };
      return testWindow.__vmsMigrationMultipartFileNames ?? [];
    }),
    actAsGovernanceReviewer: () => {
      approvalRole = "GOVERNANCE";
    },
  };
}
