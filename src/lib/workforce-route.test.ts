import { describe, expect, it } from "vitest";

import { isWorkforceRouteAvailable } from "./workforce-route";

describe("workforce route feature gate", () => {
  it("exposes workforce and attendance routes only when enabled", () => {
    expect(isWorkforceRouteAvailable(true)).toBe(true);
    expect(isWorkforceRouteAvailable(false)).toBe(false);
  });
});
