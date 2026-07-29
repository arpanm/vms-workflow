import { describe, expect, it } from "vitest";

import { administrativeMonthTransitions } from "./month-state";

describe("administrative month transition presentation", () => {
  it("offers only backend-governed administrative edges", () => {
    expect(administrativeMonthTransitions("DRAFT")).toEqual(["PLANNING"]);
    expect(administrativeMonthTransitions("REOPENED")).toEqual(["PLANNING"]);
    expect(administrativeMonthTransitions("PLAN_APPROVED")).toEqual([
      "ACTIVE",
      "REOPEN_REQUESTED",
    ]);
    expect(administrativeMonthTransitions("CLOSED")).toEqual([
      "REOPEN_REQUESTED",
    ]);
  });

  it("does not invent workflow-owned transitions", () => {
    expect(administrativeMonthTransitions("PLANNING")).toEqual([]);
    expect(administrativeMonthTransitions("REOPEN_REQUESTED")).toEqual([]);
  });
});
