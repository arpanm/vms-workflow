export type EmploymentStatus =
  | "PREBOARDING"
  | "ACTIVE"
  | "ON_LEAVE"
  | "SUSPENDED"
  | "EXITED"
  | "ARCHIVED";

export type AttendanceSourceMode =
  | "INTERNAL_AUTHORITATIVE"
  | "GREYTHR_AUTHORITATIVE"
  | "HYBRID_TRANSITION"
  | "HISTORICAL_IMPORT";

export type OrganizationOption = {
  id: string;
  code: string;
  displayName: string;
};

export type EngagementOption = {
  id: string;
  name: string;
};

export type EngagementMonthOption = {
  id: string;
  engagementId: string;
  monthStartDate: string;
  state: string;
};

export type EmployeeSummary = {
  id: string;
  organizationId: string;
  employeeNumber: string;
  displayName: string;
  designation?: string;
  workEmail: string;
  employmentStatus: EmploymentStatus;
  activationStatus: "ENABLED" | "DISABLED";
  attendanceSourceMode: AttendanceSourceMode;
  validFrom: string;
  validTo?: string;
  version: number;
};

export type EmployeeDetail = EmployeeSummary & {
  joinDate: string;
  exitDate?: string;
  firstName: string;
  lastName: string;
};

export type CreateEmployeeInput = {
  organizationId: string;
  employeeNumber: string;
  firstName: string;
  lastName: string;
  displayName: string;
  workEmail: string;
  joinDate: string;
  designation?: string;
  attendanceSourceMode: AttendanceSourceMode;
};

export type Allocation = {
  id: string;
  engagementId: string;
  projectId: string;
  allocationPercent: number;
  roleOnProject?: string;
  validFrom: string;
  validTo?: string;
  status: "PLANNED" | "ACTIVE" | "TEMPORARILY_INACTIVE" | "ENDED";
};

export type LeaveBalance = {
  leaveTypeId: string;
  leaveTypeCode: string;
  leaveTypeName: string;
  paid: boolean;
  availableUnits: number;
};

export type LeaveRequest = {
  id: string;
  employeeId: string;
  leaveTypeId: string;
  startDate: string;
  endDate: string;
  units: number;
  reason: string;
  status:
    | "DRAFT"
    | "SUBMITTED"
    | "PENDING_APPROVAL"
    | "APPROVED"
    | "REJECTED"
    | "CHANGES_REQUESTED"
    | "CANCELLED";
  paidUnits: number;
  lwpUnits: number;
  createdAt: string;
  version: number;
};

export type EmployeeAlias = {
  id: string;
  employeeId: string;
  aliasType: "HRIS_ID" | "EMAIL" | "BADGE" | "LEGACY_ID" | "OTHER";
  aliasValue: string;
  validFrom: string;
  validTo?: string | null;
  status: "ACTIVE" | "ENDED";
  createdAt: string;
};

export type DeliverableAllocation = {
  id: string;
  employeeId: string;
  projectAllocationId: string;
  deliverableId: string;
  deliverableCode: string;
  validFrom: string;
  validTo?: string | null;
  allocationPercent: number;
  roleOnDeliverable?: string | null;
  status: "PLANNED" | "ACTIVE" | "ENDED";
};

export type CalendarVersion = {
  id: string;
  organizationId: string;
  name: string;
  timezone: string;
  version: number;
  validFrom: string;
  validTo?: string | null;
  expectedFullMinutes: number;
  expectedHalfMinutes: number;
  weekdays: Array<{
    isoWeekday: number;
    classification: "WORKING" | "WEEKLY_OFF" | "HALF_DAY_EXPECTED";
    expectedMinutes: number;
  }>;
  holidays: Array<{
    holidayDate: string;
    name: string;
    classification: "HOLIDAY" | "HALF_DAY_EXPECTED";
    expectedMinutes: number;
  }>;
};

export type ShiftPolicy = {
  id: string;
  organizationId: string;
  code: string;
  name: string;
  timezone: string;
  version: number;
  validFrom: string;
  validTo?: string | null;
  scheduledStartLocalTime: string;
  scheduledEndLocalTime: string;
  overnightCutoffLocalTime: string;
  expectedNetMinutes: number;
  maximumSessionMinutes: number;
  allowSplitSessions: boolean;
  minimumBreakMinutes: number;
  status: "PUBLISHED" | "SUPERSEDED";
  publishedAt: string;
};

export type ShiftAssignment = {
  id: string;
  employeeId: string;
  shiftPolicyVersionId: string;
  shiftPolicyCode: string;
  shiftPolicyName: string;
  shiftPolicyVersion: number;
  timezone: string;
  validFrom: string;
  validTo?: string | null;
  createdAt: string;
};

