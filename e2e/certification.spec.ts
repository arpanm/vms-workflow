import { expect, test, type Page } from "@playwright/test";

import {
  certificationFixture,
  mockCertificationApi,
  type CertificationApiOptions,
} from "./fixtures/certification-api";
import { allowExpectedConsoleError } from "./fixtures/quality-gates";

const ids = certificationFixture.ids;

function mutations(api: Awaited<ReturnType<typeof mockCertificationApi>>) {
  return api.requests.filter((request) => request.method !== "GET");
}

async function openMonth(page: Page, options: CertificationApiOptions = {}) {
  const api = await mockCertificationApi(page, options);
  await page.goto(`/certification/${ids.month}`);
  return api;
}

async function openGovernance(page: Page, options: CertificationApiOptions = {}) {
  const api = await mockCertificationApi(page, options);
  await page.goto(`/confirmation/${ids.month}`);
  return api;
}

async function openConfirmation(page: Page, options: CertificationApiOptions = {}) {
  const api = await mockCertificationApi(page, options);
  await page.goto(`/confirmation/requests/${ids.request}`);
  return api;
}

function allowExpectedHttpFailure(page: Page, expected: RegExp, unexpectedConsoleErrors: string[]) {
  void unexpectedConsoleErrors;
  allowExpectedConsoleError(page, expected);
}

async function documentOverflow(page: Page) {
  return page.evaluate(() => {
    const clientWidth = document.documentElement.clientWidth;
    return {
      clientWidth,
      scrollWidth: document.documentElement.scrollWidth,
      offenders: [...document.querySelectorAll<HTMLElement>("body *")]
        .map((element) => ({
          tag: element.tagName,
          className: element.className,
          text: element.innerText?.slice(0, 80),
          right: Math.round(element.getBoundingClientRect().right),
          scrollWidth: element.scrollWidth,
          clientWidth: element.clientWidth,
        }))
        .filter(
          (element) =>
            element.right > clientWidth + 1 || element.scrollWidth > element.clientWidth + 1,
        )
        .slice(0, 12),
    };
  });
}

function expectNoDocumentOverflow(overflow: Awaited<ReturnType<typeof documentOverflow>>) {
  expect(overflow.scrollWidth, JSON.stringify(overflow.offenders, null, 2)).toBeLessThanOrEqual(
    overflow.clientWidth + 1,
  );
}

test("[E2E-F04-BC-001] vendor completeness, save, exact submit, lock, and timeline", async ({
  page,
}) => {
  const api = await openMonth(page);

  await expect(
    page.getByRole("heading", { name: "August 2026 delivery submission" }),
  ).toBeVisible();
  const blockers = page.getByRole("alert", { name: "Submission completeness blockers" });
  await expect(blockers).toContainText("Every mandatory criterion needs a response.");
  await expect(blockers).toContainText("scan-passed");
  await expect(page.getByText("Plan Time")).toBeVisible();
  await expect(page.getByText("Fetch Failed")).toBeVisible();
  await expect(
    page.getByText("“Done” or “Completed” in Linear is not displayed as product-owner acceptance."),
  ).toBeVisible();

  await page.getByLabel("Delivery summary").fill("Saved exact August delivery outcome.");
  await page.getByLabel("Vendor outcome").selectOption("PARTIALLY_COMPLETED");
  await page.getByLabel("Completion percent").fill("80");
  await page.getByLabel("Completion date").fill("2026-08-28");
  await page
    .getByLabel("Outcome summary")
    .fill("The core scope is complete with one governed carry-forward.");
  await page.getByLabel("Variance cause").fill("Approved test data arrived after cut-off.");
  await page.getByLabel("Variance impact").fill("Export verification moves to September.");
  await page.getByLabel("Next action").fill("Validate the export by 2026-09-05.");
  await page
    .getByLabel("Carry-forward proposal")
    .fill("Product owner and vendor owner review the export.");
  await page
    .getByRole("textbox", { name: /1\. The exact immutable version is reviewable/ })
    .fill("The exact criterion passed against the frozen baseline.");
  await page
    .getByLabel("Evidence for 1. The exact immutable version is reviewable.")
    .selectOption(certificationFixture.evidence.id);
  await page
    .getByLabel("Server-managed evidence references")
    .selectOption(certificationFixture.evidence.id);
  await page.getByLabel(/I declare that every effective deliverable and criterion/).check();

  const saveResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/certification/months/${ids.month}/submissions`) &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Save draft" }).click();
  expect((await saveResponse).status()).toBe(201);
  await expect(page.getByText("Draft saved to the server.")).toBeVisible();
  await expect(page.getByRole("alert", { name: "Submission completeness blockers" })).toHaveCount(
    0,
  );

  const submitResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/certification/submissions/${ids.submission}/submit`) &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Submit exact version" }).click();
  expect((await submitResponse).status()).toBe(200);
  await expect(page.getByText("Read-only / locked")).toBeVisible();
  await expect(
    page.getByText("Submitted content cannot be overwritten. Respond to an open clarification"),
  ).toBeVisible();
  await expect(page.getByText("Exact submission locked")).toBeVisible();
  await expect(page.getByLabel("Delivery summary")).toBeDisabled();

  const calls = mutations(api);
  expect(calls).toHaveLength(2);
  expect(calls[0]).toMatchObject({
    method: "POST",
    path: `/api/v1/certification/months/${ids.month}/submissions`,
    headers: {
      "if-match": '"7"',
    },
    body: {
      expectedMonthVersion: 7,
      summary: "Saved exact August delivery outcome.",
      declarationAccepted: true,
      items: [
        expect.objectContaining({
          deliverableId: ids.deliverable,
          outcome: "PARTIALLY_COMPLETED",
          completionPercentage: 80,
          evidenceReferenceIds: [ids.evidence],
          criterionResponses: [
            expect.objectContaining({
              criterionId: ids.criterion,
              evidenceReferenceIds: [ids.evidence],
            }),
          ],
        }),
      ],
    },
  });
  expect(calls[0]?.headers["idempotency-key"]).toMatch(/^[0-9a-f-]{36}$/i);
  expect(calls[1]).toMatchObject({
    path: `/api/v1/certification/submissions/${ids.submission}/submit`,
    headers: {
      "if-match": '"4"',
    },
    body: { expectedSubmissionVersion: 4 },
  });
});

