import { notFound } from "@tanstack/react-router";

import { featureFlags } from "./feature-flags";

export function isWorkforceRouteAvailable(
  workforceGovernance = featureFlags.workforceGovernance,
) {
  return workforceGovernance;
}

export function requireWorkforceRoute() {
  if (!isWorkforceRouteAvailable()) throw notFound();
}
