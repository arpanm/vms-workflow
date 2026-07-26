import { ApiError } from "@/lib/api-client";

import type {
  CertificationDecision,
  ConfirmationDecision,
  CriterionDecision,
  DeliveryOutcome,
  ReadinessStatus,
} from "./contracts";

export type CertificationErrorKind =
  | "unauthenticated"
  | "permission"
  | "not-found"
  | "version-conflict"
  | "locked"
  | "validation"
  | "unavailable"
  | "unexpected";

export function classifyCertificationError(error: unknown): CertificationErrorKind {
  if (!(error instanceof ApiError)) return "unexpected";
  if (error.status === 0 || error.status >= 500) return "unavailable";
  if (error.status === 401) return "unauthenticated";
  if (error.status === 403) return "permission";
  if (error.status === 404) return "not-found";
  if (error.code === "MONTH_LOCKED" || error.code === "SUBMISSION_LOCKED") return "locked";
  if (error.status === 409 || error.status === 412) return "version-conflict";
  if (error.status === 400 || error.status === 422) return "validation";
  return "unexpected";
}

export const deliveryOutcomeOptions: Array<{ value: DeliveryOutcome; label: string }> = [
  { value: "COMPLETED", label: "Completed" },
  { value: "PARTIALLY_COMPLETED", label: "Partially completed" },
  { value: "DEFERRED", label: "Deferred" },
  { value: "NOT_COMPLETED", label: "Not completed" },
  { value: "CANCELLED_BY_APPROVED_CHANGE", label: "Cancelled by approved change" },
];

export const certificationDecisionOptions: Array<{
  value: CertificationDecision;
  label: string;
}> = [
  { value: "ACCEPTED", label: "Accepted" },
  { value: "ACCEPTED_WITH_OBSERVATIONS", label: "Accepted with observations" },
  { value: "PARTIALLY_ACCEPTED", label: "Partially accepted" },
  { value: "CLIENT_DEPENDENCY_DEFERRED", label: "Deferred — client dependency" },
  { value: "VENDOR_DEPENDENCY_DEFERRED", label: "Deferred — vendor dependency" },
  { value: "REJECTED", label: "Rejected" },
  { value: "CANCELLED_BY_APPROVED_CHANGE", label: "Cancelled by approved change" },
  { value: "MORE_INFORMATION_REQUIRED", label: "More information required" },
];

export const criterionDecisionOptions: Array<{ value: CriterionDecision; label: string }> = [
  { value: "MET", label: "Met" },
  { value: "PARTIALLY_MET", label: "Partially met" },
  { value: "NOT_MET", label: "Not met" },
  { value: "NOT_APPLICABLE", label: "Not applicable" },
];

export const confirmationDecisionOptions: Array<{
  value: ConfirmationDecision;
  label: string;
}> = [
  { value: "CONFIRM", label: "Confirm exact version" },
  { value: "REQUEST_CORRECTION", label: "Request correction" },
  { value: "REJECT", label: "Reject" },
];

export function readinessTone(status: ReadinessStatus) {
  switch (status) {
    case "READY":
      return "border-success/40 bg-success/5";
    case "ACTION_REQUIRED":
    case "STALE":
      return "border-warning/40 bg-warning/5";
    case "BLOCKED":
      return "border-destructive/40 bg-destructive/5";
  }
}

export function requiresVendorVariance(outcome: DeliveryOutcome) {
  return outcome !== "COMPLETED";
}

export function certificationRequiresComment(decision: CertificationDecision) {
  return !["ACCEPTED", "ACCEPTED_WITH_OBSERVATIONS"].includes(decision);
}

export function certificationRequiresObservations(decision: CertificationDecision) {
  return decision === "ACCEPTED_WITH_OBSERVATIONS";
}

export function confirmationRequiresComment(decision: ConfirmationDecision) {
  return decision !== "CONFIRM";
}
