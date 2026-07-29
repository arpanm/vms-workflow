import type { Page, Route } from "@playwright/test";

type AttendanceScenario = "OPEN_SESSION" | "MISSING_CHECKOUT";

type RecordedMutation = {
  method: string;
  path: string;
  body: Record<string, unknown>;
};

type RecordedRequest = {
  method: string;
  path: string;
  search: string;
};

const organization = {
  id: "org-arrowfoundry",
  code: "ARROW",
  displayName: "ArrowFoundry",
};

const employee = {
  id: "employee-ananya",
  organizationId: organization.id,
  employeeNumber: "AF-1001",
  displayName: "Ananya Rao",
  workEmail: "ananya.rao@example.invalid",
  employmentStatus: "ACTIVE",
  activationStatus: "ENABLED",
  attendanceSourceMode: "INTERNAL_AUTHORITATIVE",
  validFrom: "2026-06-01",
  version: 3,
  joinDate: "2026-06-01",
  firstName: "Ananya",
  lastName: "Rao",
};

const leaveBalances = [
  {
    leaveTypeId: "leave-casual",
    leaveTypeCode: "CL",
    leaveTypeName: "Casual leave",
    paid: true,
    availableUnits: 1,
  },
];

function localDate(day?: number) {
  const date = new Date();
  if (day !== undefined) date.setDate(day);
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const dateOfMonth = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${dateOfMonth}`;
}

function attendanceDay(scenario: AttendanceScenario) {
  const missingCheckout = scenario === "MISSING_CHECKOUT";
  return {
    id: `attendance-${scenario.toLowerCase()}`,
    employeeId: employee.id,
    workDate: missingCheckout ? localDate(5) : localDate(),
    expectedClassification: "WORKING",
    expectedMinutes: 540,
    netMinutes: 0,
    leaveUnits: 0,
    finalStatus: missingCheckout
      ? "MISSING_CHECKOUT_EXCEPTION"
      : "OPEN_SESSION",
    sourceMode: "INTERNAL_AUTHORITATIVE",
    exceptionCode: scenario,
    calculationVersion: 2,
    computedAt: `${localDate()}T08:30:00Z`,
  };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify(body),
  });
}

export async function mockWorkforceApi(
  page: Page,
  options: { attendanceScenario?: AttendanceScenario } = {},
) {
  const scenario = options.attendanceScenario ?? "OPEN_SESSION";
  const mutations: RecordedMutation[] = [];
  const requests: RecordedRequest[] = [];
  let leaveRequests: unknown[] = [];
  let regularizations: unknown[] = [];
  let aliases: unknown[] = [];
  let deliverableAllocations: unknown[] = [];
  let calendars: unknown[] = [];
  let shiftPolicies: unknown[] = [];
  let shiftAssignments: unknown[] = [];
  let rosterSnapshots: unknown[] = [];
  const leavePolicies = [
    {
      id: "policy-casual-v1",
      organizationId: organization.id,
      leaveTypeId: "leave-casual",
      leaveTypeCode: "CL",
      leaveTypeName: "Casual leave",
      version: 1,
      status: "PUBLISHED",
      validFrom: "2026-06-01",
      approvalRequired: true,
      maximumUnitsPerRequest: 30,
      excessToLwp: true,
      cancellationAllowed: true,
      rules: { reviewerRequired: true },
    },
  ];
  let administrationLeaveRequests: unknown[] = [
    {
      id: "leave-review-1",
      employeeId: employee.id,
      leaveTypeId: "leave-casual",
      startDate: "2026-08-11",
      endDate: "2026-08-11",
      units: 1,
      paidUnits: 1,
      lwpUnits: 0,
      reason: "Medical appointment",
      status: "SUBMITTED",
      idempotencyKey: "leave-review-fixture",
      createdAt: "2026-07-29T04:00:00Z",
      version: 0,
    },
  ];
  let administrationRegularizations: unknown[] = [
    {
      id: "regularization-review-1",
      employeeId: employee.id,
      workDate: "2026-07-28",
      reasonCode: "MISSED_CHECK_OUT",
      narrative: "VPN logs confirm end of shift.",
      requestedOutcome: "CORRECT_PUNCH",
      idempotencyKey: "regularization-review-fixture",
      status: "SUBMITTED",
      createdAt: "2026-07-29T04:15:00Z",
    },
  ];

  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();
    requests.push({ method, path, search: url.search });

    if (path === "/api/v1/me") {
      await json(route, {
        id: "demo-user",
        email: "demo@example.invalid",
        displayName: "Demo User",
        organizationIds: [organization.id],
        permissions: [
          "catalog.read",
          "workforce.read",
          "workforce.manage",
          "attendance.write",
          "attendance.review",
        ],
      });
      return;
    }
    if (path === "/api/v1/organizations") {
      await json(route, [organization]);
      return;
    }
    if (path === "/api/v1/engagements") {
      await json(route, [
        { id: "eng-reliance", name: "ArrowFoundry × Reliance" },
      ]);
      return;
    }
    if (path === "/api/v1/engagement-months") {
      await json(route, [
        {
          id: "month-2026-06",
          engagementId: "eng-reliance",
          monthStartDate: "2026-06-01",
          state: "REOPENED",
        },
      ]);
      return;
    }
    if (path === "/api/v1/workforce/employees/me") {
      await json(route, employee);
      return;
    }
    if (
      path === "/api/v1/workforce/employees" &&
      method === "GET"
    ) {
      await json(route, [employee]);
      return;
    }
    if (path === `/api/v1/workforce/employees/${employee.id}`) {
      await json(route, employee);
      return;
    }
    if (
      path === `/api/v1/workforce/employees/${employee.id}/aliases`
    ) {
      if (method === "POST") {
        const body = request.postDataJSON() as Record<string, unknown>;
        mutations.push({ method, path, body });
        const alias = {
          id: "alias-new",
          employeeId: employee.id,
          ...body,
          status: "ACTIVE",
          createdAt: "2026-07-29T05:00:00Z",
        };
        aliases = [alias];
        await json(route, alias, 201);
        return;
      }
      await json(route, aliases);
      return;
    }
    if (
      path ===
      `/api/v1/workforce/employees/${employee.id}/allocations`
    ) {
      await json(route, [
        {
          id: "allocation-nam",
          engagementId: "eng-reliance",
          projectId: "project-nam",
          allocationPercent: 60,
          roleOnProject: "Engineer",
          validFrom: "2026-06-01",
          status: "ACTIVE",
        },
      ]);
      return;
    }
    if (
      path ===
      `/api/v1/workforce/employees/${employee.id}/deliverable-allocations`
    ) {
      if (method === "POST") {
        const body = request.postDataJSON() as Record<string, unknown>;
        mutations.push({ method, path, body });
        const allocation = {
          id: "deliverable-allocation-new",
          employeeId: employee.id,
          deliverableCode: "DEL-101",
          status: "ACTIVE",
          ...body,
        };
        deliverableAllocations = [allocation];
        await json(route, allocation, 201);
        return;
      }
      await json(route, deliverableAllocations);
      return;
    }
    if (
      path ===
      `/api/v1/workforce/employees/${employee.id}/leave-balances`
    ) {
      await json(route, leaveBalances);
      return;
    }
    if (
      path ===
      `/api/v1/workforce/employees/${employee.id}/leave-balance-commands` &&
      method === "POST"
    ) {
      const body = request.postDataJSON() as Record<string, unknown>;
      mutations.push({ method, path, body });
      await json(route, {
        id: "balance-command-new",
        employeeId: employee.id,
        ...body,
        createdAt: "2026-07-29T05:05:00Z",
      }, 201);
      return;
    }
    if (
      path ===
      `/api/v1/workforce/organizations/${organization.id}/calendars`
    ) {
      if (method === "POST") {
        const body = request.postDataJSON() as Record<string, unknown>;
        mutations.push({ method, path, body });
        const calendar = {
          id: "calendar-v1",
          organizationId: organization.id,
          version: 1,
          ...body,
        };
        calendars = [calendar];
        await json(route, calendar, 201);
        return;
      }
      await json(route, calendars);
      return;
    }
    if (
      path ===
      `/api/v1/workforce/organizations/${organization.id}/shift-policies`
    ) {
      if (method === "POST") {
        const body = request.postDataJSON() as Record<string, unknown>;
        mutations.push({ method, path, body });
        const policy = {
          id: "shift-policy-v1",
          organizationId: organization.id,
          version: 1,
          status: "PUBLISHED",
          publishedAt: "2026-07-29T08:00:00Z",
          ...body,
        };
        shiftPolicies = [policy];
        await json(route, policy, 201);
        return;
      }
      await json(route, shiftPolicies);
      return;
    }
    if (
      path ===
      `/api/v1/workforce/employees/${employee.id}/shift-assignments`
    ) {
      if (method === "POST") {
        const body = request.postDataJSON() as Record<string, unknown>;
        mutations.push({ method, path, body });
        const assignment = {
          id: "shift-assignment-v1",
          employeeId: employee.id,
          shiftPolicyCode: "STANDARD_DAY",
          shiftPolicyName: "Standard day shift",
          shiftPolicyVersion: 1,
          timezone: "Asia/Kolkata",
          createdAt: "2026-07-29T08:01:00Z",
          ...body,
        };
        shiftAssignments = [assignment];
        await json(route, assignment, 201);
        return;
      }
      await json(route, shiftAssignments);
      return;
    }
    if (
      path ===
      "/api/v1/workforce/engagement-months/month-2026-06/roster-readiness"
    ) {
      await json(route, {
        engagementMonthId: "month-2026-06",
        monthStartDate: "2026-06-01",
        allocatedEmployeeCount: 1,
        allocatedEmployeeDayCount: 30,
        missingCalendarDayCount: 0,
        missingShiftDayCount: 0,
        missingEmployeeVersionDayCount: 0,
        missingSourceModeDayCount: 0,
        ready: true,
        issues: [],
      });
      return;
    }
    if (
      path ===
      "/api/v1/workforce/engagement-months/month-2026-06/roster-snapshots"
    ) {
      if (method === "POST") {
        const body = request.postDataJSON() as Record<string, unknown>;
        mutations.push({ method, path, body });
        const snapshot = {
          id: "roster-snapshot-v1",
          engagementMonthId: "month-2026-06",
          version: 1,
          status: "FINALIZED",
          checksum: "a".repeat(64),
          employeeCount: 1,
          employeeDayCount: 30,
          finalizedAt: "2026-07-29T08:02:00Z",
          finalizedBySubject: "demo-user",
          ...body,
        };
        rosterSnapshots = [snapshot];
        await json(route, snapshot, 201);
        return;
      }
      await json(route, rosterSnapshots);
      return;
    }
    if (
      path ===
      `/api/v1/workforce/organizations/${organization.id}/leave-policies`
    ) {
      if (method === "POST") {
        const body = request.postDataJSON() as Record<string, unknown>;
        mutations.push({ method, path, body });
        await json(route, {
          ...leavePolicies[0],
          id: "policy-casual-v2",
          version: 2,
          ...body,
        }, 201);
        return;
      }
      await json(route, leavePolicies);
      return;
    }
    if (
      path === "/api/v1/workforce/leave-request-inbox" &&
      method === "GET"
    ) {
      await json(route, administrationLeaveRequests);
      return;
    }
    if (
      path === "/api/v1/workforce/leave-requests/leave-review-1/decisions" &&
      method === "POST"
    ) {
      const body = request.postDataJSON() as Record<string, unknown>;
      mutations.push({ method, path, body });
      administrationLeaveRequests = [];
      await json(route, {
        id: "leave-decision-new",
        leaveRequestId: "leave-review-1",
        decision: body.decision,
        expectedRequestVersion: body.expectedVersion,
        requestStatus: "APPROVED",
        requestVersion: 1,
        paidUnits: 1,
        lwpUnits: 0,
        reason: body.reason,
        decidedBySubject: "demo-user",
        decidedAt: "2026-07-29T05:10:00Z",
      }, 201);
      return;
    }
    if (
      path === "/api/v1/workforce/regularization-inbox" &&
      method === "GET"
    ) {
      await json(route, administrationRegularizations);
      return;
    }
    if (
      path ===
        "/api/v1/attendance/regularizations/regularization-review-1/decisions" &&
      method === "POST"
    ) {
      const body = request.postDataJSON() as Record<string, unknown>;
      mutations.push({ method, path, body });
      administrationRegularizations = [];
      await json(route, { id: "regularization-decision-new", ...body }, 201);
      return;
    }
    if (
      path ===
        `/api/v1/workforce/organizations/${organization.id}/imports` &&
      method === "POST"
    ) {
      const body = request.postDataJSON() as Record<string, unknown>;
      mutations.push({ method, path, body });
      await json(route, {
        id: "workforce-import-new",
        organizationId: organization.id,
        importType: body.importType,
        fileName: body.fileName,
        checksum: "fixture-checksum",
        status: body.apply ? "IMPORTED" : "VALIDATED",
        rowCount: 1,
        importedCount: body.apply ? 1 : 0,
        errors: [],
        replay: false,
      }, 201);
      return;
    }
    if (
      path ===
      `/api/v1/workforce/employees/${employee.id}/leave-requests`
    ) {
      if (method === "POST") {
        const body = request.postDataJSON() as Record<string, unknown>;
        mutations.push({ method, path, body });
        leaveRequests = [
          {
            id: "leave-request-new",
            employeeId: employee.id,
            leaveTypeId: body.leaveTypeId,
            startDate: body.startDate,
            endDate: body.endDate,
            units: body.units,
            reason: body.reason,
            status: "APPROVED",
            paidUnits: 1,
            lwpUnits: 0.5,
            createdAt: "2026-07-26T04:00:00Z",
          },
        ];
        await json(route, leaveRequests[0], 201);
        return;
      }
      await json(route, leaveRequests);
      return;
    }
    if (path === "/api/v1/attendance/days") {
      await json(route, [attendanceDay(scenario)]);
      return;
    }
    if (path === "/api/v1/attendance/punches" && method === "POST") {
      const body = request.postDataJSON() as Record<string, unknown>;
      mutations.push({ method, path, body });
      await json(
        route,
        {
          id: "punch-checkout",
          employeeId: employee.id,
          eventType: body.eventType,
          occurredAt: "2026-07-26T12:30:00Z",
          workDate: localDate(),
          source: "INTERNAL",
          idempotencyKey: body.idempotencyKey,
          sessionId: "session-1",
          sessionStatus: "CLOSED",
          netMinutes: 480,
        },
        201,
      );
      return;
    }
    if (path === "/api/v1/attendance/regularizations") {
      if (method === "POST") {
        const body = request.postDataJSON() as Record<string, unknown>;
        mutations.push({ method, path, body });
        regularizations = [
          {
            id: "regularization-new",
            employeeId: employee.id,
            workDate: body.workDate,
            reasonCode: body.reasonCode,
            narrative: body.narrative,
            requestedOutcome: body.requestedOutcome,
            status: "SUBMITTED",
            createdAt: "2026-07-26T04:15:00Z",
          },
        ];
        await json(route, regularizations[0], 201);
        return;
      }
      await json(route, regularizations);
      return;
    }
    if (path === "/api/v1/attendance/month-snapshots") {
      await json(route, [
        {
          id: "snapshot-v1",
          engagementMonthId: "month-2026-06",
          status: "CLOSED",
          version: 1,
          closedAt: "2026-07-02T10:00:00Z",
          checksum: "sha256:v1",
          dayCount: 22,
        },
        {
          id: "snapshot-v2",
          engagementMonthId: "month-2026-06",
          status: "REOPENED",
          version: 2,
          supersedesId: "snapshot-v1",
          reopenedAt: "2026-07-03T09:00:00Z",
          checksum: "sha256:v2",
          dayCount: 22,
        },
      ]);
      return;
    }

    await json(route, { message: `No workforce E2E fixture for ${path}` }, 404);
  });

  return { mutations, requests };
}

export const workforceFixture = {
  employee,
  exceptionDate: localDate(5),
};
