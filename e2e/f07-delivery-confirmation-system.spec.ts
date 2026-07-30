import { expect, test, type APIResponse } from "@playwright/test";
import {
  createDecipheriv,
  createHash,
  createHmac,
  randomUUID,
} from "node:crypto";
import { execFileSync } from "node:child_process";

import "./fixtures/quality-gates";

const monthId = "00000000-0000-0000-0000-000000000602";
const nextMonthId = "73000000-0000-0000-0000-000000000001";
const projectId = "00000000-0000-0000-0000-000000000501";
const employeeId = "00000000-0000-0000-0000-000000000801";
const evidenceId = "00000000-0000-0000-0000-000000000904";
const linearConnectionId = "00000000-0000-0000-0000-000000001101";
const linearIssueId = "00000000-0000-0000-0000-000000001201";
const systemInstant = "2026-07-29T10:00:00.000Z";
const systemEpochMilliseconds = Date.parse(systemInstant);
const systemEpochSeconds = Math.floor(systemEpochMilliseconds / 1_000);
const postgresContainer = requiredEnvironment("VMS_E2E_POSTGRES_CONTAINER");

const tokens = {
  vendor: requiredEnvironment("VMS_E2E_TOKEN_USER_ARROW"),
  approver: requiredEnvironment("VMS_E2E_TOKEN_USER_APPROVER"),
  productOwner: requiredEnvironment("VMS_E2E_TOKEN_USER_RELIANCE"),
  governance: requiredEnvironment("VMS_E2E_TOKEN_USER_GOVERNANCE"),
  inbound: requiredEnvironment("VMS_E2E_TOKEN_USER_INBOUND"),
  reviewer: requiredEnvironment("VMS_E2E_TOKEN_USER_REVIEWER"),
};

let planId = "";
let planVersionId = "";
let baselineId = "";
let deliverableVersionId = "";
let criterionId = "";
let linearLinkId = "";
let submissionId = "";
let summaryId = "";
let firstConfirmationRequestId = "";
let secondConfirmationRequestId = "";

test.describe.configure({ mode: "serial" });