test("[E2E-F04-BC-002] dirty visible edits are saved before exact submission", async ({ page }) => {
  const api = await openMonth(page, { monthScenario: "complete-draft" });

  await page
    .getByLabel("Delivery summary")
    .fill("Unsaved visible text that must not be omitted from exact submission.");
  await page.getByRole("button", { name: "Submit exact version" }).click();

  await expect
    .poll(() => mutations(api).map(({ path }) => path))
    .toEqual([
      `/api/v1/certification/months/${ids.month}/submissions`,
      `/api/v1/certification/submissions/${ids.submission}/submit`,
    ]);
  const calls = mutations(api);
  expect(calls[0]?.body).toMatchObject({
    expectedMonthVersion: 7,
    summary: "Unsaved visible text that must not be omitted from exact submission.",
  });
  expect(calls[1]?.body).toEqual({ expectedSubmissionVersion: 4 });
  expect(calls[0]?.headers["idempotency-key"]).not.toBe(calls[1]?.headers["idempotency-key"]);
});

test("[E2E-F04-BC-003] a submitted version stays read-only and preserves clarification/timeline evidence", async ({
  page,
}) => {
  await openMonth(page, { monthScenario: "submitted" });

  await expect(page.getByText("Read-only / locked")).toBeVisible();
  await expect(page.getByLabel("Delivery summary")).toBeDisabled();
  await expect(page.getByRole("button", { name: "Save draft" })).toBeDisabled();
  await expect(page.getByText("Clarification responses")).toBeVisible();
  await expect(page.getByText("Round 1")).toBeVisible();
  await expect(page.getByText("Exact submission locked")).toBeVisible();
});

