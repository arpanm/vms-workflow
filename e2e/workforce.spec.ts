import { expect, test } from "@playwright/test";

import {
  mockWorkforceApi,
  workforceFixture,
} from "./fixtures/workforce-api";
import "./fixtures/quality-gates";

test("[E2E-F02-001] employee roster, profile and allocation exclude compensation data", async ({
  page,
}) => {
  await mockWorkforceApi(page);
  await page.goto("/workforce/employees");

  await expect(
    page.getByRole("heading", { name: "Employee directory" }),
  ).toBeVisible();
  await expect(page.getByRole("link", { name: "Ananya Rao" })).toBeVisible();
  await expect(page.getByRole("columnheader", { name: "Employment" })).toBeVisible();
  await expect(
    page.getByRole("columnheader", { name: /salary|payroll|rate|ctc/i }),
  ).toHaveCount(0);

  await page.getByRole("link", { name: "Ananya Rao" }).click();
  await expect(page.getByRole("heading", { name: "Ananya Rao" })).toBeVisible();
  await expect(page.getByText("AF-1001")).toBeVisible();
  await expect(page.getByText("Project project-nam · Engineer")).toBeVisible();
  await expect(page.getByText("60%")).toBeVisible();
  for (const forbiddenLabel of ["Salary", "Payroll", "Rate", "CTC", "Markup"]) {
    await expect(page.getByText(forbiddenLabel, { exact: true })).toHaveCount(0);
  }
});

test("[E2E-F02-002] open attendance session enables an explicit checkout", async ({
  page,
}) => {
  const api = await mockWorkforceApi(page, {
    attendanceScenario: "OPEN_SESSION",
  });
  await page.goto("/attendance/today");

  await expect(page.getByText("Ananya Rao")).toBeVisible();
  await expect(page.getByText("AF-1001")).toBeVisible();
  await expect(page.getByText("Session in progress")).toBeVisible();
  await expect(page.getByText("worked duration remains unresolved")).toBeVisible();
  await expect(page.getByRole("button", { name: "Check in" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "Check out" })).toBeEnabled();
  await page.getByRole("button", { name: "Start break" }).click();
  await page.getByRole("button", { name: "End break" }).click();
  await page.getByRole("button", { name: "Check out" }).click();

  await expect
    .poll(() =>
      api.mutations
        .filter(
          (mutation) => mutation.path === "/api/v1/attendance/punches",
        )
        .map((mutation) => mutation.body.eventType),
    )
    .toEqual(["BREAK_START", "BREAK_END", "CHECK_OUT"]);
  await expect
    .poll(() =>
      api.mutations.find(
        (mutation) =>
          mutation.path === "/api/v1/attendance/punches" &&
          mutation.body.eventType === "CHECK_OUT",
      ),
    )
    .toMatchObject({
      method: "POST",
      body: {
        employeeId: workforceFixture.employee.id,
        eventType: "CHECK_OUT",
      },
    });
});

test("[E2E-F02-003] missing checkout remains unresolved and is never synthesized", async ({
  page,
}) => {
  await mockWorkforceApi(page, {
    attendanceScenario: "MISSING_CHECKOUT",
  });
  await page.goto("/attendance/today");

  await expect(page.getByText("Ananya Rao")).toBeVisible();
  await expect(page.getByText("Missing punch requires resolution")).toBeVisible();
  await expect(
    page.getByText("no checkout or duration has been synthesized"),
  ).toBeVisible();
  await expect(
    page.getByText("Recorded").locator("..").getByText("Unresolved"),
  ).toBeVisible();
});

test("[E2E-F02-004] leave submission renders the API-returned paid and LWP split", async ({
  page,
}) => {
  const api = await mockWorkforceApi(page);
  await page.goto("/attendance/leave");

  await expect(page.getByText("Ananya Rao")).toBeVisible();
  await expect(page.getByRole("combobox", { name: "Employee" })).toHaveCount(0);
  await page.getByRole("combobox", { name: "Leave type" }).click();
  await page.getByRole("option", { name: /Casual leave/ }).click();
  const dates = page.locator('input[type="date"]');
  await dates.nth(0).fill("2026-08-03");
  await dates.nth(1).fill("2026-08-03");
  await page.locator('input[type="number"]').fill("1.5");
  const reason = page.locator("textarea");
  await reason.fill("Family appointment");
  await expect(reason).toHaveValue("Family appointment");
  await page.getByRole("button", { name: "Submit request" }).click();

  await expect(
    page.getByText(
      "Leave units cannot exceed the 1-day selected date span.",
    ),
  ).toBeVisible();
  expect(
    api.mutations.filter((mutation) =>
      mutation.path.endsWith("/leave-requests"),
    ),
  ).toEqual([]);
  await expect(reason).toHaveValue("Family appointment");

  await dates.nth(1).fill("2026-08-04");
  await page.getByRole("button", { name: "Submit request" }).click();

  await expect(page.getByText("paid 1, LWP 0.5")).toBeVisible();
  await expect(page.getByText("approved", { exact: true })).toBeVisible();
  await expect
    .poll(() =>
      api.mutations.find((mutation) =>
        mutation.path.endsWith("/leave-requests"),
      ),
    )
    .toMatchObject({
      method: "POST",
      body: {
        leaveTypeId: "leave-casual",
        startDate: "2026-08-03",
        endDate: "2026-08-04",
        units: 1.5,
        reason: "Family appointment",
      },
    });
});

