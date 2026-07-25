import { describe, expect, it } from "vitest";

import { readFeatureFlags, readSafeDemoMode } from "./feature-flags";

describe("readFeatureFlags", () => {
  it("keeps legacy mode on and new domains off by default", () => {
    expect(readFeatureFlags({ VITE_DEMO_MODE: "true" })).toEqual({
      legacyFixedCost: true,
      workforceGovernance: false,
      greytHR: false,
      linear: false,
      emailReplyIngestion: false,
    });
  });

  it("rejects demo authorization in production", () => {
    expect(() => readFeatureFlags({ VITE_DEMO_MODE: "true" }, true)).toThrow(
      "VITE_DEMO_MODE must be false in production",
    );
  });

  it("rejects ambiguous boolean values", () => {
    expect(() =>
      readFeatureFlags({ VITE_DEMO_MODE: "false", VITE_FEATURE_LINEAR: "yes" }),
    ).toThrow("Expected a boolean feature flag");
  });

  it("enables persona switching only through an explicit non-production flag", () => {
    expect(readSafeDemoMode({}, false)).toBe(false);
    expect(readSafeDemoMode({ VITE_DEMO_MODE: "true" }, false)).toBe(true);
    expect(readSafeDemoMode({ VITE_DEMO_MODE: "true" }, true)).toBe(false);
  });
});
