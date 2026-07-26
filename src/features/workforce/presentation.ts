import { ApiError } from "@/lib/api-client";

import type {
  AttendanceDay,
  CreateLeaveRequestInput,
  CreateRegularizationInput,
} from "./domain";

export type WorkforceErrorKind =
  | "unauthenticated"
  | "unauthorized"
  | "conflict"
  | "not-found"
  | "unavailable"
  | "unexpected";

export function classifyWorkforceError(error: unknown): WorkforceErrorKind {
  if (!(error instanceof ApiError)) return "unexpected";
  if (error.status === 0 || error.status >= 500) return "unavailable";
  if (error.status === 401) return "unauthenticated";
  if (error.status === 403) return "unauthorized";
  if (error.status === 404) return "not-found";
  if (error.status === 409 || error.status === 412) return "conflict";
  return "unexpected";
}

export function attendanceAction(day: AttendanceDay | undefined) {
  if (day && day.sourceMode !== "INTERNAL_AUTHORITATIVE") {
    return { action: "READ_ONLY" as const, label: "Externally managed" };
  }
  if (day?.exceptionCode === "OPEN_SESSION") {
    return { action: "CHECK_OUT" as const, label: "Check out" };
  }
  return { action: "CHECK_IN" as const, label: "Check in" };
}

export function hasMissingPunch(day: AttendanceDay) {
  return ["MISSING_CHECKOUT", "MISSING_CHECKIN"].includes(
    day.exceptionCode ?? "",
  );
}

export function formatMinutes(minutes: number | undefined) {
  if (minutes === undefined) return "Unresolved";
  const hours = Math.floor(minutes / 60);
  const remaining = minutes % 60;
  return `${hours}h ${remaining.toString().padStart(2, "0")}m`;
}

export type ValidationErrors<T> = Partial<Record<keyof T, string>>;

export function validateLeaveRequest(
  value: CreateLeaveRequestInput,
): ValidationErrors<CreateLeaveRequestInput> {
  const errors: ValidationErrors<CreateLeaveRequestInput> = {};
  if (!value.leaveTypeId.trim()) errors.leaveTypeId = "Select a leave type.";
  if (!value.startDate) errors.startDate = "Start date is required.";
  if (!value.endDate) errors.endDate = "End date is required.";
  if (value.startDate && value.endDate && value.startDate > value.endDate) {
    errors.endDate = "End date cannot be before start date.";
  }
  if (value.reason.trim().length < 5) {
    errors.reason = "Add a reason of at least 5 characters.";
  }
  if (!Number.isFinite(value.units) || value.units < 0.5) {
    errors.units = "Leave units must be at least 0.5.";
  } else if (
    value.startDate &&
    value.endDate &&
    value.startDate <= value.endDate
  ) {
    const inclusiveDays = inclusiveIsoDateSpan(
      value.startDate,
      value.endDate,
    );
    if (inclusiveDays !== undefined && value.units > inclusiveDays) {
      errors.units = `Leave units cannot exceed the ${inclusiveDays}-day selected date span.`;
    }
  }
  return errors;
}

function inclusiveIsoDateSpan(start: string, end: string) {
  const startParts = isoDateParts(start);
  const endParts = isoDateParts(end);
  if (!startParts || !endParts) return undefined;
  const startTime = Date.UTC(...startParts);
  const endTime = Date.UTC(...endParts);
  return Math.floor((endTime - startTime) / 86_400_000) + 1;
}

function isoDateParts(value: string): [number, number, number] | undefined {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return undefined;
  return [Number(match[1]), Number(match[2]) - 1, Number(match[3])];
}

export function validateRegularization(
  value: CreateRegularizationInput,
): ValidationErrors<CreateRegularizationInput> {
  const errors: ValidationErrors<CreateRegularizationInput> = {};
  if (!value.employeeId) {
    errors.employeeId = "Choose an employee.";
  }
  if (!value.workDate) errors.workDate = "Choose an attendance date.";
  if (!value.reasonCode) errors.reasonCode = "Select a reason.";
  if (value.narrative.trim().length < 10) {
    errors.narrative = "Explain the correction in at least 10 characters.";
  }
  if (!value.requestedOutcome) {
    errors.requestedOutcome = "Select the requested outcome.";
  }
  if (!value.declarationAccepted) {
    errors.declarationAccepted = "Confirm that the information is accurate.";
  }
  return errors;
}