test("[E2E-F02-005] regularization validates evidence fields and submits an attributable request", async ({
  page,
}) => {
  const api = await mockWorkforceApi(page, {
    attendanceScenario: "MISSING_CHECKOUT",
  });
  await page.goto("/attendance/regularizations");

  await expect(page.getByText("Ananya Rao")).toBeVisible();
  await expect(page.getByRole("combobox", { name: "Employee" })).toHaveCount(0);
  await page.getByRole("button", { name: "Submit regularization" }).click();
  await expect(page.getByText("Choose an attendance date.")).toBeVisible();
  await expect(
    page.getByText("Confirm that the information is accurate."),
  ).toBeVisible();

  await page.getByText("Choose an exception").click();
  await page
    .getByRole("option", { name: new RegExp(workforceFixture.exceptionDate) })
    .click();
  await page.getByText("Select reason").click();
  await page.getByRole("option", { name: "Missed check-out" }).click();
  await page.getByText("Select outcome").click();
  await page.getByRole("option", { name: "Correct punch" }).click();
  await page
    .getByPlaceholder("Explain what happened and the evidence available…")
    .fill("Checkout punch was missed after the approved shift.");
  await page.getByRole("checkbox").check();
  await page.getByRole("button", { name: "Submit regularization" }).click();

  await expect(page.getByText(/MISSED_CHECK_OUT/)).toBeVisible();
  await expect(
    page.getByText("Checkout punch was missed after the approved shift."),
  ).toBeVisible();
  await expect
    .poll(() =>
      api.mutations.find(
        (mutation) =>
          mutation.path === "/api/v1/attendance/regularizations",
      ),
    )
    .toMatchObject({
      method: "POST",
      body: {
        workDate: workforceFixture.exceptionDate,
        reasonCode: "MISSED_CHECK_OUT",
        requestedOutcome: "CORRECT_PUNCH",
      },
    });
});

test("[E2E-F02-006] month status presents immutable snapshot history without mutation controls", async ({
  page,
}) => {
  const api = await mockWorkforceApi(page);
  await page.goto("/attendance/month-close");

  await expect(
    page.getByRole("heading", { name: "Attendance month status" }),
  ).toBeVisible();
  const versions = page.getByText(/Snapshot version [12]/);
  await expect(versions.nth(0)).toHaveText("Snapshot version 2");
  await expect(versions.nth(1)).toHaveText("Snapshot version 1");
  await expect(page.getByText("Supersedes snapshot snapshot-v1")).toBeVisible();
  await expect(
    page.getByText("does not infer blocker resolution or mutate snapshot lineage"),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: /close|reopen|snapshot/i }),
  ).toHaveCount(0);
  expect(
    api.mutations.filter((mutation) =>
      mutation.path.includes("/attendance/month-snapshots"),
    ),
  ).toEqual([]);
});

test("[E2E-F02-007] self-service attendance resolves only the authenticated employee without peer discovery", async ({
  page,
}) => {
  const api = await mockWorkforceApi(page);
  const routes = [
    { path: "/attendance/today", heading: "Today's attendance" },
    { path: "/attendance/leave", heading: "Leave" },
    {
      path: "/attendance/regularizations",
      heading: "Attendance regularizations",
    },
  ];

  for (const route of routes) {
    await page.goto(route.path);
    await expect(
      page.getByRole("heading", { name: route.heading, exact: true }),
    ).toBeVisible();
    await expect(page.getByText("Ananya Rao")).toBeVisible();
    await expect(
      page.getByRole("combobox", { name: "Employee" }),
    ).toHaveCount(0);
    await expect(
      page.getByRole("combobox", { name: "Organization" }),
    ).toHaveCount(0);
  }

  expect(
    api.requests.filter(
      (request) =>
        request.method === "GET" &&
        request.path === "/api/v1/workforce/employees/me",
    ).length,
  ).toBeGreaterThan(0);
  expect(
    api.requests.filter(
      (request) =>
        request.method === "GET" &&
        (request.path === "/api/v1/organizations" ||
          (request.path === "/api/v1/workforce/employees" &&
            request.search.includes("organizationId"))),
    ),
  ).toEqual([]);
});