test("[E2E-F04-BC-004] reviewer deep route exposes criteria, clarification, partial carry-forward, and no Linear inference", async ({
  page,
}) => {
  const api = await mockCertificationApi(page, {
    monthScenario: "submitted",
    includeUnassignedReview: true,
  });
  await page.goto(`/certification/${ids.month}/review`);

  const reviewHeading = page.getByRole("heading", {
    name: "August 2026 certification review",
  });
  await expect(
    reviewHeading,
    "The nested product-owner review route must render its child.",
  ).toBeVisible();

  await expect(page.getByRole("heading", { name: "Frozen baseline" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Vendor submission" })).toBeVisible();
  await expect(page.getByText("Recorded product-owner decision")).toBeVisible();
  const assignment = page.getByLabel(`Assignment for DEL-F04-001`);
  await expect(assignment).toContainText(/overdue/i);
  await expect(assignment).toContainText("Assignment age: 2 days");
  await expect(assignment).toContainText("Frozen product-owner assignment");
  await expect(page.getByText("Assigned items").locator("..")).toContainText("1");
  await expect(page.getByText("Unassigned payroll integration evidence")).toHaveCount(0);
  await expect(page.getByText(certificationFixture.evidence.displayName)).toBeVisible();
  await expect(page.getByText("Artifact access unavailable")).toBeVisible();
  await expect(
    page.getByText("Provider “Done” is never translated into acceptance."),
  ).toBeVisible();

  await page
    .getByLabel("Specific clarification questions")
    .fill("Which immutable scan record supports the export?;Who owns the carry-forward?");
  const clarificationResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/certification/submissions/${ids.submission}/clarifications`) &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Request more information" }).click();
  const recordedClarificationResponse = await clarificationResponse;
  expect(recordedClarificationResponse.status()).toBe(200);
  expect(recordedClarificationResponse.headers().etag).toBe('"7"');
  await expect
    .poll(() => mutations(api).find((request) => request.path.endsWith("/clarifications"))?.body)
    .toMatchObject({
      expectedSubmissionVersion: 3,
      questions: [
        "Which immutable scan record supports the export?",
        "Who owns the carry-forward?",
      ],
    });

  await page.getByLabel("Decision", { exact: true }).selectOption("PARTIALLY_MET");
  await page.getByLabel("Rationale").fill("The exact report supports only the accepted scope.");
  await page.getByText("Evidence viewed").click();
  await page.getByLabel("Aggregate decision").selectOption("PARTIALLY_ACCEPTED");
  await page.getByLabel("Decision comment (required)").fill("Explicit partial decision.");
  await page.getByLabel("Cause").fill("The export test dataset arrived late.");
  await page
    .getByLabel("Next action", { exact: true })
    .fill("Retest the export in the next governed month.");
  await page.getByLabel("Accepted scope", { exact: true }).fill("Core certification evidence.");
  await page.getByLabel("Unaccepted scope", { exact: true }).fill("Export verification.");
  await page
    .getByLabel("Carry-forward owner and next action")
    .fill("Reliance Product Owner · verify by 2026-09-05.");
  const certificationResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/certification/submissions/${ids.submission}/certifications`) &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Record independent decision" }).click();
  const recordedCertificationResponse = await certificationResponse;
  expect(recordedCertificationResponse.status()).toBe(200);
  expect(recordedCertificationResponse.headers().etag).toBe('"8"');

  const clarificationCall = mutations(api).find((request) =>
    request.path.endsWith("/clarifications"),
  );
  const certificationCall = mutations(api).find((request) =>
    request.path.endsWith("/certifications"),
  );
  expect(clarificationCall).toMatchObject({
    headers: { "if-match": '"3"' },
  });
  expect(clarificationCall?.headers["idempotency-key"]).toMatch(/^[0-9a-f-]{36}$/i);
  expect(certificationCall).toMatchObject({
    headers: { "if-match": '"3"' },
    body: {
      expectedSubmissionVersion: 3,
      decision: "PARTIALLY_ACCEPTED",
      acceptedScope: "Core certification evidence.",
      rejectedScope: "Export verification.",
      criterionResults: [
        expect.objectContaining({
          criterionId: ids.criterion,
          decision: "PARTIALLY_MET",
          evidenceViewed: true,
        }),
      ],
    },
  });
  expect(certificationCall?.headers["idempotency-key"]).toMatch(/^[0-9a-f-]{36}$/i);
  expect(certificationCall?.headers["idempotency-key"]).not.toBe(
    clarificationCall?.headers["idempotency-key"],
  );

  await expect(page.getByRole("button", { name: "Generate versioned summary" })).toBeEnabled();
  await page.getByLabel("Explicit monthly decision").selectOption("CERTIFIED_WITH_OBSERVATIONS");
  await page
    .getByLabel("Monthly observations")
    .fill("The exact partial decision and carry-forward remain visible.");
  const summaryResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/certification/months/${ids.month}/summaries`) &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Generate versioned summary" }).click();
  const recordedSummaryResponse = await summaryResponse;
  expect(recordedSummaryResponse.status()).toBe(201);
  expect(recordedSummaryResponse.headers().etag).toBe('"9"');

  const summaryCall = mutations(api).find((request) => request.path.endsWith("/summaries"));
  expect(summaryCall).toMatchObject({
    headers: { "if-match": '"8"' },
    body: {
      expectedMonthVersion: 8,
      decision: "CERTIFIED_WITH_OBSERVATIONS",
      observations: "The exact partial decision and carry-forward remain visible.",
    },
  });
  expect(summaryCall?.headers["idempotency-key"]).toMatch(/^[0-9a-f-]{36}$/i);
  expect(
    new Set(
      [clarificationCall, certificationCall, summaryCall].map(
        (request) => request?.headers["idempotency-key"],
      ),
    ).size,
  ).toBe(3);
});

test("[E2E-F04-BC-005] governance shows all five pillars, blockers, owners, CTAs, and no F05 execution", async ({
  page,
}) => {
  await openGovernance(page, {
    monthScenario: "complete-draft",
    readinessScenario: "blocked",
  });

  await expect(page.getByRole("heading", { name: "Five-pillar readiness" })).toBeVisible();
  for (const name of [
    "Roster and allocation",
    "Attendance",
    "Plan and Linear",
    "Certification",
    "Confirmation and handoff",
  ]) {
    await expect(
      page.getByRole("region", { name: "Five-pillar readiness" }).getByText(name, {
        exact: true,
      }),
    ).toBeVisible();
  }
  await expect(page.getByText("Closed attendance snapshot is required.")).toBeVisible();
  await expect(page.getByText(/Owner: Attendance Governance/)).toBeVisible();
  await expect(page.getByRole("link", { name: "Open month close" })).toHaveAttribute(
    "href",
    "/attendance/month-close",
  );
  await expect(page.getByRole("button", { name: "Create and queue exact request" })).toBeDisabled();
  await expect(page.getByRole("button", { name: /create.*invoice|create.*package/i })).toHaveCount(
    0,
  );
  await expect(
    page.getByText("This workflow does not create an invoice, procurement package"),
  ).toBeVisible();
});

test("[E2E-F04-BC-006] server offset is converted into the operator timezone before request creation", async ({
  page,
}) => {
  await openGovernance(page, { monthScenario: "complete-draft" });

  await expect(page.getByLabel("Due date and time")).toHaveValue("2026-08-31T09:00");
});

test("[E2E-F04-BC-031] due conversion respects the operator zone outside daylight-saving time", async ({
  page,
}) => {
  await openGovernance(page, {
    monthScenario: "complete-draft",
    defaultDueAt: "2027-01-15T18:30:00+05:30",
  });

  await expect(page.getByLabel("Due date and time")).toHaveValue("2027-01-15T08:00");
  await expect(page.getByText(/Displayed in America\/New_York/)).toBeVisible();
});

test("[E2E-F04-BC-007] governance queues the exact recipient/quorum/version request with concurrency headers", async ({
  page,
}) => {
  const api = await openGovernance(page, { monthScenario: "complete-draft" });

  await expect(page.getByText("Reliance Product Owner").first()).toBeVisible();
  await expect(page.getByText("Central Procurement").first()).toBeVisible();
  await expect(page.getByText("ANY_ONE · 1 of 1 eligible confirmer")).toBeVisible();
  await expect(page.getByText(new RegExp(ids.attendanceSnapshot))).toBeVisible();
  await page.getByLabel("Due date and time").fill("2026-08-31T09:00");
  const createResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/certification/months/${ids.month}/confirmation-requests`) &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Create and queue exact request" }).click();
  expect((await createResponse).status()).toBe(201);

  await expect(page).toHaveURL(
    new RegExp(`/confirmation/requests/${certificationFixture.ids.request}$`),
  );
  const call = mutations(api).find((request) => request.path.endsWith("/confirmation-requests"));
  expect(call).toMatchObject({
    headers: {
      "if-match": '"7"',
    },
    body: {
      expectedMonthVersion: 7,
      dueAt: "2026-08-31T13:00:00.000Z",
    },
  });
  expect(call?.headers["idempotency-key"]).toMatch(/^[0-9a-f-]{36}$/i);
});

test("[E2E-F04-BC-008] inbound/manual evidence remains safe metadata and requires reviewer decision/reason controls", async ({
  page,
}) => {
  const api = await openGovernance(page, { monthScenario: "complete-draft" });

  await expect(
    page.getByText("Restricted inbound and manual-evidence review", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("Ambiguous reply requires a distinct authorized reviewer."),
  ).toBeVisible();
  await expect(page.getByText("No explicit confirmation phrase was recorded.")).toBeVisible();
  await expect(page.getByText(/cannot autonomously confirm/)).toBeVisible();
  await expect(
    page.getByText("Unsafe inbound content was quarantined without creating a decision."),
  ).toBeVisible();
  await expect(page.getByText("Provider signature verification failed.")).toBeVisible();
  await expect(page.getByLabel("Inbound review reason")).toBeVisible();
  await expect(page.getByRole("button", { name: "Accept inbound interpretation" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Reject inbound interpretation" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Quarantine inbound item" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Approve manual evidence" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Reject manual evidence" })).toBeVisible();

  await page.getByRole("button", { name: "Accept inbound interpretation" }).click();
  const validation = page.getByRole("alert", { name: "Evidence review errors" });
  await expect(validation).toBeFocused();
  await expect(page.getByLabel("Inbound review reason")).toHaveAttribute("aria-invalid", "true");

  await page
    .getByLabel("Inbound review reason")
    .fill("The redacted interpretation matches the explicit permitted phrase.");
  const reviewResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/certification/inbound-messages/${ids.inbound}/reviews`) &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Accept inbound interpretation" }).click();
  const recordedReviewResponse = await reviewResponse;
  expect(recordedReviewResponse.status()).toBe(201);
  expect(recordedReviewResponse.headers().etag).toBe('"1"');

  const call = mutations(api).find((request) =>
    request.path.endsWith(`/inbound-messages/${ids.inbound}/reviews`),
  );
  expect(call).toMatchObject({
    headers: { "if-match": '"0"' },
    body: {
      expectedReviewVersion: 0,
      decision: "ACCEPT_INTERPRETATION",
      reasoning: "The redacted interpretation matches the explicit permitted phrase.",
    },
  });
  expect(call?.headers["idempotency-key"]).toMatch(/^[0-9a-f-]{36}$/i);
  expect(api.evidenceReviewBusinessEffects).toBe(1);
  await expect(
    page
      .getByRole("heading", {
        name: "Ambiguous reply requires a distinct authorized reviewer.",
      })
      .locator(".."),
  ).toContainText(/approved/i);
});

test("[E2E-F04-BC-032] manual evidence requires a distinct attributable second-review action", async ({
  page,
}) => {
  const api = await openGovernance(page, { monthScenario: "complete-draft" });

  await expect(page.getByText("Recorder and second reviewer must remain distinct.")).toBeVisible();
  await page
    .getByLabel("Manual evidence review reason")
    .fill("A distinct reviewer verified the safe immutable metadata.");
  const reviewResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/certification/manual-evidence/${ids.manualEvidence}/reviews`) &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Approve manual evidence" }).click();
  const recordedReviewResponse = await reviewResponse;
  expect(recordedReviewResponse.status()).toBe(201);
  expect(recordedReviewResponse.headers().etag).toBe('"1"');

  const call = mutations(api).find((request) =>
    request.path.endsWith(`/manual-evidence/${ids.manualEvidence}/reviews`),
  );
  expect(call).toMatchObject({
    headers: { "if-match": '"0"' },
    body: {
      expectedReviewVersion: 0,
      decision: "APPROVE",
      reasoning: "A distinct reviewer verified the safe immutable metadata.",
    },
  });
  expect(call?.headers["idempotency-key"]).toMatch(/^[0-9a-f-]{36}$/i);
  expect(api.evidenceReviewBusinessEffects).toBe(1);
});

test("[E2E-F04-BC-033] unauthorized inbound access returns no restricted review metadata or controls", async ({
  page,
}) => {
  await openGovernance(page, {
    monthScenario: "complete-draft",
    inboundAccess: "unauthorized",
  });

  await expect(
    page.getByText("Restricted metadata is unavailable to the current authority."),
  ).toBeVisible();
  await expect(
    page.getByText("Ambiguous reply requires a distinct authorized reviewer."),
  ).toHaveCount(0);
  await expect(page.getByText(ids.inboundAudit)).toHaveCount(0);
  await expect(page.getByLabel("Inbound review reason")).toHaveCount(0);
  await expect(page.getByRole("button", { name: /inbound interpretation/i })).toHaveCount(0);
  await expect(page.getByRole("button", { name: /manual evidence/i })).toHaveCount(0);
});

test("[E2E-F04-BC-009] exact diff confirmation records one attributable in-app action", async ({
  page,
}) => {
  const api = await openConfirmation(page);

  await expect(page.getByText("Exact confirmation scope", { exact: true })).toBeVisible();
  await expect(page.getByText("sha256:f04-confirmation-scope-v2")).toBeVisible();
  await expect(
    page
      .getByRole("heading", { name: "Bound source versions" })
      .locator("..")
      .getByText(ids.attendanceSnapshot, { exact: true }),
  ).toBeVisible();
  const diff = page.getByRole("table", {
    name: "Changes from the superseded confirmation request",
  });
  await expect(diff).toContainText("attendance-snapshot-v3");
  await expect(diff).toContainText("attendance-snapshot-v4");
  await expect(page.getByText("AWAITING RESPONSE").first()).toBeVisible();
  await expect(page.getByText("DELIVERED").first()).toBeVisible();

  await page.getByLabel("Confirmation comment").fill("Reviewed the exact visible diff.");
  await page.getByRole("button", { name: "Record action for exact version" }).click();
  await expect(page.getByText("CONFIRMED").first()).toBeVisible();
  await expect(page.getByText(ids.actionAudit)).toBeVisible();
  await expect(page.getByText(/IN_APP/)).toBeVisible();

  expect(mutations(api)).toContainEqual(
    expect.objectContaining({
      path: `/api/v1/certification/confirmation-requests/${ids.request}/actions`,
      headers: expect.objectContaining({ "if-match": '"2"' }),
      body: {
        expectedRequestVersion: 2,
        decision: "CONFIRM",
        comment: "Reviewed the exact visible diff.",
      },
    }),
  );
});

test("[E2E-F04-BC-010] non-confirmation requires actionable keyboard and screen-reader feedback", async ({
  page,
}) => {
  await openConfirmation(page);

  await page.getByLabel("Request correction").check();
  const action = page.getByRole("button", { name: "Record action for exact version" });
  await expect(action).toBeEnabled();
  await action.click();

  const errorSummary = page.getByRole("alert", { name: /confirmation action errors/i });
  await expect(errorSummary).toContainText("Reason and required correction is required.");
  await expect(errorSummary).toBeFocused();
  await expect(page.getByLabel("Reason and required correction (required)")).toHaveAttribute(
    "aria-invalid",
    "true",
  );
});

test("[E2E-F04-BC-028] reviewer attempted submit focuses linked criterion and decision errors", async ({
  page,
}) => {
  await mockCertificationApi(page, { monthScenario: "submitted" });
  await page.goto(`/certification/${ids.month}/review`);
  await page.getByRole("button", { name: "Record independent decision" }).click();

  const errors = page.getByRole("alert", { name: "Certification decision errors" });
  await expect(errors).toContainText("Every criterion decision requires a rationale.");
  await expect(errors).toBeFocused();
  await expect(page.getByLabel("Rationale")).toHaveAttribute("aria-invalid", "true");
});

test("[E2E-F04-BC-029] reopen attempted submit focuses a complete linked error summary", async ({
  page,
}) => {
  await openGovernance(page, { monthScenario: "complete-draft" });
  await page.getByRole("button", { name: "Request governed reopen" }).click();

  const errors = page.getByRole("alert", { name: "Reopen request errors" });
  await expect(errors).toContainText("Reopen reason is required.");
  await expect(errors).toContainText("At least one impacted record ID is required.");
  await expect(errors).toBeFocused();
  await expect(page.getByLabel("Reason", { exact: true })).toHaveAttribute("aria-invalid", "true");
});

test("[E2E-F04-BC-030] confirmation request validates and focuses a missing local due time", async ({
  page,
}) => {
  await openGovernance(page, { monthScenario: "complete-draft" });
  await page.getByLabel("Due date and time").fill("");
  await page.getByRole("button", { name: "Create and queue exact request" }).click();

  const errors = page.getByRole("alert", { name: "Confirmation request errors" });
  await expect(errors).toContainText("Enter a valid confirmation due date and time.");
  await expect(errors).toBeFocused();
  await expect(page.getByLabel("Due date and time")).toHaveAttribute("aria-invalid", "true");
});

test("[E2E-F04-BC-011] explicit correction preserves source facts and records the governance outcome", async ({
  page,
}) => {
  const api = await openConfirmation(page);

  await page.getByLabel("Request correction").check();
  await page
    .getByLabel("Reason and required correction (required)")
    .fill("Correct the disclosed attendance snapshot.");
  await page.getByText(/I understand this preserves the request and action/).click();
  await page.getByRole("button", { name: "Record action for exact version" }).click();

  await expect(page.getByText("CHANGES REQUESTED").first()).toBeVisible();
  await expect(
    page
      .getByText("Action and request lineage", { exact: true })
      .locator("..")
      .locator("..")
      .getByText("Correct the disclosed attendance snapshot.", { exact: true }),
  ).toBeVisible();
  expect(mutations(api)[0]?.body).toEqual({
    expectedRequestVersion: 2,
    decision: "REQUEST_CORRECTION",
    comment: "Correct the disclosed attendance snapshot.",
  });
});

test("[E2E-F04-BC-012] replay renders only the prior authorized result and no second action", async ({
  page,
}) => {
  const api = await openConfirmation(page, { confirmationScenario: "replayed" });

  await expect(page.getByText("This immutable request is terminal.")).toBeVisible();
  await expect(page.getByText(ids.actionAudit)).toHaveCount(1);
  await expect(
    page.getByRole("button", { name: "Record action for exact version" }),
  ).toBeDisabled();
  expect(mutations(api)).toEqual([]);
});

test("[E2E-F04-BC-013] expired confirmation is non-actionable and never inferred from elapsed time", async ({
  page,
}) => {
  await openConfirmation(page, { confirmationScenario: "expired" });

  await expect(page.getByText("EXPIRED").first()).toBeVisible();
  await expect(page.getByText("This immutable request is terminal.")).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Record action for exact version" }),
  ).toBeDisabled();
  await expect(page.getByText(/elapsed due time do not create an action/i)).toBeVisible();
});