test("[E2E-03] plan, Linear delivery, partial certification and carry-forward preserve exact lineage", async ({
  request,
}) => {
  const created = await json(await request.post("/api/v1/delivery/plans", {
    headers: authorization(tokens.vendor),
    data: {
      engagementMonthId: monthId,
      title: "F07 exact real-system delivery plan",
      summary: "One deterministic provider-neutral deliverable",
      businessOutcomes: "Exercise immutable delivery and certification lineage",
      coordinatorSubject: "user-arrow",
      baselineType: "ON_TIME",
      quorumMode: "ANY_ONE",
      quorumRequired: 1,
      approverSubjects: ["user-approver"],
      recipients: {
        arrowFoundry: ["alice@arrowfoundry.example"],
        relianceStakeholders: ["ravi@reliance.example"],
        procurementCc: ["governance@reliance.example"],
      },
      deliverables: [{
        deliverableCode: "F07-E2E-03",
        title: "Real-system delivery evidence",
        description: "Delivered through recorded Linear and reviewed independently",
        businessObjective: "Prove delivery state never becomes business acceptance",
        projectId,
        productOwnerSubject: "user-reliance",
        vendorOwnerSubject: "user-arrow",
        priority: "P1",
        targetCompletionDate: "2026-07-31",
        evidenceExpectations: "A scan-passed immutable test report",
        dependencyNoneDeclared: true,
        riskAndAssumptions: "Recorded provider data is deterministic and local",
        deliveryCategory: "QUALITY",
        criteria: [{
          statement: "The exact frozen acceptance criterion is partially met",
          validationMethod: "Playwright, Spring and PostgreSQL",
          expectedResult: "Immutable evidence plus explicit client decision",
          mandatory: true,
        }],
        dependencies: [],
        assignments: [{
          employeeId,
          effectiveFrom: "2026-07-01",
          effectiveTo: "2026-07-31",
        }],
      }],
    },
  }), 201);
  planId = String(created.id);
  planVersionId = String(created.currentVersionId);
  deliverableVersionId = String(created.deliverables[0].deliverableVersionId);
  criterionId = String(created.deliverables[0].criteria[0].id);
  expect(created.completenessBlockers).toContain(
    `${created.deliverables[0].deliverableCode}:LINEAR_LINK_OR_EXCEPTION_REQUIRED`,
  );

  const linked = await json(await request.post(
    "/api/v1/integrations/linear/links",
    {
      headers: authorization(tokens.vendor),
      data: {
        deliverableVersionId,
        connectionId: linearConnectionId,
        issueUuid: linearIssueId,
        rationale: "Single recorded provider issue for the exact deliverable",
      },
    },
  ), 201);
  linearLinkId = String(linked.id);
  expect(linked.currentNormalizedState).toBe("UNSTARTED");

  const submittedPlan = await json(await request.post(
    `/api/v1/delivery/plans/${planId}/submit`,
    { headers: authorization(tokens.vendor) },
  ), 200);
  expect(submittedPlan.state).toBe("PENDING_APPROVAL");
  expect(submittedPlan.checksum).toMatch(/^[0-9a-f]{64}$/);

  const frozen = await json(await request.post(
    `/api/v1/delivery/plans/${planId}/approvals`,
    {
      headers: authorization(tokens.approver),
      data: {
        decision: "APPROVE",
        comment: "Approved the exact immutable baseline",
      },
    },
  ), 200);
  baselineId = String(frozen.baselineId);
  expect(frozen).toMatchObject({
    state: "FROZEN",
    commitmentStatus: "PENDING",
  });
  expect(frozen.approvals[0].signedChecksum).toBe(submittedPlan.checksum);
  await expect.poll(() => queryDatabase(`
    SELECT COUNT(*) FROM commitment_outbox outbox
    WHERE outbox.baseline_id = '${baselineId}'::uuid
      AND outbox.plan_version_id = '${planVersionId}'::uuid
      AND outbox.status = 'SENT'
      AND outbox.attempt_count = 1
      AND outbox.provider_message_id =
          'recorded-commitment-' || outbox.id::text
      AND outbox.provider_thread_id =
          'recorded-commitment-thread-' || outbox.baseline_id::text
      AND outbox.sent_at IS NOT NULL
      AND outbox.recipient_snapshot @> '{
        "arrowFoundry":["alice@arrowfoundry.example"],
        "relianceStakeholders":["ravi@reliance.example"],
        "procurementCc":["governance@reliance.example"]
      }'::jsonb
      AND outbox.subject_text LIKE '%[${
        submittedPlan.checksum.slice(0, 12)
      }]%'
      AND outbox.plain_text LIKE '%${submittedPlan.checksum}%';
  `), { timeout: 15_000 }).toBe("1");
  expect(queryDatabase(`
    SELECT COUNT(*) FROM commitment_outbox_attempts attempt
    JOIN commitment_outbox outbox ON outbox.id = attempt.outbox_id
    WHERE outbox.baseline_id = '${baselineId}'::uuid
      AND attempt.attempt_number = 1
      AND attempt.status = 'SENT'
      AND attempt.provider_message_reference = outbox.provider_message_id;
  `)).toBe("1");

  const webhookTimestamp = systemEpochMilliseconds;
  const webhookDeliveryId = randomUUID();
  const webhookBody = JSON.stringify({
    type: "Issue",
    action: "update",
    organizationId: "linear-test-organization",
    connectionId: linearConnectionId,
    webhookTimestamp,
    data: {
      id: linearIssueId,
      identifier: "TEAM-123",
      url: "https://linear.app/test/issue/TEAM-123",
      title: "Recorded issue",
      updatedAt: systemInstant,
      state: {
        id: "state-done",
        name: "Done",
        type: "completed",
      },
    },
  });
  const accepted = await json(await request.post(
    `/api/v1/integrations/linear/webhook/${linearConnectionId}`,
    {
      headers: {
        "Content-Type": "application/json",
        "Linear-Signature": createHmac("sha256", "test-webhook-secret")
          .update(webhookBody)
          .digest("hex"),
        "Linear-Timestamp": String(webhookTimestamp),
        "Linear-Delivery": webhookDeliveryId,
      },
      data: webhookBody,
    },
  ), 200);
  expect(accepted).toMatchObject({
    deliveryId: webhookDeliveryId,
    duplicate: false,
    queueStatus: "QUEUED",
  });
  const processed = await json(await request.post(
    `/api/v1/integrations/linear/deliveries/${webhookDeliveryId}/process`,
    { headers: authorization(tokens.vendor) },
  ), 200);
  expect(processed.status).toBe("PROCESSED");

  const current = await json(await request.get(
    `/api/v1/integrations/linear/links/${linearLinkId}/current`,
    { headers: authorization(tokens.productOwner) },
  ), 200);
  expect(current).toMatchObject({
    normalizedState: "COMPLETED",
    executionProjection: "COMPLETED",
  });
  expect(queryDatabase(`
    SELECT state FROM delivery_plan_versions
    WHERE id = '${planVersionId}'::uuid;
  `)).toBe("FROZEN");

  const monthEnd = await json(await request.post(
    `/api/v1/integrations/linear/months/${monthId}/snapshots`,
    { headers: authorization(tokens.vendor) },
  ), 200);
  expect(monthEnd).toHaveLength(1);
  expect(monthEnd[0]).toMatchObject({
    snapshotType: "MONTH_END",
    status: "CAPTURED",
    normalizedState: "COMPLETED",
    confidence: "CURRENT_STATE_ONLY",
  });
  const monthEndReplay = await json(await request.post(
    `/api/v1/integrations/linear/months/${monthId}/snapshots`,
    { headers: authorization(tokens.vendor) },
  ), 200);
  expect(monthEndReplay[0].id).toBe(monthEnd[0].id);

  let workspace = await json(await request.get(
    `/api/v1/certification/months/${monthId}`,
    { headers: authorization(tokens.vendor) },
  ), 200);
  const draft = await json(await request.post(
    `/api/v1/certification/months/${monthId}/submissions`,
    {
      headers: versionedHeaders(
        tokens.vendor,
        workspace.version,
        "e2e-03-save-partial",
      ),
      data: {
        expectedMonthVersion: workspace.version,
        summary: "The core outcome is usable; one bounded item carries forward",
        declarationAccepted: true,
        items: [{
          deliverableId: deliverableVersionId,
          outcome: "PARTIALLY_COMPLETED",
          completionPercentage: 70,
          completionDate: "2026-07-29",
          summary: "The accepted core is complete and the hardening slice remains",
          varianceCause: "Joint scope sequencing",
          varianceImpact: "The optional hardening slice moves to August",
          nextAction: "Complete and independently verify the hardening slice",
          carryForwardProposal: "Carry the bounded hardening slice into August",
          criterionResponses: [{
            criterionId,
            response: "Core behavior passed; the hardening extension is pending",
            evidenceReferenceIds: [evidenceId],
          }],
          evidenceReferenceIds: [],
        }],
      },
    },
  ), 201);
  submissionId = String(draft.submission.id);
  expect(draft.submission.completenessBlockers).toEqual([]);

  workspace = await json(await request.post(
    `/api/v1/certification/submissions/${submissionId}/submit`,
    {
      headers: versionedHeaders(
        tokens.vendor,
        draft.submission.version,
        "e2e-03-submit-partial",
      ),
      data: { expectedSubmissionVersion: draft.submission.version },
    },
  ), 200);
  expect(workspace.submission.status).toBe("UNDER_REVIEW");
  expect(workspace.submission.locked).toBe(true);

  const certified = await json(await request.post(
    `/api/v1/certification/submissions/${submissionId}/certifications`,
    {
      headers: versionedHeaders(
        tokens.productOwner,
        workspace.submission.version,
        "e2e-03-certify-partial",
      ),
      data: {
        expectedSubmissionVersion: workspace.submission.version,
        deliverableId: deliverableVersionId,
        decision: "PARTIALLY_ACCEPTED",
        comment: "Accepted the exact core scope with explicit carry-forward",
        observations: "Linear Done was evidence only, never the client decision",
        cause: "Joint scope sequencing",
        nextAction: "Complete and independently verify the hardening slice",
        acceptedScope: "The tested core workflow",
        rejectedScope: "The unverified hardening extension",
        carryForward: "Complete the bounded hardening extension in August",
        criterionResults: [{
          criterionId,
          decision: "PARTIALLY_MET",
          rationale: "The immutable report proves the accepted core only",
          evidenceViewed: true,
        }],
      },
    },
  ), 200);
  expect(certified.deliverables[0].certification).toMatchObject({
    decision: "PARTIALLY_ACCEPTED",
    carryForward: "Complete the bounded hardening extension in August",
    terminal: true,
  });

  const summarized = await json(await request.post(
    `/api/v1/certification/months/${monthId}/summaries`,
    {
      headers: versionedHeaders(
        tokens.productOwner,
        certified.version,
        "e2e-03-partial-summary",
      ),
      data: {
        expectedMonthVersion: certified.version,
        decision: "PARTIALLY_CERTIFIED",
        observations: "Exact partial decision with immutable August lineage",
      },
    },
  ), 201);
  summaryId = String(summarized.summary.id);
  expect(summarized.summary).toMatchObject({
    decision: "PARTIALLY_CERTIFIED",
    terminalItemCount: 1,
    totalItemCount: 1,
  });
  expect(summarized.summary.checksum).toMatch(/^[0-9a-f]{64}$/);
  await expect.poll(() => queryDatabase(`
    SELECT COUNT(*) FROM notification_outbox outbox
    WHERE outbox.business_object_id = '${summaryId}'::uuid
      AND outbox.event_type = 'CERTIFICATION_COMPLETED'
      AND outbox.business_object_version = 1
      AND outbox.transport_status = 'SENT'
      AND outbox.provider_message_id =
          'recorded-certification-' || outbox.id::text
      AND outbox.provider_thread_id =
          'recorded-certification-thread-${summaryId}'
      AND outbox.recipient_snapshot @> '{
        "to":[
          {"display":"alice@arrowfoundry.example"},
          {"display":"ravi@reliance.example"}
        ],
        "cc":[{"display":"governance@reliance.example"}]
      }'::jsonb
      AND outbox.plain_text LIKE '%${summarized.summary.checksum}%';
  `), { timeout: 15_000 }).toBe("1");

  const snapshots = await json(await request.get(
    `/api/v1/integrations/linear/links/${linearLinkId}/snapshots`,
    { headers: authorization(tokens.productOwner) },
  ), 200);
  expect(snapshots.map((value: { snapshotType: string }) => value.snapshotType))
    .toEqual(["PLAN_TIME", "MONTH_END"]);
  expect(snapshots[0].normalizedState).toBe("UNSTARTED");
  expect(snapshots[1].normalizedState).toBe("COMPLETED");
  expect(queryDatabase(`
    SELECT COUNT(*) FROM carry_forward_links
    WHERE origin_deliverable_version_id = '${deliverableVersionId}'::uuid
      AND target_engagement_month_id = '${nextMonthId}'::uuid;
  `)).toBe("1");
  expect(queryDatabase(`
    SELECT COUNT(*) FROM delivery_plan_baselines baseline
    JOIN commitment_outbox outbox ON outbox.baseline_id = baseline.id
    WHERE baseline.id = '${baselineId}'::uuid
      AND outbox.plan_version_id = '${planVersionId}'::uuid
      AND outbox.recipient_snapshot <> '{}'::jsonb
      AND outbox.archive_reference LIKE 'db://commitment-outbox/%';
  `)).toBe("1");
  expect(queryDatabase(`
    SELECT COUNT(*) FROM deliverable_certifications certification
    WHERE certification.submission_id = '${submissionId}'::uuid
      AND certification.authority_snapshot
          @> '{"resolvedServerSide":true}'::jsonb;
  `)).toBe("1");
});

