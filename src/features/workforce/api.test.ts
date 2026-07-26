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
});