test("[E2E-F04-BC-014] unauthorized confirmation deep link is a non-disclosing safe denial", async ({
  page,
}) => {
  const unexpectedConsoleErrors: string[] = [];
  allowExpectedHttpFailure(
    page,
    /status of 404.*certification\/confirmation-requests/i,
    unexpectedConsoleErrors,
  );
  await openConfirmation(page, { confirmationScenario: "unauthorized" });

  const denial = page.getByRole("alert");
  await expect(denial).toContainText("This record is unavailable", { timeout: 15_000 });
  await expect(denial).toContainText("Reference: corr-f04-denied");
  await expect(page.getByText("sha256:f04-confirmation-scope-v2")).toHaveCount(0);
  await expect(page.getByText(ids.attendanceSnapshot)).toHaveCount(0);
  expect(unexpectedConsoleErrors).toEqual([]);
});

test("[E2E-F04-BC-015] transport delivery, read, failure, and silence do not approve", async ({
  page,
}) => {
  await openGovernance(page, { monthScenario: "complete-draft" });

  await expect(page.getByText("READ").first()).toBeVisible();
  await expect(page.getByText("DEAD LETTER").first()).toBeVisible();
  await expect(page.getByText("AWAITING RESPONSE").first()).toBeVisible();
  await expect(
    page.getByText("Transport status never changes the business decision.").first(),
  ).toBeVisible();
  await expect(page.getByText("CONFIRMED", { exact: true })).toHaveCount(0);
});

