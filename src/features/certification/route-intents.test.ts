import { describe, expect, it } from "vitest";

import { resolveReadinessActionPath } from "./route-intents";

describe("certification readiness route intents", () => {
  it("[F04-UNIT-ROUTE-001] maps server resource paths to valid application routes", () => {
    expect(resolveReadinessActionPath("/certification/months/month-1")).toBe(
      "/certification/month-1",
    );
    expect(resolveReadinessActionPath("/certification/confirmation-requests/request-1")).toBe(
      "/confirmation/requests/request-1",
    );
    expect(resolveReadinessActionPath("/delivery/integrations")).toBe(
      "/delivery/integration-health",
    );
    expect(resolveReadinessActionPath("/attendance")).toBe("/attendance/today");
  });

  it("[F04-UNIT-ROUTE-002] refuses external, malformed, and nonexistent CTA paths", () => {
    expect(resolveReadinessActionPath("https://example.invalid/secret")).toBeNull();
    expect(resolveReadinessActionPath("//example.invalid/secret")).toBeNull();
    expect(resolveReadinessActionPath("/confirmation/not-a-route/extra")).toBeNull();
    expect(resolveReadinessActionPath("/certification\\unsafe")).toBeNull();
  });
});
