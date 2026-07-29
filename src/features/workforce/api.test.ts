import { beforeEach, describe, expect, it, vi } from "vitest";

const apiMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}));

vi.mock("@/lib/api-client", () => ({
  apiClient: apiMocks,
}));

import { workforceApi } from "./api";

describe("workforce API contract", () => {
  beforeEach(() => {
    apiMocks.get.mockReset();
    apiMocks.post.mockReset();
  });

  it("resolves the authenticated employee without listing a roster", async () => {
    apiMocks.get.mockResolvedValue({ id: "employee-self" });

    await workforceApi.myEmployee();

    expect(apiMocks.get).toHaveBeenCalledOnce();
    expect(apiMocks.get).toHaveBeenCalledWith("/workforce/employees/me");
    expect(apiMocks.get).not.toHaveBeenCalledWith(
      expect.stringContaining("organizationId"),
    );
  });

  it("keeps self attendance reads scoped to the server-resolved employee id", async () => {
    apiMocks.get.mockResolvedValue([]);

    await workforceApi.attendanceDays(
      "employee-self",
      "2026-07-01",
      "2026-07-31",
    );

    expect(apiMocks.get).toHaveBeenCalledWith(
      "/attendance/days?employeeId=employee-self&from=2026-07-01&to=2026-07-31",
    );
  });

  it("sends punch authority to the backend rather than a client role", async () => {
    apiMocks.post.mockResolvedValue({ id: "punch-1" });

    await workforceApi.punch(
      "employee-self",
      "CHECK_IN",
      "attendance-punch:test",
    );

    expect(apiMocks.post).toHaveBeenCalledWith("/attendance/punches", {
      employeeId: "employee-self",
      eventType: "CHECK_IN",
      idempotencyKey: "attendance-punch:test",
    });
  });

  it("uses exact-version leave decisions and manager-scoped inbox paths", async () => {
    apiMocks.get.mockResolvedValue([]);
    apiMocks.post.mockResolvedValue({ id: "decision-1" });

    await workforceApi.leaveRequestInbox("org/a");
    await workforceApi.decideLeave("request/a", {
      decision: "APPROVE",
      expectedVersion: 7,
      idempotencyKey: "leave-decision:stable",
      reason: "Reviewed evidence",
    });

    expect(apiMocks.get).toHaveBeenCalledWith(
      "/workforce/leave-request-inbox?organizationId=org%2Fa",
    );
    expect(apiMocks.post).toHaveBeenCalledWith(
      "/workforce/leave-requests/request%2Fa/decisions",
      {
        decision: "APPROVE",
        expectedVersion: 7,
        idempotencyKey: "leave-decision:stable",
        reason: "Reviewed evidence",
      },
    );
  });

  it("exposes paired break events and bounded CSV validation", async () => {
    apiMocks.post.mockResolvedValue({ id: "result-1" });

    await workforceApi.punch(
      "employee-self",
      "BREAK_START",
      "attendance-punch:break",
    );
    await workforceApi.importCsv("org-1", {
      importType: "EMPLOYEE_ALIASES",
      fileName: "aliases.csv",
      csvContent: "employeeNumber,aliasType\nAF-1,HRIS_ID",
      idempotencyKey: "csv:validate",
      apply: false,
    });

    expect(apiMocks.post).toHaveBeenNthCalledWith(1, "/attendance/punches", {
      employeeId: "employee-self",
      eventType: "BREAK_START",
      idempotencyKey: "attendance-punch:break",
    });
    expect(apiMocks.post).toHaveBeenNthCalledWith(
      2,
      "/workforce/organizations/org-1/imports",
      {
        importType: "EMPLOYEE_ALIASES",
        fileName: "aliases.csv",
        csvContent: "employeeNumber,aliasType\nAF-1,HRIS_ID",
        idempotencyKey: "csv:validate",
        apply: false,
      },
    );
  });
});