test("[E2E-F04-BC-016] governed reopen appends request lineage and invalidates current readiness", async ({
  page,
}) => {
  const api = await openGovernance(page, { monthScenario: "complete-draft" });

  await page.getByLabel("Correction category").selectOption("CERTIFICATION_CORRECTION");
  await page.getByLabel("Reason", { exact: true }).fill("Correct the exact certification summary.");
  await page.getByLabel("Impacted record IDs (comma separated)").fill(ids.summary);
  await page
    .getByLabel("Package / invoice impact")
    .fill("Invalidate only the current F05 readiness handoff.");
  await page
    .getByLabel("Risk statement")
    .fill("Prior request remains immutable and a new confirmation is required.");
  const reopenResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith(`/certification/months/${ids.month}/reopen-requests`) &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Request governed reopen" }).click();
  expect((await reopenResponse).status()).toBe(201);

  await expect(page.getByText("Refresh before taking an action.")).toBeVisible();
  await expect(page.getByRole("link", { name: "Request v3" })).toBeVisible();
  await expect(page.getByText(`supersedes ${ids.request}`)).toBeVisible();
  expect(mutations(api)[0]?.body).toEqual({
    expectedMonthVersion: 7,
    category: "CERTIFICATION_CORRECTION",
    reason: "Correct the exact certification summary.",
    impactedRecordIds: [ids.summary],
    packageInvoiceImpact: "Invalidate only the current F05 readiness handoff.",
    riskStatement: "Prior request remains immutable and a new confirmation is required.",
  });
});

