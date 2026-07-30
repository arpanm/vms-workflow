import { expect, test, type APIResponse } from "@playwright/test";
import { execFileSync } from "node:child_process";

import "./fixtures/quality-gates";

const monthId = "00000000-0000-0000-0000-000000000602";
const organizationId = "00000000-0000-0000-0000-000000000101";
const engagementId = "00000000-0000-0000-0000-000000000401";
const projectId = "00000000-0000-0000-0000-000000000501";
const leaveTypeId = "00000000-0000-0000-0000-000000000921";
const calendarId = "00000000-0000-0000-0000-000000000901";
const fixtureEmployeeId = "00000000-0000-0000-0000-000000000801";
const browserTokenKey = "__vms_system_e2e_access_token";
const postgresContainer = requiredEnvironment("VMS_E2E_POSTGRES_CONTAINER");

const tokens = {
  vendor: requiredEnvironment("VMS_E2E_TOKEN_USER_ARROW"),
  employee: requiredEnvironment("VMS_E2E_TOKEN_USER_E2E_EMPLOYEE"),
};

test("[E2E-01] new employee reaches exact closed monthly attendance evidence in the real system", async ({
  page,
  request,
}) => {
  const employee = await json(await request.post("/api/v1/workforce/employees", {
    headers: authorization(tokens.vendor),
    data: {
      organizationId,
      employeeNumber: "AF-E2E-074",
      firstName: "End",
      lastName: "Toend",
      displayName: "End Toend",
      workEmail: "e2e.employee@arrowfoundry.example",
      joinDate: "2026-07-01",
      designation: "System Test Engineer",
      attendanceSourceMode: "INTERNAL_AUTHORITATIVE",
      userProfileId: "72000000-0000-0000-0000-000000000001",
    },
  }), 201);
  const createdEmployeeId = String(employee.id);
  expect(JSON.stringify(employee)).not.toMatch(
    /salary|compensation|bankAccount|bankDetails|taxIdentifier/i,
  );

  const allocation = await json(await request.post(
    `/api/v1/workforce/employees/${createdEmployeeId}/allocations`,
    {
      headers: authorization(tokens.vendor),
      data: {
        engagementId,
        projectId,
        validFrom: "2026-07-01",
        validTo: "2026-07-31",
        allocationPercent: 100,
        roleOnProject: "System Test Engineer",
      },
    },
  ), 201);
  expect(allocation).toMatchObject({
    employeeId: createdEmployeeId,
    engagementId,
    projectId,
    allocationPercent: 100,
    status: "ACTIVE",
  });

  const policy = await json(await request.post(
    `/api/v1/workforce/employees/${createdEmployeeId}/policy-assignments`,
    {
      headers: authorization(tokens.vendor),
      data: {
        calendarVersionId: calendarId,
        leaveTypeId,
        openingUnits: 2,
        effectiveFrom: "2026-07-01",
        idempotencyKey: "e2e-01-policy",
        reason: "Assign the approved calendar and leave policy",
      },
    },
  ), 201);
  expect(policy).toMatchObject({
    employeeId: createdEmployeeId,
    calendarVersionId: calendarId,
    leaveTypeId,
    openingUnits: 2,
  });

  const leave = await json(await request.post(
    `/api/v1/workforce/employees/${createdEmployeeId}/leave-requests`,
    {
      headers: authorization(tokens.employee),
      data: {
        leaveTypeId,
        startDate: "2026-07-30",
        endDate: "2026-07-30",
        units: 1,
        reason: "Planned real-system leave",
        idempotencyKey: "e2e-01-leave",
      },
    },
  ), 201);
  expect(leave).toMatchObject({ paidUnits: 1, lwpUnits: 0, status: "APPROVED" });

  await json(await request.post("/api/v1/attendance/punches", {
    headers: authorization(tokens.employee),
    data: {
      employeeId: createdEmployeeId,
      eventType: "CHECK_IN",
      idempotencyKey: "e2e-01-check-in",
    },
  }), 201);
  const checkout = await json(await request.post("/api/v1/attendance/punches", {
    headers: authorization(tokens.employee),
    data: {
      employeeId: createdEmployeeId,
      eventType: "CHECK_OUT",
      idempotencyKey: "e2e-01-check-out",
    },
  }), 201);
  expect(checkout.sessionStatus).toBe("CLOSED");
  expect(checkout.workDate).toBe("2026-07-29");
  const checkoutReplay = await json(await request.post(
    "/api/v1/attendance/punches",
    {
      headers: authorization(tokens.employee),
      data: {
        employeeId: createdEmployeeId,
        eventType: "CHECK_OUT",
        idempotencyKey: "e2e-01-check-out",
      },
    },
  ), 201);
  expect(checkoutReplay).toEqual(checkout);
  expect(queryDatabase(`
    SELECT COUNT(*) FROM attendance_sessions
    WHERE employee_id = '${createdEmployeeId}'::uuid
      AND status = 'CLOSED'
      AND check_out_at > check_in_at
      AND net_minutes = 0;
  `)).toBe("1");

  const regularization = await json(await request.post(
    "/api/v1/attendance/regularizations",
    {
      headers: authorization(tokens.employee),
      data: {
        employeeId: createdEmployeeId,
        workDate: String(checkout.workDate),
        reasonCode: "SYSTEM_TEST_SHORT_SESSION",
        narrative: "Explicit correction request for the short synthetic session",
        requestedOutcome: "FULL_DAY_PRESENT",
        idempotencyKey: "e2e-01-regularization",
      },
    },
  ), 201);
  expect(regularization.status).toBe("SUBMITTED");

  const decision = await json(await request.post(
    `/api/v1/attendance/regularizations/${regularization.id}/decisions`,
    {
      headers: authorization(tokens.vendor),
      data: {
        decision: "APPROVE",
        adjustedNetMinutes: 540,
        reasoning: "Reviewed synthetic punches and approved full-day correction",
      },
    },
  ), 201);
  expect(decision).toMatchObject({
    regularizationId: regularization.id,
    decision: "APPROVE",
    adjustedNetMinutes: 540,
  });

  const shiftPolicy = await json(await request.post(
    `/api/v1/workforce/organizations/${organizationId}/shift-policies`,
    {
      headers: authorization(tokens.vendor),
      data: {
        code: "E2E01-DAY",
        name: "E2E-01 governed day shift",
        timezone: "Asia/Kolkata",
        validFrom: "2026-07-01",
        validTo: "2026-07-31",
        scheduledStartLocalTime: "09:00:00",
        scheduledEndLocalTime: "18:30:00",
        overnightCutoffLocalTime: "07:00:00",
        expectedNetMinutes: 540,
        maximumSessionMinutes: 720,
        allowSplitSessions: true,
        minimumBreakMinutes: 30,
      },
    },
  ), 201);
  for (const employeeId of [fixtureEmployeeId, createdEmployeeId]) {
    await json(await request.post(
      `/api/v1/workforce/employees/${employeeId}/shift-assignments`,
      {
        headers: authorization(tokens.vendor),
        data: {
          shiftPolicyVersionId: shiftPolicy.id,
          validFrom: "2026-07-01",
          validTo: "2026-07-31",
        },
      },
    ), 201);
  }
  const rosterReadiness = await json(await request.get(
    `/api/v1/workforce/engagement-months/${monthId}/roster-readiness`,
    { headers: authorization(tokens.vendor) },
  ), 200);
  expect(rosterReadiness).toMatchObject({
    ready: true,
    allocatedEmployeeCount: 2,
    missingCalendarDayCount: 0,
    missingShiftDayCount: 0,
    missingEmployeeVersionDayCount: 0,
    missingSourceModeDayCount: 0,
  });
  const roster = await json(await request.post(
    `/api/v1/workforce/engagement-months/${monthId}/roster-snapshots`,
    {
      headers: authorization(tokens.vendor),
      data: {
        reason: "E2E-01 verified complete allocation, calendar, source and shift coverage",
      },
    },
  ), 201);
  expect(roster).toMatchObject({
    status: "FINALIZED",
    employeeCount: 2,
  });
  expect(roster.checksum).toMatch(/^[0-9a-f]{64}$/);

  const snapshot = await json(await request.post(
    "/api/v1/attendance/month-snapshots",
    {
      headers: authorization(tokens.vendor),
      data: { engagementMonthId: monthId },
    },
  ), 201);
  expect(snapshot).toMatchObject({ status: "CLOSED", version: 1 });
  expect(snapshot.checksum).toMatch(/^[0-9a-f]{64}$/);

  const days = await json(await request.get(
    `/api/v1/attendance/days?employeeId=${createdEmployeeId}`
      + `&from=${checkout.workDate}&to=2026-07-30`,
    { headers: authorization(tokens.employee) },
  ), 200);
  const corrected = days.find(
    (day: { workDate: string }) => day.workDate === checkout.workDate,
  );
  expect(corrected).toMatchObject({
    netMinutes: 540,
    finalStatus: "PRESENT_FULL_DAY",
    sourceMode: "INTERNAL_AUTHORITATIVE",
  });
  const leaveDay = days.find(
    (day: { workDate: string }) => day.workDate === "2026-07-30",
  );
  expect(leaveDay).toMatchObject({
    leaveUnits: 1,
    leaveTypeCode: "CL",
    finalStatus: "PAID_LEAVE",
    sourceMode: "INTERNAL_AUTHORITATIVE",
  });

  expect(queryDatabase(`
    SELECT COUNT(*) FROM attendance_snapshot_days
    WHERE snapshot_id = '${snapshot.id}'::uuid
      AND employee_id = '${createdEmployeeId}'::uuid;
  `)).toBe("31");
  expect(queryDatabase(`
    SELECT COUNT(*) FROM attendance_snapshot_days
    WHERE snapshot_id = '${snapshot.id}'::uuid
      AND employee_id = '${createdEmployeeId}'::uuid
      AND work_date = '${checkout.workDate}'::date
      AND net_minutes = 540
      AND final_status = 'PRESENT_FULL_DAY'
      AND source_mode = 'INTERNAL_AUTHORITATIVE';
  `)).toBe("1");
  expect(queryDatabase(`
    SELECT COUNT(*) FROM employee_project_allocations
    WHERE employee_id = '${createdEmployeeId}'::uuid
      AND engagement_id = '${engagementId}'::uuid
      AND project_id = '${projectId}'::uuid
      AND allocation_percent = 100
      AND status = 'ACTIVE';
  `)).toBe("1");
  expect(queryDatabase(`
    SELECT COUNT(*) FROM attendance_regularization_decisions decision
    JOIN attendance_regularization_adjustments adjustment
      ON adjustment.regularization_id = decision.regularization_id
    JOIN employee_policy_assignment_commands policy
      ON policy.employee_id = adjustment.employee_id
    WHERE decision.regularization_id = '${regularization.id}'::uuid
      AND decision.decision = 'APPROVE'
      AND adjustment.adjusted_net_minutes = 540
      AND adjustment.adjustment_version = 1
      AND adjustment.supersedes_adjustment_id IS NULL
      AND policy.idempotency_key = 'e2e-01-policy';
  `)).toBe("1");
  expect(queryDatabase(`
    SELECT COUNT(*) FROM workforce_audit_events
    WHERE employee_id = '${createdEmployeeId}'::uuid
      AND (
        (action = 'EMPLOYEE_CREATED' AND actor_subject = 'user-arrow')
        OR (action = 'POLICY_ASSIGNED' AND actor_subject = 'user-arrow')
        OR (action = 'CHECK_IN' AND actor_subject = 'user-e2e-employee')
        OR (action = 'CHECK_OUT' AND actor_subject = 'user-e2e-employee')
        OR (
          action = 'REGULARIZATION_SUBMITTED'
          AND actor_subject = 'user-e2e-employee'
        )
        OR (
          action = 'REGULARIZATION_APPROVED'
          AND actor_subject = 'user-arrow'
        )
      );
  `)).toBe("6");
  expect(queryDatabase(`
    SELECT COUNT(*) FROM workforce_audit_events
    WHERE object_type = 'ATTENDANCE_SNAPSHOT'
      AND object_id = '${snapshot.id}'::uuid
      AND action = 'SNAPSHOT_CLOSED'
      AND actor_subject = 'user-arrow';
  `)).toBe("1");

  await page.addInitScript(
    ({ key, token }) => window.sessionStorage.setItem(key, token),
    { key: browserTokenKey, token: tokens.vendor },
  );
  await page.goto("/workforce/employees");
  await expect(
    page.getByRole("heading", { name: "Employee directory" }),
  ).toBeVisible();
  await expect(page.getByText("End Toend")).toBeVisible();
});

function requiredEnvironment(name: string) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required for F07 system E2E.`);
  return value;
}

function authorization(token: string) {
  return { Authorization: `Bearer ${token}` };
}

async function json(response: APIResponse, expectedStatus: number) {
  const body = await response.text();
  expect(response.status(), body).toBe(expectedStatus);
  return body ? JSON.parse(body) : null;
}

function queryDatabase(sql: string) {
  return execFileSync(
    "docker",
    [
      "exec", postgresContainer, "psql", "--no-psqlrc", "--tuples-only",
      "--no-align", "--username", "vms", "--dbname", "vms_workflow",
      "--set", "ON_ERROR_STOP=1", "--command", sql,
    ],
    { encoding: "utf8", timeout: 15_000 },
  ).trim();
}
