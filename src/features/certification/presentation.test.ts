import { describe, expect, it } from "vitest";

import { ApiError } from "@/lib/api-client";

import {
  certificationDecisionOptions,
  certificationRequiresComment,
  certificationRequiresObservations,
  classifyCertificationError,
  confirmationDecisionOptions,
  confirmationRequiresComment,
  criterionDecisionOptions,
  deliveryOutcomeOptions,
  readinessTone,
  requiresVendorVariance,
} from "./presentation";
import type { ReadinessStatus } from "./contracts";

describe("certification presentation contract", () => {
  it("[F04-UNIT-PRES-001] classifies safe denial, conflict, locked, validation, and outage states", () => {
    const classify = (status: number, code?: string) =>
      classifyCertificationError(
        new ApiError("fixture detail", {
          status,
          code,
          correlationId: "corr-f04",
        }),
      );

    expect(classify(401)).toBe("unauthenticated");
    expect(classify(403)).toBe("permission");
    expect(classify(404)).toBe("not-found");
    expect(classify(409)).toBe("version-conflict");
    expect(classify(412)).toBe("version-conflict");
    expect(classify(409, "MONTH_LOCKED")).toBe("locked");
    expect(classify(422)).toBe("validation");
    expect(classify(503)).toBe("unavailable");
    expect(classifyCertificationError(new Error("unknown"))).toBe("unexpected");
  });

  it("[F04-UNIT-PRES-002] keeps every vendor, criterion, certification, and confirmation choice explicit", () => {
    expect(deliveryOutcomeOptions.map(({ value }) => value)).toEqual([
      "COMPLETED",
      "PARTIALLY_COMPLETED",
      "DEFERRED",
      "NOT_COMPLETED",
      "CANCELLED_BY_APPROVED_CHANGE",
    ]);
    expect(certificationDecisionOptions.map(({ value }) => value)).toContain(
      "MORE_INFORMATION_REQUIRED",
    );
    expect(criterionDecisionOptions.map(({ value }) => value)).toEqual([
      "MET",
      "PARTIALLY_MET",
      "NOT_MET",
      "NOT_APPLICABLE",
    ]);
    expect(confirmationDecisionOptions.map(({ value }) => value)).toEqual([
      "CONFIRM",
      "REQUEST_CORRECTION",
      "REJECT",
    ]);
  });

  it("[F04-UNIT-PRES-003] requires narrative evidence for non-simple outcomes and non-confirmation", () => {
    expect(requiresVendorVariance("COMPLETED")).toBe(false);
    expect(requiresVendorVariance("PARTIALLY_COMPLETED")).toBe(true);
    expect(requiresVendorVariance("DEFERRED")).toBe(true);
    expect(certificationRequiresComment("ACCEPTED")).toBe(false);
    expect(certificationRequiresComment("ACCEPTED_WITH_OBSERVATIONS")).toBe(false);
    expect(certificationRequiresComment("PARTIALLY_ACCEPTED")).toBe(true);
    expect(certificationRequiresObservations("ACCEPTED_WITH_OBSERVATIONS")).toBe(true);
    expect(confirmationRequiresComment("CONFIRM")).toBe(false);
    expect(confirmationRequiresComment("REQUEST_CORRECTION")).toBe(true);
    expect(confirmationRequiresComment("REJECT")).toBe(true);
  });

  it("[F04-UNIT-PRES-004] provides non-color readiness differentiation for every server state", () => {
    const statuses: ReadinessStatus[] = ["READY", "BLOCKED", "ACTION_REQUIRED", "STALE"];
    expect(statuses.map(readinessTone)).toEqual([
      "border-success/40 bg-success/5",
      "border-destructive/40 bg-destructive/5",
      "border-warning/40 bg-warning/5",
      "border-warning/40 bg-warning/5",
    ]);
  });
});