test("[E2E-F04-BC-017] stale server state is explicit and disables consequential controls", async ({
  page,
}) => {
  await openGovernance(page, {
    monthScenario: "stale",
    readinessScenario: "stale",
  });

  await expect(page.getByText("Refresh before taking an action.")).toBeVisible();
  await expect(
    page.getByText("Inputs changed after this evaluation. Re-evaluation is required"),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "Create and queue exact request" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "Request governed reopen" })).toBeDisabled();
});

test("[E2E-F04-BC-018] version conflict rebases controlled fields to the current server version", async ({
  page,
}) => {
  const unexpectedConsoleErrors: string[] = [];
  allowExpectedHttpFailure(page, /status of 412.*certification\/months/i, unexpectedConsoleErrors);
  await openMonth(page, {
    monthScenario: "complete-draft",
    saveConflict: true,
  });

  await page.getByLabel("Delivery summary").fill("Stale local summary.");
  await page.getByRole("button", { name: "Save draft" }).click();

  const conflictAlert = page.getByRole("alert");
  await expect(conflictAlert).toContainText("A newer version is available");
  await expect(conflictAlert).toBeFocused();
  await expect(page.getByText("Server version v8")).toBeVisible();
  await expect(page.getByLabel("Delivery summary")).toHaveValue(
    "Concurrent server summary that must replace stale local fields.",
  );
  expect(unexpectedConsoleErrors).toEqual([]);
});

