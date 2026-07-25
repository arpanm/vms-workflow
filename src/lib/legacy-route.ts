import { notFound } from "@tanstack/react-router";

import { featureFlags } from "./feature-flags";

export function isLegacyRouteAvailable(
  legacyFixedCost = featureFlags.legacyFixedCost,
) {
  return legacyFixedCost;
}

export function requireLegacyRoute() {
  if (!isLegacyRouteAvailable()) {
    throw notFound();
  }
}