test("[E2E-F02-008] workforce administration executes governed manager workflows", async ({
  page,
}) => {
  const api = await mockWorkforceApi(page);
  await page.goto("/workforce/administration");

  await expect(
    page.getByRole("heading", { name: "Workforce administration" }),
  ).toBeVisible();
  await page.getByLabel("Employee administration scope").click();
  await page.getByRole("option", { name: /Ananya Rao/ }).click();

  await page.getByLabel("Alias value").fill("GHR-1001");
  await page.getByRole("button", { name: "Add immutable alias" }).click();

  await page.getByLabel("Project allocation").click();
  await page.getByRole("option", { name: /project-nam/ }).click();
  await page
    .getByLabel("Deliverable UUID")
    .fill("00000000-0000-0000-0000-00000000d101");
  await page.getByLabel("Allocation percent").fill("20");
  await page.getByRole("button", { name: "Add bounded allocation" }).click();

  await page.getByRole("button", { name: "Publish policy version" }).click();
  await page.getByLabel("Holiday date").fill("2026-08-15");
  await page.getByLabel("Holiday name").fill("Independence Day");
  await page
    .getByRole("button", { name: "Publish standard calendar" })
    .click();
  await page.getByRole("button", { name: "Publish shift policy" }).click();
  await page.getByLabel("Published policy").click();
  await page.getByRole("option", { name: /STANDARD_DAY · v1/ }).click();
  await page
    .getByRole("button", { name: "Assign shift to selected employee" })
    .click();

  await page.getByLabel("Engagement", { exact: true }).click();
  await page
    .getByRole("option", { name: "ArrowFoundry × Reliance" })
    .click();
  await page.getByLabel("Engagement month").click();
  await page.getByRole("option", { name: /2026-06-01/ }).click();
  await expect(page.getByText("1 employees · 30 employee-days")).toBeVisible();
  await page.getByRole("button", { name: "Finalize immutable roster" }).click();
  await expect(page.getByText("Roster v1")).toBeVisible();

  await page.getByLabel("Leave type").click();
  await page.getByRole("option", { name: "CL" }).click();
  await page.getByLabel("Auditable reason").fill("Opening balance evidence");
  await page
    .getByRole("button", { name: "Record immutable command" })
    .click();

  await page
    .getByLabel("Decision reasoning")
    .fill("Manager confirmed policy and staffing coverage");
  await page.getByRole("button", { name: "Approve exact version" }).click();

  await page
    .getByLabel("Review reasoning")
    .fill("VPN evidence confirms the corrected shift");
  await page
    .getByRole("button", { name: "Approve 540 minutes" })
    .click();

  await page
    .getByLabel("CSV preview")
    .fill(
      "employeeNumber,aliasType,aliasValue,validFrom\nAF-1001,HRIS_ID,GHR-1001,2026-08-01",
    );
  await page.getByRole("button", { name: "Validate only" }).click();
  await expect(
    page.getByText("VALIDATED · 1 row(s), 0 imported", { exact: true }),
  ).toBeVisible();

  await expect
    .poll(() => api.mutations.map((mutation) => mutation.path))
    .toEqual(
      expect.arrayContaining([
        `/api/v1/workforce/employees/${workforceFixture.employee.id}/aliases`,
        `/api/v1/workforce/employees/${workforceFixture.employee.id}/deliverable-allocations`,
        `/api/v1/workforce/organizations/org-arrowfoundry/leave-policies`,
        `/api/v1/workforce/organizations/org-arrowfoundry/calendars`,
        `/api/v1/workforce/organizations/org-arrowfoundry/shift-policies`,
        `/api/v1/workforce/employees/${workforceFixture.employee.id}/shift-assignments`,
        "/api/v1/workforce/engagement-months/month-2026-06/roster-snapshots",
        `/api/v1/workforce/employees/${workforceFixture.employee.id}/leave-balance-commands`,
        "/api/v1/workforce/leave-requests/leave-review-1/decisions",
        "/api/v1/attendance/regularizations/regularization-review-1/decisions",
        "/api/v1/workforce/organizations/org-arrowfoundry/imports",
      ]),
    );
});
