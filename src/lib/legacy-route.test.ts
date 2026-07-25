import { describe, expect, it } from "vitest";

import { isLegacyRouteAvailable } from "./legacy-route";

describe("legacy route feature gate", () => {
  it("makes compatibility routes available only while the flag is enabled", () => {
    expect(isLegacyRouteAvailable(true)).toBe(true);
    expect(isLegacyRouteAvailable(false)).toBe(false);
  });
});
