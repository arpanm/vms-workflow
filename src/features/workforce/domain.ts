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
  eventType: "CHECK_IN" | "CHECK_OUT";
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