test("[E2E-F04-BC-019] server error detail cannot render token, raw MIME, or provider-secret content", async ({
  page,
}) => {
  const unexpectedConsoleErrors: string[] = [];
  allowExpectedHttpFailure(page, /status of 404.*certification\/months/i, unexpectedConsoleErrors);
  await openMonth(page, { unsafeMonthError: true });

  await expect(page.getByRole("alert")).toContainText("This record is unavailable", {
    timeout: 15_000,
  });
  await expect(page.getByRole("alert")).toContainText("Reference: corr-f04-safe-denial");
  await expect(page.getByText(/internal persistence diagnostic row 42/i)).toHaveCount(0);
  expect(unexpectedConsoleErrors).toEqual([]);
});

test("[E2E-F04-BC-020] browser state contains no confirmation token, raw restricted content, or provider secret", async ({
  page,
}) => {
  const api = await openConfirmation(page);

  const body = await page.locator("body").innerText();
  expect(body).not.toMatch(/plaintext-token=|token-hash=|boundary=unsafe|provider-secret=/i);
  expect(page.url()).not.toMatch(/[?&#](token|code|secret)=/i);
  const browserStorage = await page.evaluate(() => ({
    local: Object.fromEntries(Object.entries(localStorage)),
    session: Object.fromEntries(Object.entries(sessionStorage)),
  }));
  expect(JSON.stringify(browserStorage)).not.toMatch(
    /access.?token|refresh.?token|provider.?secret/i,
  );
  expect(JSON.stringify(api.requests)).not.toMatch(
    /plaintext.?token|token.?hash|raw.?mime|provider.?secret|private.?key/i,
  );
  expect(body).not.toMatch(/\b(?:salary|payroll|ctc|markup|employee rate)\b/i);
});

test("[E2E-F04-BC-025] a committed action with a lost response retries the retained user intent once", async ({
  page,
}) => {
  allowExpectedConsoleError(
    page,
    /failed to load resource|networkerror|failed to fetch|connection/i,
  );
  const api = await openConfirmation(page, { loseFirstActionResponse: true });

  await page.getByLabel("Confirmation comment").fill("Retry this exact visible intent.");
  await page.getByRole("button", { name: "Record action for exact version" }).click();
  await expect(page.getByRole("alert")).toContainText("Certification service unavailable");

  await page.getByRole("button", { name: "Record action for exact version" }).click();
  await expect(page.getByText("CONFIRMED").first()).toBeVisible();

  const actionCalls = mutations(api).filter((request) => request.path.endsWith("/actions"));
  expect(actionCalls).toHaveLength(2);
  expect(actionCalls[1]?.headers["idempotency-key"]).toBe(
    actionCalls[0]?.headers["idempotency-key"],
  );
  expect(actionCalls[1]?.body).toEqual(actionCalls[0]?.body);
  expect(api.actionBusinessEffects).toBe(1);
});

test("[E2E-F04-BC-026] a multi-project confirmer selects an exact server-supplied contribution", async ({
  page,
}) => {
  const api = await openConfirmation(page, { multiProjectAction: true });

  await page.getByRole("button", { name: "Record action for exact version" }).click();
  await expect(page.getByRole("alert", { name: "Confirmation action errors" })).toContainText(
    "Select the eligible project this action covers.",
  );

  await page.getByLabel("Eligible project contribution (required)").selectOption(ids.projectB);
  await page.getByRole("button", { name: "Record action for exact version" }).click();
  await expect(page.getByText("CONFIRMED").first()).toBeVisible();

  expect(mutations(api).find((request) => request.path.endsWith("/actions"))?.body).toEqual({
    expectedRequestVersion: 2,
    decision: "CONFIRM",
    projectId: ids.projectB,
  });
});

test("[E2E-F04-BC-021] critical confirmation controls remain named and keyboard reachable", async ({
  page,
}) => {
  await page.setViewportSize({ width: 768, height: 1024 });
  await openConfirmation(page);

  await expect(page.locator("main")).toHaveCount(1);
  await expect(page.getByRole("heading", { name: "August 2026 confirmation" })).toBeVisible();
  await expect(page.getByLabel("Confirm exact version")).toBeVisible();
  await expect(page.getByLabel("Request correction")).toBeVisible();
  await expect(page.getByLabel("Reject")).toBeVisible();
  await expect(page.getByLabel("Confirmation comment")).toBeVisible();

  await page.getByLabel("Confirm exact version").focus();
  await page.keyboard.press("ArrowRight");
  await expect(page.getByLabel("Request correction")).toBeFocused();
});

test("[E2E-F04-BC-022] exact confirmation scope does not overflow a tablet viewport", async ({
  page,
}) => {
  await page.setViewportSize({ width: 768, height: 1024 });
  await openConfirmation(page);
  await page.getByLabel("Request correction").check();

  expectNoDocumentOverflow(await documentOverflow(page));
});

test("[E2E-F04-BC-027] expanded confirmation action fits a narrow phone viewport", async ({
  page,
}) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await openConfirmation(page);
  await page.getByLabel("Request correction").check();

  await expect(page.getByLabel("Reason and required correction (required)")).toBeVisible();
  expectNoDocumentOverflow(await documentOverflow(page));
});

test("[E2E-F04-BC-023] vendor criterion evidence uses a safe server-managed selector", async ({
  page,
}) => {
  await openMonth(page, { monthScenario: "complete-draft" });

  const criterionEvidence = page.getByLabel(
    "Evidence for 1. The exact immutable version is reviewable.",
  );
  await expect(criterionEvidence).toBeVisible();
  await expect(
    criterionEvidence.getByRole("option", {
      name: `${certificationFixture.evidence.displayName} · scan passed`,
    }),
  ).toBeVisible();
  await expect(page.getByLabel("Arbitrary evidence URL")).toHaveCount(0);
});

test("[E2E-F04-BC-024] bound confirmation sources are human-readable named versions and hashes", async ({
  page,
}) => {
  await openConfirmation(page);

  const sources = page.getByRole("heading", { name: "Bound source versions" }).locator("..");
  await expect(sources.getByText("Closed attendance snapshot", { exact: true })).toBeVisible();
  await expect(sources.getByText("Frozen delivery plan", { exact: true })).toBeVisible();
  await expect(sources.getByText("Immutable delivery baseline", { exact: true })).toBeVisible();
  await expect(sources.getByText("Monthly certification summary", { exact: true })).toBeVisible();
  await expect(sources.getByText(/^sha256:/)).toHaveCount(4);
});
