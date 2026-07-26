import { describe, expect, it } from "vitest";

import { isDeliveryRouteAvailable } from "./delivery-route";

describe("delivery planning route gate", () => {
  it("exposes delivery planning only when its rollout flag is enabled", () => {
    expect(isDeliveryRouteAvailable(true)).toBe(true);
    expect(isDeliveryRouteAvailable(false)).toBe(false);
  });
});
