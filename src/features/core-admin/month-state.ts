const REOPEN_REQUEST_STATES = new Set([
  "PLAN_APPROVED",
  "ACTIVE",
  "DELIVERY_SUBMITTED",
  "DELIVERY_REVIEW",
  "CONFIRMATION_PENDING",
  "CONFIRMED",
  "INVOICE_READY",
  "INVOICE_SUBMITTED",
  "CLOSED",
]);

export function administrativeMonthTransitions(state: string): string[] {
  if (state === "DRAFT" || state === "REOPENED") return ["PLANNING"];
  if (state === "PLAN_APPROVED") return ["ACTIVE", "REOPEN_REQUESTED"];
  if (REOPEN_REQUEST_STATES.has(state)) return ["REOPEN_REQUESTED"];
  return [];
}
