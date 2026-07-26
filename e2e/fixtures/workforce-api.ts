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
        permissions: ["catalog.read", "workforce.read", "attendance.write"],
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
      `/api/v1/workforce/employees/${employee.id}/leave-balances`
    ) {
      await json(route, leaveBalances);
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
