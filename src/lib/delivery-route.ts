import { notFound } from "@tanstack/react-router";

import { featureFlags } from "./feature-flags";

export function isDeliveryRouteAvailable(
  deliveryPlanning = featureFlags.linear,
) {
  return deliveryPlanning;
}

export function requireDeliveryRoute() {
  if (!isDeliveryRouteAvailable()) throw notFound();
}
