import { beforeEach, describe, expect, it, vi } from "vitest";

const apiMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  postForm: vi.fn(),
  download: vi.fn(),
}));

vi.mock("@/lib/api-client", () => ({ apiClient: apiMocks }));

import { financeApi } from "./api";

const intentKey = "00000000-0000-4000-8000-000000000501";

describe("finance API contract", () => {
  beforeEach(() => {
    Object.values(apiMocks).forEach((mock) => {
      mock.mockReset();
      mock.mockResolvedValue({});
    });
  });

  it("[F05-UNIT-API-001] URL-encodes identifiers and opaque cursors", async () => {
    await financeApi.months("offset/+==");
    await financeApi.invoices("offset/+==", "month/one");
    await financeApi.packageHistory("month/one", "offset/+==");
    await financeApi.packageAccess("package/one", "offset/+==");
    await financeApi.packageShares("package/one", "offset/+==");
    await financeApi.controlTower("offset/+==");
    await financeApi.reports("offset/+==");

    expect(apiMocks.get.mock.calls).toEqual([
      ["/finance/months?cursor=offset%2F%2B%3D%3D"],
      ["/finance/invoices?cursor=offset%2F%2B%3D%3D&monthId=month%2Fone"],
      ["/finance/months/month%2Fone/packages?cursor=offset%2F%2B%3D%3D"],
      ["/finance/packages/package%2Fone/access-events?cursor=offset%2F%2B%3D%3D"],
      ["/finance/packages/package%2Fone/shares?cursor=offset%2F%2B%3D%3D"],
      ["/finance/procurement/control-tower?cursor=offset%2F%2B%3D%3D"],
      ["/finance/reports?cursor=offset%2F%2B%3D%3D"],
    ]);
  });

  it("[F05-UNIT-API-002] creates and revokes a share with idempotency and no URL grant", async () => {
    const createInput = {
      recipientSubject: "auditor@reliance.example",
      accessScope: "DOWNLOAD" as const,
      expiresAt: "2026-08-31T18:30:00.000Z",
      reason: "Bounded external audit",
    };

    await financeApi.createPackageShare("package/one", createInput, intentKey);
    await financeApi.revokePackageShare(
      "package/one",
      { shareId: "share/one", reason: "Audit window closed" },
      intentKey,
    );

    expect(apiMocks.post).toHaveBeenNthCalledWith(
      1,
      "/finance/packages/package%2Fone/shares",
      createInput,
      { headers: { "Idempotency-Key": intentKey } },
    );
    expect(apiMocks.post).toHaveBeenNthCalledWith(
      2,
      "/finance/packages/package%2Fone/shares/share%2Fone/revoke",
      { reason: "Audit window closed" },
      { headers: { "Idempotency-Key": intentKey } },
    );
    expect(JSON.stringify(apiMocks.post.mock.calls)).not.toMatch(
      /signed.?url|pre.?signed|bearer|token/i,
    );
  });

  it("[F05-UNIT-API-003] preserves exact version and intent headers on package generation", async () => {
    const input = {
      expectedMonthVersion: 8,
      readinessRunId: "readiness-run-8",
      reason: "Month-close package generation",
    };

    await financeApi.generatePackage("month/one", input, intentKey);

    expect(apiMocks.post).toHaveBeenCalledWith(
      "/finance/months/month%2Fone/packages",
      input,
      {
        headers: {
          "If-Match": '"8"',
          "Idempotency-Key": intentKey,
        },
      },
    );
  });

  it("[F05-UNIT-API-004] separates exception request from authenticated second approval", async () => {
    const requestInput = {
      expectedVersion: 11,
      ruleId: "10000000-0000-0000-0000-000000000501",
      readinessRunId: "20000000-0000-0000-0000-000000000501",
      packageId: "30000000-0000-0000-0000-000000000501",
      packageVersion: 4,
      rationale: "Bounded exception for the exact blocked result",
      validUntil: "2026-08-31T18:30:00.000Z",
    };
    const approvalInput = {
      expectedVersion: 12,
      invoiceId: "40000000-0000-0000-0000-000000000501",
      ruleId: requestInput.ruleId,
      readinessRunId: requestInput.readinessRunId,
      packageId: requestInput.packageId,
      packageVersion: requestInput.packageVersion,
      policyVersionId: "50000000-0000-0000-0000-000000000501",
      policyVersion: 3,
    };

    await financeApi.requestException("invoice/one", requestInput, intentKey);
    await financeApi.approveException("exception/one", approvalInput, intentKey);

    expect(apiMocks.post).toHaveBeenNthCalledWith(
      1,
      "/finance/procurement/invoices/invoice%2Fone/exceptions",
      requestInput,
      {
        headers: {
          "If-Match": '"11"',
          "Idempotency-Key": intentKey,
        },
      },
    );
    expect(apiMocks.post).toHaveBeenNthCalledWith(
      2,
      "/finance/procurement/exceptions/exception%2Fone/second-approval",
      approvalInput,
      {
        headers: {
          "If-Match": '"12"',
          "Idempotency-Key": intentKey,
        },
      },
    );
    expect(JSON.stringify(apiMocks.post.mock.calls)).not.toContain("secondApproverId");
  });
});