test("[E2E-04] exact-version secure confirmation is single-use, audited and F05-ready", async ({
  request,
}) => {
  const attendance = await json(await request.post(
    "/api/v1/attendance/month-snapshots",
    {
      headers: authorization(tokens.vendor),
      data: { engagementMonthId: monthId },
    },
  ), 201);
  expect(attendance).toMatchObject({ status: "CLOSED" });
  expect(attendance.checksum).toMatch(/^[0-9a-f]{64}$/);

  const preRequestReadiness = await json(await request.get(
    `/api/v1/certification/months/${monthId}/readiness`,
    { headers: authorization(tokens.governance) },
  ), 200);
  for (const pillar of preRequestReadiness.pillars.filter(
    (value: { key: string }) => value.key !== "CONFIRMATION_HANDOFF",
  )) {
    expect(pillar.status, JSON.stringify(pillar.blockers)).toBe("READY");
  }

  const workspace = await json(await request.get(
    `/api/v1/certification/months/${monthId}`,
    { headers: authorization(tokens.governance) },
  ), 200);
  const dueAt = "2026-07-31T10:00:00.000Z";
  const confirmation = await json(await request.post(
    `/api/v1/certification/months/${monthId}/confirmation-requests`,
    {
      headers: versionedHeaders(
        tokens.governance,
        workspace.version,
        "e2e-04-exact-request",
      ),
      data: {
        expectedMonthVersion: workspace.version,
        dueAt,
      },
    },
  ), 201);
  firstConfirmationRequestId = String(confirmation.id);
  expect(confirmation).toMatchObject({
    version: 1,
    state: "AWAITING_RESPONSE",
    eligible: false,
    scopeSources: expect.any(Array),
  });
  const eligibleConfirmerView = await json(await request.get(
    `/api/v1/certification/confirmation-requests/${firstConfirmationRequestId}`,
    { headers: authorization(tokens.productOwner) },
  ), 200);
  expect(eligibleConfirmerView).toMatchObject({
    state: "AWAITING_RESPONSE",
    eligible: true,
  });
  expect(confirmation.scopeChecksum).toMatch(/^[0-9a-f]{64}$/);
  expect(confirmation.sourceVersionIds).toEqual(expect.arrayContaining([
    planVersionId,
    baselineId,
    summaryId,
  ]));
  expect(JSON.stringify(confirmation)).not.toMatch(
    /secureToken|tokenHash|tokenSalt/i,
  );
  await expect.poll(() => queryDatabase(`
    SELECT COUNT(*) FROM notification_outbox outbox
    WHERE outbox.business_object_id = '${firstConfirmationRequestId}'::uuid
      AND outbox.event_type = 'CONFIRMATION_REQUESTED'
      AND outbox.business_object_version = 1
      AND outbox.transport_status = 'SENT'
      AND outbox.provider_message_id =
          'recorded-certification-' || outbox.id::text
      AND outbox.provider_thread_id =
          'recorded-certification-thread-${firstConfirmationRequestId}'
      AND outbox.recipient_snapshot @> '{
        "to":[
          {"display":"alice@arrowfoundry.example"},
          {"display":"ravi@reliance.example"}
        ],
        "cc":[{"display":"governance@reliance.example"}]
      }'::jsonb;
  `), { timeout: 15_000 }).toBe("1");

  // This decrypts the fake provider handoff with its local-only configured
  // key. The action itself still traverses OAuth authorization, token hashing,
  // request/version binding, single-use consumption and PostgreSQL persistence.
  const secureToken = decryptRecordedToken(firstConfirmationRequestId);
  const actionBody = {
    expectedRequestVersion: confirmation.version,
    decision: "CONFIRM",
    comment: "Confirmed the exact immutable consolidated scope",
    projectId,
    secureToken,
  };
  const confirmed = await json(await request.post(
    `/api/v1/certification/confirmation-requests/${firstConfirmationRequestId}/actions`,
    {
      headers: versionedHeaders(
        tokens.productOwner,
        confirmation.version,
        "e2e-04-secure-confirm",
      ),
      data: actionBody,
    },
  ), 200);
  expect(confirmed.state).toBe("CONFIRMED");
  expect(confirmed.actions).toHaveLength(1);
  expect(confirmed.actions[0]).toMatchObject({
    decision: "CONFIRM",
    source: "SECURE_LINK",
  });

  const idempotentReplay = await json(await request.post(
    `/api/v1/certification/confirmation-requests/${firstConfirmationRequestId}/actions`,
    {
      headers: versionedHeaders(
        tokens.productOwner,
        confirmation.version,
        "e2e-04-secure-confirm",
      ),
      data: actionBody,
    },
  ), 200);
  expect(idempotentReplay.actions[0].id).toBe(confirmed.actions[0].id);

  await expectStatus(request.post(
    `/api/v1/certification/confirmation-requests/${firstConfirmationRequestId}/actions`,
    {
      headers: versionedHeaders(
        tokens.productOwner,
        confirmation.version,
        "e2e-04-token-replay-denied",
      ),
      data: actionBody,
    },
  ), 409);
  expect(queryDatabase(`
    SELECT COUNT(*) FROM business_confirmation_actions
    WHERE request_id = '${firstConfirmationRequestId}'::uuid;
  `)).toBe("1");
  expect(queryDatabase(`
    SELECT COUNT(*) FROM certification_security_events
    WHERE object_id = '${firstConfirmationRequestId}'::uuid
      AND event_type = 'CONFIRMATION_ACTION_REJECTED'
      AND outcome = 'DENIED'
      AND redacted_facts
          @> '{"reasonCode":"REQUEST_NOT_AWAITING_RESPONSE"}'::jsonb;
  `)).toBe("1");
  expect(queryDatabase(`
    SELECT COUNT(*) FROM confirmation_secure_tokens
    WHERE request_id = '${firstConfirmationRequestId}'::uuid
      AND consumed_at IS NOT NULL
      AND consumed_by_subject = 'user-reliance';
  `)).toBe("1");
  expect(queryDatabase(`
    SELECT COUNT(*) FROM f05_certification_handoffs
    WHERE confirmation_request_id = '${firstConfirmationRequestId}'::uuid
      AND package_hash ~ '^[0-9a-f]{64}$';
  `)).toBe("1");
  await expect.poll(() => queryDatabase(`
    SELECT COUNT(*) FROM notification_outbox outbox
    WHERE outbox.business_object_id = '${firstConfirmationRequestId}'::uuid
      AND outbox.event_type = 'CONFIRMATION_OUTCOME_RECORDED'
      AND outbox.business_object_version = 1
      AND outbox.transport_status = 'SENT'
      AND outbox.attempt_count = 1
      AND outbox.provider_message_id =
          'recorded-certification-' || outbox.id::text
      AND outbox.provider_thread_id =
          'recorded-certification-thread-${firstConfirmationRequestId}'
      AND outbox.recipient_snapshot @> '{
        "to":[
          {"display":"alice@arrowfoundry.example"},
          {"display":"ravi@reliance.example"}
        ],
        "cc":[{"display":"governance@reliance.example"}]
      }'::jsonb;
  `), { timeout: 15_000 }).toBe("1");

  const readiness = await json(await request.get(
    `/api/v1/certification/months/${monthId}/readiness`,
    { headers: authorization(tokens.governance) },
  ), 200);
  expect(readiness).toMatchObject({
    status: "READY",
    f05HandoffStatus: "ELIGIBLE",
    blockers: [],
  });
});

