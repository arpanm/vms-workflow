import { beforeEach, describe, expect, it, vi } from "vitest";

const apiMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  patch: vi.fn(),
  delete: vi.fn(),
}));

vi.mock("@/lib/api-client", () => ({ apiClient: apiMocks }));

import { collaborationApi } from "./api";

describe("collaboration API contract", () => {
  beforeEach(() => {
    Object.values(apiMocks).forEach((mock) => mock.mockReset());
  });

  it("derives personal filters in the collection request", async () => {
    apiMocks.get.mockResolvedValue([]);
    await collaborationApi.listWorkItems("eng/a", "NEXT", true, true);
    expect(apiMocks.get).toHaveBeenCalledWith(
      "/collaboration/work-items?engagementId=eng%2Fa&bucket=NEXT&assignedToMe=true&mentionedToMe=true",
    );
  });

  it("sends exact versions for delivery and approval decisions", async () => {
    apiMocks.patch.mockResolvedValue({});
    apiMocks.post.mockResolvedValue({});
    await collaborationApi.updateStatus("task/1", {
      expectedVersion: 7,
      lifecycleStatus: "DELIVERED",
      deliverySummary: "Accepted",
    });
    await collaborationApi.approve("task/1", {
      expectedVersion: 8,
      stage: "DELIVERY_L2",
      decision: "APPROVED",
      stackRank: null,
      comment: "Approved",
    });
    expect(apiMocks.patch).toHaveBeenCalledWith(
      "/collaboration/work-items/task%2F1/delivery-status",
      expect.objectContaining({ expectedVersion: 7 }),
    );
    expect(apiMocks.post).toHaveBeenCalledWith(
      "/collaboration/work-items/task%2F1/approvals",
      expect.objectContaining({ expectedVersion: 8, stage: "DELIVERY_L2" }),
    );
  });

  it("uses an HTTP delete for estimate removal", async () => {
    apiMocks.delete.mockResolvedValue({});
    await collaborationApi.deleteEstimate("task/1", "estimate/2");
    expect(apiMocks.delete).toHaveBeenCalledWith(
      "/collaboration/work-items/task%2F1/estimates/estimate%2F2",
    );
  });
});
