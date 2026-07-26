import { describe, expect, it } from "vitest";

import { ApiError } from "@/lib/api-client";

import type { AttendanceDay } from "./domain";
import {
  attendanceAction,
  classifyWorkforceError,
  formatMinutes,
  hasMissingPunch,
  validateLeaveRequest,
  validateRegularization,
} from "./presentation";

const day: AttendanceDay = {
  id: "d1",
  employeeId: "e1",
  workDate: "2026-07-26",
  expectedClassification: "WORKING",
  expectedMinutes: 540,
  sourceMode: "INTERNAL_AUTHORITATIVE",
  leaveUnits: 0,
  finalStatus: "NOT_STARTED",
  calculationVersion: 1,
  computedAt: "2026-07-26T00:00:00Z",
};

describe("workforce presentation rules", () => {
  it("selects an action only when the backend says it is allowed", () => {
    expect(attendanceAction(day).action).toBe("CHECK_IN");
    expect(
      attendanceAction({
        ...day,
        exceptionCode: "OPEN_SESSION",
      }).action,
    ).toBe("CHECK_OUT");
    expect(
      attendanceAction({ ...day, sourceMode: "GREYTHR_AUTHORITATIVE" }).action,
    ).toBe("READ_ONLY");
  });

  it("never turns a missing checkout into worked minutes", () => {
    const missingDay = {
      id: null,
      employeeId: "e1",
      workDate: "2026-07-26",
      expectedClassification: "WORKING",
      expectedMinutes: 540,
      finalStatus: "MISSING_CHECKOUT",
      sourceMode: "INTERNAL_AUTHORITATIVE",
      exceptionCode: "MISSING_CHECKOUT",
      netMinutes: undefined,
      leaveUnits: 0,
      calculationVersion: 1,
      computedAt: "2026-07-26T18:00:00Z",
    } satisfies AttendanceDay;

    expect(hasMissingPunch(missingDay)).toBe(true);
    expect(formatMinutes(missingDay.netMinutes)).toBe("Unresolved");
  });

  it("maps authorization and concurrency failures to distinct UX states", () => {
    expect(
      classifyWorkforceError(new ApiError("Denied", { status: 403 })),
    ).toBe("unauthorized");
    expect(
      classifyWorkforceError(new ApiError("Changed", { status: 409 })),
    ).toBe("conflict");
    expect(
      classifyWorkforceError(new ApiError("Unavailable", { status: 404 })),
    ).toBe("not-found");
    expect(
      classifyWorkforceError(new ApiError("Offline", { status: 0 })),
    ).toBe("unavailable");
  });

  it("validates leave dates without calculating entitlement in the browser", () => {
    expect(
      validateLeaveRequest({
        leaveTypeId: "leave-cl",
        startDate: "2026-08-03",
        endDate: "2026-08-01",
        units: 1,
        reason: "Trip",
      }),
    ).toEqual({
      endDate: "End date cannot be before start date.",
      reason: "Add a reason of at least 5 characters.",
    });
  });

  it("rejects units greater than the inclusive selected calendar span", () => {
    expect(
      validateLeaveRequest({
        leaveTypeId: "leave-cl",
        startDate: "2026-08-03",
        endDate: "2026-08-03",
        units: 1.5,
        reason: "Family commitment",
      }),
    ).toMatchObject({
      units: "Leave units cannot exceed the 1-day selected date span.",
    });

    expect(
      validateLeaveRequest({
        leaveTypeId: "leave-cl",
        startDate: "2026-08-03",
        endDate: "2026-08-04",
        units: 2,
        reason: "Family commitment",
      }),
    ).toEqual({});
  });

  it("requires evidence declaration and correction detail", () => {
    expect(
      validateRegularization({
        employeeId: "e1",
        workDate: "2026-07-26",
        reasonCode: "MISSED_CHECKOUT",
        narrative: "forgot",
        requestedOutcome: "CORRECT_PUNCH",
        declarationAccepted: false,
      }),
    ).toMatchObject({
      narrative: expect.any(String),
      declarationAccepted: expect.any(String),
    });
  });
});