test("[E2E-05] only a signed eligible explicit thread reply completes quorum; spoof, ambiguity and replay do not", async ({
  request,
}) => {
  let workspace = await json(await request.get(
    `/api/v1/certification/months/${monthId}`,
    { headers: authorization(tokens.productOwner) },
  ), 200);
  const reopened = await json(await request.post(
    `/api/v1/certification/months/${monthId}/reopen-requests`,
    {
      headers: versionedHeaders(
        tokens.productOwner,
        workspace.version,
        "e2e-05-reopen-request",
      ),
      data: {
        expectedMonthVersion: workspace.version,
        category: "CERTIFICATION_CORRECTION",
        reason: "Exercise a separately governed inbound confirmation journey",
        impactedRecordIds: [summaryId],
        packageInvoiceImpact: "CONFIRMATION_HANDOFF_CURRENT",
        riskStatement: "The prior handoff must be fenced before reconfirmation",
      },
    },
  ), 201);
  expect(reopened.lifecycleState).toBe("REOPEN_REQUESTED");
  const reopenRequestId = queryDatabase(`
    SELECT id FROM month_reopen_requests
    WHERE engagement_month_id = '${monthId}'::uuid AND status = 'REQUESTED';
  `);
  const invalidationId = queryDatabase(`
    SELECT id FROM certification_invalidations
    WHERE reopen_request_id = '${reopenRequestId}'::uuid
      AND object_id = '${summaryId}'::uuid;
  `);

  workspace = await json(await request.get(
    `/api/v1/certification/months/${monthId}`,
    { headers: authorization(tokens.governance) },
  ), 200);
  await json(await request.post(
    `/api/v1/certification/reopen-requests/${reopenRequestId}/decisions`,
    {
      headers: versionedHeaders(
        tokens.governance,
        workspace.version,
        "e2e-05-reopen-approve",
      ),
      data: {
        expectedMonthVersion: workspace.version,
        decision: "APPROVE",
        reasoning: "Independent governance approved the reconfirmation journey",
      },
    },
  ), 201);

  workspace = await json(await request.get(
    `/api/v1/certification/months/${monthId}`,
    { headers: authorization(tokens.productOwner) },
  ), 200);
  const recertified = await json(await request.post(
    `/api/v1/certification/months/${monthId}/summaries`,
    {
      headers: versionedHeaders(
        tokens.productOwner,
        workspace.version,
        "e2e-05-recertify-summary",
      ),
      data: {
        expectedMonthVersion: workspace.version,
        decision: "PARTIALLY_CERTIFIED",
        observations: "Re-certified unchanged scope for inbound confirmation",
      },
    },
  ), 201);
  summaryId = String(recertified.summary.id);

  workspace = await json(await request.get(
    `/api/v1/certification/months/${monthId}`,
    { headers: authorization(tokens.governance) },
  ), 200);
  const confirmation = await json(await request.post(
    `/api/v1/certification/months/${monthId}/confirmation-requests`,
    {
      headers: versionedHeaders(
        tokens.governance,
        workspace.version,
        "e2e-05-inbound-request",
      ),
      data: {
        expectedMonthVersion: workspace.version,
        dueAt: "2026-07-31T10:00:00.000Z",
      },
    },
  ), 201);
  secondConfirmationRequestId = String(confirmation.id);
  expect(confirmation).toMatchObject({
    version: 2,
    state: "AWAITING_RESPONSE",
  });
  await expect.poll(() => queryDatabase(`
    SELECT COUNT(*) FROM notification_outbox outbox
    WHERE outbox.business_object_id = '${secondConfirmationRequestId}'::uuid
      AND outbox.event_type = 'CONFIRMATION_REQUESTED'
      AND outbox.business_object_version = 2
      AND outbox.transport_status = 'SENT'
      AND outbox.provider_message_id IS NOT NULL
      AND outbox.provider_thread_id =
          'recorded-certification-thread-${secondConfirmationRequestId}';
  `), { timeout: 15_000 }).toBe("1");
  const requestProviderMessageId = queryDatabase(`
    SELECT provider_message_id FROM notification_outbox
    WHERE business_object_id = '${secondConfirmationRequestId}'::uuid
      AND event_type = 'CONFIRMATION_REQUESTED';
  `);
  const requestProviderThreadId = queryDatabase(`
    SELECT provider_thread_id FROM notification_outbox
    WHERE business_object_id = '${secondConfirmationRequestId}'::uuid
      AND event_type = 'CONFIRMATION_REQUESTED';
  `);

  workspace = await json(await request.get(
    `/api/v1/certification/months/${monthId}`,
    { headers: authorization(tokens.governance) },
  ), 200);
  const validInput = inboundInput({
    expectedMonthVersion: workspace.version,
    requestId: secondConfirmationRequestId,
    fingerprint: "f07-e2e-05-valid-explicit",
    messageId: "<f07-valid@provider.example>",
    threadId: requestProviderThreadId,
    sender: "ravi@reliance.example",
    intent: "EXPLICIT_CONFIRM",
    authentication: { spf: "PASS", dkim: "PASS", dmarc: "PASS" },
    rawReference: "test/f04/immutable-test-report.pdf",
    rawSha256: "9".repeat(64),
    inReplyToHash: sha256(requestProviderMessageId),
    referencesHash: sha256(requestProviderThreadId),
  });
  const valid = await recordInbound(
    request,
    validInput,
    "e2e-05-valid-explicit",
  );
  expect(valid).toMatchObject({
    source: "VERIFIED_REPLY",
    authenticationConfidence: "VERIFIED",
    reviewStatus: "PENDING",
    senderEligibility: "ELIGIBLE",
  });
  expect(queryDatabase(`
    SELECT status FROM business_confirmation_requests
    WHERE id = '${secondConfirmationRequestId}'::uuid;
  `)).toBe("AWAITING_RESPONSE");

  workspace = await json(await request.get(
    `/api/v1/certification/months/${monthId}`,
    { headers: authorization(tokens.governance) },
  ), 200);
  const spoofInput = inboundInput({
    expectedMonthVersion: workspace.version,
    requestId: secondConfirmationRequestId,
    fingerprint: "f07-e2e-05-spoofed-reply",
    messageId: "<f07-spoof@provider.example>",
    threadId: requestProviderThreadId,
    sender: "attacker@example.test",
    intent: "EXPLICIT_CONFIRM",
    authentication: { spf: "FAIL", dkim: "FAIL", dmarc: "FAIL" },
    inReplyToHash: sha256(requestProviderMessageId),
    referencesHash: sha256(requestProviderThreadId),
  });
  const spoof = await recordInbound(
    request,
    spoofInput,
    "e2e-05-spoofed-reply",
  );
  expect(spoof).toMatchObject({
    source: "QUARANTINED",
    authenticationConfidence: "FAILED",
    senderEligibility: "INELIGIBLE",
  });

  workspace = await json(await request.get(
    `/api/v1/certification/months/${monthId}`,
    { headers: authorization(tokens.governance) },
  ), 200);
  const ambiguousInput = inboundInput({
    expectedMonthVersion: workspace.version,
    requestId: secondConfirmationRequestId,
    fingerprint: "f07-e2e-05-ambiguous-reply",
    messageId: "<f07-ambiguous@provider.example>",
    threadId: requestProviderThreadId,
    sender: "ravi@reliance.example",
    intent: "AMBIGUOUS",
    authentication: { spf: "PASS", dkim: "PASS", dmarc: "PASS" },
    inReplyToHash: sha256(requestProviderMessageId),
    referencesHash: sha256(requestProviderThreadId),
  });
  const ambiguous = await recordInbound(
    request,
    ambiguousInput,
    "e2e-05-ambiguous-reply",
  );
  expect(ambiguous).toMatchObject({
    source: "QUARANTINED",
    authenticationConfidence: "VERIFIED",
    senderEligibility: "ELIGIBLE",
  });

  workspace = await json(await request.get(
    `/api/v1/certification/months/${monthId}`,
    { headers: authorization(tokens.governance) },
  ), 200);
  const autoReply = await recordInbound(
    request,
    inboundInput({
      expectedMonthVersion: workspace.version,
      requestId: secondConfirmationRequestId,
      fingerprint: "f07-e2e-05-automated-reply",
      messageId: "<f07-auto-reply@provider.example>",
      threadId: requestProviderThreadId,
      sender: "ravi@reliance.example",
      intent: "AUTO_REPLY",
      authentication: { spf: "PASS", dkim: "PASS", dmarc: "PASS" },
      inReplyToHash: sha256(requestProviderMessageId),
      referencesHash: sha256(requestProviderThreadId),
    }),
    "e2e-05-automated-reply",
  );
  expect(autoReply).toMatchObject({
    source: "QUARANTINED",
    authenticationConfidence: "VERIFIED",
    senderEligibility: "ELIGIBLE",
  });
  expect(queryDatabase(`
    SELECT COUNT(*) FROM certification_security_events
    WHERE object_id = '${autoReply.id}'::uuid
      AND event_type = 'INBOUND_MESSAGE_QUARANTINED'
      AND outcome = 'QUARANTINED'
      AND redacted_facts @> '{"classifiedIntent":"AUTO_REPLY"}'::jsonb;
  `)).toBe("1");

  const validReplay = await recordInbound(
    request,
    validInput,
    "e2e-05-valid-explicit",
  );
  expect(validReplay.id).toBe(valid.id);
  expect(queryDatabase(`
    SELECT COUNT(*) FROM inbound_confirmation_messages
    WHERE request_id = '${secondConfirmationRequestId}'::uuid;
  `)).toBe("4");

  const reviewedSpoof = await json(await request.post(
    `/api/v1/certification/inbound-messages/${spoof.id}/reviews`,
    {
      headers: versionedHeaders(
        tokens.reviewer,
        spoof.version,
        "e2e-05-quarantine-spoof",
      ),
      data: {
        expectedReviewVersion: spoof.version,
        decision: "QUARANTINE",
        reasoning: "Authentication failed and the sender was not eligible",
      },
    },
  ), 201);
  expect(reviewedSpoof.reviewStatus).toBe("QUARANTINED");

  const reviewedAmbiguous = await json(await request.post(
    `/api/v1/certification/inbound-messages/${ambiguous.id}/reviews`,
    {
      headers: versionedHeaders(
        tokens.reviewer,
        ambiguous.version,
        "e2e-05-quarantine-ambiguous",
      ),
      data: {
        expectedReviewVersion: ambiguous.version,
        decision: "QUARANTINE",
        reasoning: "Authenticated transport did not contain explicit intent",
      },
    },
  ), 201);
  expect(reviewedAmbiguous.reviewStatus).toBe("QUARANTINED");

  const reviewedValid = await json(await request.post(
    `/api/v1/certification/inbound-messages/${valid.id}/reviews`,
    {
      headers: versionedHeaders(
        tokens.reviewer,
        valid.version,
        "e2e-05-accept-explicit",
      ),
      data: {
        expectedReviewVersion: valid.version,
        decision: "ACCEPT_INTERPRETATION",
        reasoning: "SPF, DKIM, DMARC, captured sender and reply thread all match",
      },
    },
  ), 201);
  expect(reviewedValid).toMatchObject({
    reviewStatus: "APPROVED",
    senderEligibility: "ELIGIBLE",
  });

  const outcome = await json(await request.get(
    `/api/v1/certification/confirmation-requests/${secondConfirmationRequestId}`,
    { headers: authorization(tokens.governance) },
  ), 200);
  expect(outcome.state).toBe("CONFIRMED");
  expect(outcome.actions).toHaveLength(1);
  expect(outcome.actions[0]).toMatchObject({
    decision: "CONFIRM",
    source: "VERIFIED_REPLY",
  });
  expect(queryDatabase(`
    SELECT COUNT(*) FROM reviewed_confirmation_action_promotions promotion
    JOIN business_confirmation_actions action
      ON action.id = promotion.action_id
    WHERE promotion.request_id = '${secondConfirmationRequestId}'::uuid
      AND promotion.source_type = 'INBOUND_MESSAGE'
      AND action.verification_status = 'VERIFIED';
  `)).toBe("1");
  expect(queryDatabase(`
    SELECT COUNT(*) FROM inbound_confirmation_messages message
    JOIN evidence_artifacts artifact
      ON artifact.object_key = message.raw_reference
     AND artifact.sha256 = message.raw_sha256
    WHERE message.id = '${valid.id}'::uuid
      AND message.provider_message_id = '<f07-valid@provider.example>'
      AND message.provider_thread_id = '${requestProviderThreadId}'
      AND message.in_reply_to_hash IS NOT NULL
      AND message.references_hash IS NOT NULL
      AND artifact.scan_status = 'PASSED';
  `)).toBe("1");
  expect(queryDatabase(`
    SELECT COUNT(*) FROM business_confirmation_actions
    WHERE request_id = '${secondConfirmationRequestId}'::uuid;
  `)).toBe("1");
  workspace = await json(await request.get(
    `/api/v1/certification/months/${monthId}`,
    { headers: authorization(tokens.governance) },
  ), 200);
  await json(await request.post(
    `/api/v1/certification/invalidations/${invalidationId}/resolutions`,
    {
      headers: versionedHeaders(
        tokens.governance,
        workspace.version,
        "e2e-05-clear-invalidation",
      ),
      data: {
        expectedMonthVersion: workspace.version,
        resolution: "CLEARED",
        reasoning: "The unchanged scope received a fresh governed confirmation",
      },
    },
  ), 201);
  expect(queryDatabase(`
    SELECT COUNT(*) FROM f05_certification_handoffs
    WHERE confirmation_request_id = '${secondConfirmationRequestId}'::uuid;
  `)).toBe("1");
});