export type RosterReadiness = {
  engagementMonthId: string;
  monthStartDate: string;
  allocatedEmployeeCount: number;
  allocatedEmployeeDayCount: number;
  missingCalendarDayCount: number;
  missingShiftDayCount: number;
  missingEmployeeVersionDayCount: number;
  missingSourceModeDayCount: number;
  ready: boolean;
  issues: Array<{
    code: string;
    employeeId?: string | null;
    workDate?: string | null;
    message: string;
  }>;
};

export type RosterSnapshot = {
  id: string;
  engagementMonthId: string;
  version: number;
  supersedesId?: string | null;
  status: "FINALIZED";
  checksum: string;
  employeeCount: number;
  employeeDayCount: number;
  finalizedAt: string;
  finalizedBySubject: string;
  reason: string;
};

export type LeavePolicy = {
  id: string;
  organizationId: string;
  leaveTypeId: string;
  leaveTypeCode: string;
  leaveTypeName: string;
  version: number;
  status: "DRAFT" | "PUBLISHED" | "SUPERSEDED";
  validFrom: string;
  validTo?: string | null;
  approvalRequired: boolean;
  maximumUnitsPerRequest?: number | null;
  excessToLwp: boolean;
  cancellationAllowed: boolean;
  rules: Record<string, unknown>;
  publishedAt?: string | null;
};

export type LeaveDecision = {
  id: string;
  leaveRequestId: string;
  decision: "APPROVE" | "REJECT" | "CANCEL";
  expectedRequestVersion: number;
  requestStatus: "APPROVED" | "REJECTED" | "CANCELLED";
  requestVersion: number;
  paidUnits: number;
  lwpUnits: number;
  reason: string;
  decidedBySubject: string;
  decidedAt: string;
};

export type WorkforceCsvImport = {
  id: string;
  organizationId: string;
  importType:
    | "EMPLOYEE_ALIASES"
    | "DELIVERABLE_ALLOCATIONS"
    | "LEAVE_BALANCE_COMMANDS";
  fileName: string;
  checksum: string;
  status: "VALIDATED" | "IMPORTED" | "FAILED";
  rowCount: number;
  importedCount: number;
  errors: Array<{
    rowNumber: number;
    fieldName: string;
    errorCode: string;
    message: string;
  }>;
  replay: boolean;
};

export type AttendanceSession = {
  id: string;
  checkedInAt: string;
  checkedOutAt?: string;
  netWorkedMinutes?: number;
  status: "OPEN" | "CLOSED" | "INVALID" | "SUPERSEDED";
};

export type AttendanceDay = {
  id: string | null;
  employeeId: string;
  workDate: string;
  expectedClassification: string;
  expectedMinutes: number;
  netMinutes?: number;
  leaveUnits: number;
  leaveTypeCode?: string;
  finalStatus: string;
  sourceMode: AttendanceSourceMode;
  exceptionCode?: string;
  calculationVersion: number;
  computedAt: string;
};

export type Punch = {
  id: string;
  employeeId: string;
  eventType: "CHECK_IN" | "CHECK_OUT" | "BREAK_START" | "BREAK_END";
  occurredAt: string;
  workDate: string;
  source: string;
  idempotencyKey: string;
  sessionId: string;
  sessionStatus: AttendanceSession["status"];
  netMinutes?: number;
};

export type RegularizationRequest = {
  id: string;
  employeeId: string;
  workDate: string;
  reasonCode: string;
  narrative: string;
  requestedOutcome: string;
  status:
    | "DRAFT"
    | "SUBMITTED"
    | "UNDER_REVIEW"
    | "INFO_REQUESTED"
    | "APPROVED"
    | "REJECTED"
    | "CANCELLED"
    | "SUPERSEDED";
  createdAt: string;
};

export type MonthlyAttendanceSnapshot = {
  id: string;
  engagementMonthId: string;
  status: "OPEN" | "BLOCKED" | "READY" | "CLOSED" | "REOPENED";
  version: number;
  supersedesId?: string;
  closedAt?: string;
  reopenedAt?: string;
  checksum?: string;
  dayCount: number;
};

export type CreateLeaveRequestInput = {
  leaveTypeId: string;
  startDate: string;
  endDate: string;
  units: number;
  reason: string;
};

export type CreateRegularizationInput = {
  employeeId: string;
  workDate: string;
  reasonCode: string;
  narrative: string;
  requestedOutcome: string;
  declarationAccepted: boolean;
};