function requiredEnvironment(name: string) {
  const value = process.env[name];
  if (!value) {
    throw new Error(`${name} is required for F07 delivery system E2E.`);
  }
  return value;
}

function authorization(token: string) {
  return { Authorization: `Bearer ${token}` };
}

function versionedHeaders(
  token: string,
  version: number,
  idempotencyKey: string,
) {
  return {
    ...authorization(token),
    "If-Match": String(version),
    "Idempotency-Key": idempotencyKey,
  };
}

async function json(response: APIResponse, expectedStatus: number) {
  const body = await response.text();
  expect(response.status(), body).toBe(expectedStatus);
  return body ? JSON.parse(body) : null;
}

async function expectStatus(
  responsePromise: Promise<APIResponse>,
  expectedStatus: number,
) {
  const response = await responsePromise;
  expect(response.status(), await response.text()).toBe(expectedStatus);
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

function decryptRecordedToken(requestId: string) {
  const encoded = queryDatabase(`
    SELECT json_build_object(
      'tokenId', handoff.token_id,
      'outboxId', handoff.outbox_id,
      'ciphertext', encode(handoff.encrypted_token, 'base64'),
      'nonce', encode(handoff.nonce, 'base64'),
      'keyVersion', handoff.key_version
    )::text
    FROM confirmation_token_handoffs handoff
    WHERE handoff.request_id = '${requestId}'::uuid
    ORDER BY handoff.created_at
    LIMIT 1;
  `);
  const handoff = JSON.parse(encoded) as {
    tokenId: string;
    outboxId: string;
    ciphertext: string;
    nonce: string;
    keyVersion: string;
  };
  const encrypted = Buffer.from(handoff.ciphertext, "base64");
  const tag = encrypted.subarray(encrypted.length - 16);
  const ciphertext = encrypted.subarray(0, encrypted.length - 16);
  const decipher = createDecipheriv(
    "aes-256-gcm",
    Buffer.from("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "base64"),
    Buffer.from(handoff.nonce, "base64"),
  );
  decipher.setAAD(Buffer.from(
    `${handoff.keyVersion}:${handoff.tokenId}:${requestId}:${handoff.outboxId}`,
  ));
  decipher.setAuthTag(tag);
  return Buffer.concat([
    decipher.update(ciphertext),
    decipher.final(),
  ]).toString("utf8");
}

type InboundAuthentication = {
  spf: "PASS" | "FAIL" | "UNAVAILABLE";
  dkim: "PASS" | "FAIL" | "UNAVAILABLE";
  dmarc: "PASS" | "FAIL" | "UNAVAILABLE";
};

type InboundOptions = {
  expectedMonthVersion: number;
  requestId: string;
  fingerprint: string;
  messageId: string;
  threadId: string;
  sender: string;
  intent:
    | "EXPLICIT_CONFIRM"
    | "EXPLICIT_CORRECTION"
    | "EXPLICIT_REJECT"
    | "AMBIGUOUS"
    | "AUTO_REPLY";
  authentication: InboundAuthentication;
  rawReference?: string;
  rawSha256?: string;
  inReplyToHash?: string;
  referencesHash?: string;
};

function inboundInput(options: InboundOptions) {
  return {
    expectedMonthVersion: options.expectedMonthVersion,
    requestId: options.requestId,
    providerMessageFingerprint: options.fingerprint,
    providerMessageId: options.messageId,
    providerThreadId: options.threadId,
    senderAddress: options.sender,
    rawReference: options.rawReference,
    rawSha256: options.rawSha256,
    inReplyToHash: options.inReplyToHash,
    referencesHash: options.referencesHash,
    authentication: options.authentication,
    classifiedIntent: options.intent,
    providerReceivedAt: "2026-07-29T09:59:59.000Z",
  };
}

async function recordInbound(
  request: Parameters<Parameters<typeof test>[1]>[0]["request"],
  input: ReturnType<typeof inboundInput>,
  idempotencyKey: string,
) {
  const timestamp = systemEpochSeconds;
  const signaturePayload = [
    "f04-inbound-signature-v1",
    String(timestamp),
    monthId,
    input.requestId,
    input.providerMessageFingerprint,
    input.senderAddress.trim().toLowerCase(),
    input.rawSha256 ?? "",
    input.classifiedIntent,
    new Date(input.providerReceivedAt).toISOString().replace(/\.000Z$/, "Z"),
  ].join("\n");
  return json(await request.post(
    `/api/v1/certification/months/${monthId}/inbound-messages`,
    {
      headers: {
        ...versionedHeaders(
          tokens.inbound,
          input.expectedMonthVersion,
          idempotencyKey,
        ),
        "X-VMS-Inbound-Timestamp": String(timestamp),
        "X-VMS-Inbound-Signature": `v1=${createHmac(
          "sha256",
          Buffer.alloc(32),
        ).update(signaturePayload).digest("hex")}`,
      },
      data: input,
    },
  ), 201);
}

function sha256(value: string) {
  return createHash("sha256").update(value).digest("hex");
}
