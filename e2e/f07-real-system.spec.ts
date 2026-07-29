import { expect, test, type APIResponse } from "@playwright/test";
import { createHash, createHmac } from "node:crypto";
import { execFileSync, spawnSync } from "node:child_process";

import "./fixtures/quality-gates";

const monthId = "00000000-0000-0000-0000-000000000602";
const clientOrganizationId = "00000000-0000-0000-0000-000000000102";
const engagementId = "00000000-0000-0000-0000-000000000401";
const projectId = "00000000-0000-0000-0000-000000000501";
const linearConnectionId = "00000000-0000-0000-0000-000000001101";
const greytHrConnectionId = "72000000-0000-0000-0000-000000000010";
const postgresContainer = requiredEnvironment("VMS_E2E_POSTGRES_CONTAINER");

const tokens = {
  vendor: requiredEnvironment("VMS_E2E_TOKEN_USER_ARROW"),
  e2eEmployee: requiredEnvironment("VMS_E2E_TOKEN_USER_E2E_EMPLOYEE"),
  productOwner: requiredEnvironment("VMS_E2E_TOKEN_USER_RELIANCE"),
  governance: requiredEnvironment("VMS_E2E_TOKEN_USER_GOVERNANCE"),
};

test.describe.configure({ mode: "serial" });

test("[E2E-07] post-close correction regenerates exact snapshot, confirmation, package and readiness lineage", async ({
  request,
}) => {
  const employeeIdForCorrection = queryDatabase(`
    SELECT id FROM employees WHERE employee_number = 'AF-E2E-074';
  `);
  const originalSnapshotId = queryDatabase(`
    SELECT id FROM attendance_snapshot_versions
    WHERE engagement_month_id = '${monthId}'::uuid
    ORDER BY version DESC LIMIT 1;
  `);
  const originalSnapshotVersion = Number(queryDatabase(`
    SELECT version FROM attendance_snapshot_versions
    WHERE id = '${originalSnapshotId}'::uuid;
  `));
  const originalSnapshotChecksum = queryDatabase(`
    SELECT checksum FROM attendance_snapshot_versions
    WHERE id = '${originalSnapshotId}'::uuid;
  `);
  const originalSummaryId = queryDatabase(`
    SELECT id FROM monthly_certification_summaries
    WHERE engagement_month_id = '${monthId}'::uuid AND status = 'CURRENT';
  `);
  const originalRequestId = queryDatabase(`
    SELECT id FROM business_confirmation_requests
    WHERE engagement_month_id = '${monthId}'::uuid AND status = 'CONFIRMED'
    ORDER BY version DESC LIMIT 1;
  `);
  const originalReadinessRunId = queryDatabase(`
    SELECT readiness_run_id FROM f05_certification_handoffs
    WHERE confirmation_request_id = '${originalRequestId}'::uuid;
  `);

  let certification = await json(await request.get(
    `/api/v1/certification/months/${monthId}`,
    { headers: authorization(tokens.governance) },
  ), 200);
  const closure = await json(await request.post(
    `/api/v1/certification/months/${monthId}/closures`,
    {
      headers: versionedHeaders(
        tokens.governance,
        certification.version,
        "e2e-07-close",
      ),
      data: { expectedMonthVersion: certification.version },
    },
  ), 201);
  expect(closure).toMatchObject({
    status: "CURRENT",
    confirmationRequestId: originalRequestId,
  });

  const invoice = await json(await request.post("/api/v1/finance/invoices", {
    headers: mutationHeaders(tokens.vendor, "e2e-07-invoice-create"),
    data: {
      monthId,
      documentKind: "PRIMARY",
      relatedInvoiceId: null,
      representedMetadata: {
        invoiceNumber: "F07 POST CLOSE 001",
        invoiceDate: "2026-07-31",
        billingPeriodStart: "2026-07-01",
        billingPeriodEnd: "2026-07-31",
        currency: "INR",
        taxableValue: "100.00",
        taxValue: "18.00",
        totalValue: "118.00",
        purchaseOrderReference: "PO-F07-POST-CLOSE",
        workOrderReference: "WO-F07-POST-CLOSE",
      },
    },
  }), 201);
  const invoiceId = String(invoice.invoiceId);
  let invoiceVersion = Number(invoice.version);
  const uploadMetadata = JSON.stringify({
    expectedVersion: invoiceVersion,
    classification: "CONFIDENTIAL",
    retentionPolicy: "FINANCE_EVIDENCE",
    source: "VENDOR_UPLOAD",
    reason: "Exact pre-correction invoice evidence",
  });
  const uploaded = await json(await request.post(
    `/api/v1/finance/invoices/${invoiceId}/documents`,
    {
      headers: {
        ...authorization(tokens.vendor),
        "If-Match": String(invoiceVersion),
        "Idempotency-Key": "e2e-07-invoice-upload",
      },
      multipart: {
        file: {
          name: "f07-post-close.pdf",
          mimeType: "application/pdf",
          buffer: Buffer.from("%PDF-1.7\nF07 post-close evidence\n%%EOF"),
        },
        metadata: {
          name: "metadata.json",
          mimeType: "application/json",
          buffer: Buffer.from(uploadMetadata),
        },
      },
    },
  ), 200);
  invoiceVersion = Number(uploaded.version);
  expect(uploaded.currentDocument.scanStatus).toBe("PASSED");

  const packageV1 = await json(await request.post(
    `/api/v1/finance/months/${monthId}/packages`,
    {
      headers: {
        ...mutationHeaders(tokens.vendor, "e2e-07-package-v1"),
        "If-Match": "1",
      },
      data: {
        expectedMonthVersion: 1,
        readinessRunId: originalReadinessRunId,
        reason: "Freeze exact pre-correction package",
      },
    },
  ), 200);
  expect(packageV1).toMatchObject({
    version: 1,
    state: "AVAILABLE",
    current: true,
  });
  const originalPackageHash = String(packageV1.canonicalInputHash);
  expect(originalPackageHash).toMatch(/^[0-9a-f]{64}$/);
  expect(queryDatabase(`
    SELECT COUNT(*) FROM evidence_package_items
    WHERE package_version_id = '${packageV1.packageId}'::uuid
      AND item_type = 'ATTENDANCE'
      AND source_object_id = '${originalSnapshotId}'::uuid;
  `)).toBe("1");
  const e2eEmployeeId = queryDatabase(`
    SELECT id FROM employees WHERE employee_number = 'AF-E2E-074';
  `);
  expect(queryDatabase(`
    SELECT COUNT(*) FROM evidence_package_items item
    JOIN attendance_snapshot_days day
      ON day.snapshot_id = item.source_object_id
    WHERE item.package_version_id = '${packageV1.packageId}'::uuid
      AND item.item_type = 'ATTENDANCE'
      AND day.employee_id = '${e2eEmployeeId}'::uuid
      AND day.work_date = '2026-07-29'
      AND day.net_minutes = 540
      AND day.final_status = 'PRESENT_FULL_DAY';
  `)).toBe("1");
  const packageV1View = await json(await request.get(
    `/api/v1/finance/packages/${packageV1.packageId}`,
    { headers: authorization(tokens.vendor) },
  ), 200);
  expect(packageV1View.integrityVerified).toBe(true);
  expect(packageV1View.manifestItems).toEqual(expect.arrayContaining([
    expect.objectContaining({
      source: expect.objectContaining({
        sourceId: originalSnapshotId,
      }),
    }),
  ]));
  expect(JSON.stringify(packageV1View)).not.toMatch(
    /salary|payroll|compensation|ctc|markup|employeeRate|bankAccount/i,
  );
  const readinessV1 = await json(await request.post(
    `/api/v1/finance/invoices/${invoiceId}/readiness-runs`,
    {
      headers: {
        ...mutationHeaders(tokens.vendor, "e2e-07-readiness-v1"),
        "If-Match": String(invoiceVersion),
      },
      data: { expectedVersion: invoiceVersion },
    },
  ), 200);
  invoiceVersion = Number(readinessV1.version);
  expect(readinessV1.readiness.eligibleForSubmission).toBe(true);

  certification = await json(await request.get(
    `/api/v1/certification/months/${monthId}`,
    { headers: authorization(tokens.productOwner) },
  ), 200);
  const reopened = await json(await request.post(
    `/api/v1/certification/months/${monthId}/reopen-requests`,
    {
      headers: versionedHeaders(
        tokens.productOwner,
        certification.version,
        "e2e-07-reopen-request",
      ),
      data: {
        expectedMonthVersion: certification.version,
        category: "ATTENDANCE_CORRECTION",
        reason: "A closed attendance day needs an additive approved correction",
        impactedRecordIds: [originalSummaryId],
        packageInvoiceImpact: "PACKAGE_AND_INVOICE_CURRENT",
        riskStatement: "Prior confirmation, package and readiness must be fenced",
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
      AND object_id = '${originalSummaryId}'::uuid;
  `);

  certification = await json(await request.get(
    `/api/v1/certification/months/${monthId}`,
    { headers: authorization(tokens.governance) },
  ), 200);
  await json(await request.post(
    `/api/v1/certification/reopen-requests/${reopenRequestId}/decisions`,
    {
      headers: versionedHeaders(
        tokens.governance,
        certification.version,
        "e2e-07-reopen-approve",
      ),
      data: {
        expectedMonthVersion: certification.version,
        decision: "APPROVE",
        reasoning: "Independent governance approved additive attendance correction",
      },
    },
  ), 201);
  expect(queryDatabase(`
    SELECT status FROM month_closures WHERE id = '${closure.id}'::uuid;
  `)).toBe("SUPERSEDED");
  expect(queryDatabase(`
    SELECT status FROM evidence_package_versions
    WHERE id = '${packageV1.packageId}'::uuid;
  `)).toBe("INVALIDATED");
  expect(queryDatabase(`
    SELECT status FROM invoices WHERE id = '${invoiceId}'::uuid;
  `)).toBe("EVIDENCE_PENDING");
  expect(queryDatabase(`
    SELECT current_result FROM invoice_readiness_runs
    WHERE id = '${readinessV1.readiness.runId}'::uuid;
  `)).toBe("f");
  expect(queryDatabase(`
    SELECT effective_status FROM effective_f05_certification_handoffs
    WHERE confirmation_request_id = '${originalRequestId}'::uuid;
  `)).toBe("INVALIDATED");

  const reopenedAttendance = await json(await request.post(
    `/api/v1/attendance/month-snapshots/${originalSnapshotId}/reopen`,
    {
      headers: authorization(tokens.governance),
      data: { reason: "Approved post-close attendance correction" },
    },
  ), 201);
  expect(reopenedAttendance).toMatchObject({
    status: "REOPENED",
    supersedesId: originalSnapshotId,
  });

  const correction = await json(await request.post(
    "/api/v1/attendance/regularizations",
    {
      headers: authorization(tokens.e2eEmployee),
      data: {
        employeeId: employeeIdForCorrection,
        workDate: "2026-07-28",
        reasonCode: "POST_CLOSE_APPROVED_CORRECTION",
        narrative: "Approved evidence establishes a full working day",
        requestedOutcome: "FULL_DAY_PRESENT",
        idempotencyKey: "e2e-07-regularization",
      },
    },
  ), 201);
  await json(await request.post(
    `/api/v1/attendance/regularizations/${correction.id}/decisions`,
    {
      headers: authorization(tokens.vendor),
      data: {
        decision: "APPROVE",
        adjustedNetMinutes: 540,
        reasoning: "Reviewed post-close evidence and approved exact minutes",
      },
    },
  ), 201);
  const correctedSnapshot = await json(await request.post(
    "/api/v1/attendance/month-snapshots",
    {
      headers: authorization(tokens.vendor),
      data: { engagementMonthId: monthId },
    },
  ), 201);
  expect(correctedSnapshot).toMatchObject({
    version: originalSnapshotVersion + 2,
    status: "CLOSED",
    supersedesId: reopenedAttendance.id,
  });
  expect(correctedSnapshot.checksum).not.toBe(originalSnapshotChecksum);
  expect(queryDatabase(`
    SELECT COUNT(*) FROM attendance_snapshot_days
    WHERE snapshot_id = '${correctedSnapshot.id}'::uuid
      AND employee_id = '${employeeIdForCorrection}'::uuid
      AND work_date = '2026-07-28'
      AND final_status = 'PRESENT_FULL_DAY'
      AND net_minutes = 540;
  `)).toBe("1");

  certification = await json(await request.get(
    `/api/v1/certification/months/${monthId}`,
    { headers: authorization(tokens.productOwner) },
  ), 200);
  const summarized = await json(await request.post(
    `/api/v1/certification/months/${monthId}/summaries`,
    {
      headers: versionedHeaders(
        tokens.productOwner,
        certification.version,
        "e2e-07-corrected-summary",
      ),
      data: {
        expectedMonthVersion: certification.version,
        decision: "PARTIALLY_CERTIFIED",
        observations:
          "Recertified after approved attendance correction and snapshot regeneration",
      },
    },
  ), 201);
  const correctedSummaryId = String(summarized.summary.id);
  expect(summarized.summary).toMatchObject({
    version: 2,
  });
  expect(queryDatabase(`
    SELECT supersedes_id FROM monthly_certification_summaries
    WHERE id = '${correctedSummaryId}'::uuid;
  `)).toBe(originalSummaryId);

  certification = await json(await request.get(
    `/api/v1/certification/months/${monthId}`,
    { headers: authorization(tokens.governance) },
  ), 200);
  const correctedRequest = await json(await request.post(
    `/api/v1/certification/months/${monthId}/confirmation-requests`,
    {
      headers: versionedHeaders(
        tokens.governance,
        certification.version,
        "e2e-07-corrected-confirmation",
      ),
      data: {
        expectedMonthVersion: certification.version,
        dueAt: "2026-07-31T10:00:00Z",
      },
    },
  ), 201);
  expect(correctedRequest).toMatchObject({
    version: 3,
  });
  expect(correctedRequest.sourceVersionIds).toEqual(expect.arrayContaining([
    String(correctedSnapshot.id),
    correctedSummaryId,
  ]));
  expect(queryDatabase(`
    SELECT COUNT(*) FROM business_confirmation_requests
    WHERE id = '${correctedRequest.id}'::uuid
      AND attendance_snapshot_id = '${correctedSnapshot.id}'::uuid
      AND certification_summary_id = '${correctedSummaryId}'::uuid
      AND supersedes_id = '${originalRequestId}'::uuid;
  `)).toBe("1");
  const confirmed = await json(await request.post(
    `/api/v1/certification/confirmation-requests/${correctedRequest.id}/actions`,
    {
      headers: versionedHeaders(
        tokens.productOwner,
        correctedRequest.version,
        "e2e-07-confirm-corrected",
      ),
      data: {
        expectedRequestVersion: correctedRequest.version,
        decision: "CONFIRM",
        comment: "Confirmed the corrected exact attendance and certification scope",
        projectId,
      },
    },
  ), 200);
  expect(confirmed.state).toBe("CONFIRMED");

  certification = await json(await request.get(
    `/api/v1/certification/months/${monthId}`,
    { headers: authorization(tokens.governance) },
  ), 200);
  await json(await request.post(
    `/api/v1/certification/invalidations/${invalidationId}/resolutions`,
    {
      headers: versionedHeaders(
        tokens.governance,
        certification.version,
        "e2e-07-clear-invalidation",
      ),
      data: {
        expectedMonthVersion: certification.version,
        resolution: "CLEARED",
        reasoning: "Exact corrected summary and snapshot received fresh confirmation",
      },
    },
  ), 201);

  const correctedReadinessRunId = queryDatabase(`
    SELECT readiness_run_id FROM f05_certification_handoffs
    WHERE confirmation_request_id = '${correctedRequest.id}'::uuid;
  `);
  const packageV2 = await json(await request.post(
    `/api/v1/finance/months/${monthId}/packages`,
    {
      headers: {
        ...mutationHeaders(tokens.vendor, "e2e-07-package-v2"),
        "If-Match": "2",
      },
      data: {
        expectedMonthVersion: 2,
        readinessRunId: correctedReadinessRunId,
        reason: "Regenerate exact package after confirmed correction",
      },
    },
  ), 200);
  expect(packageV2).toMatchObject({
    version: 2,
    state: "AVAILABLE",
    current: true,
    supersedesPackageId: packageV1.packageId,
  });
  expect(queryDatabase(`
    SELECT COUNT(*) FROM evidence_package_items
    WHERE package_version_id = '${packageV2.packageId}'::uuid
      AND item_type = 'ATTENDANCE'
      AND source_object_id = '${correctedSnapshot.id}'::uuid;
  `)).toBe("1");

  invoiceVersion = Number(queryDatabase(`
    SELECT optimistic_version FROM invoices WHERE id = '${invoiceId}'::uuid;
  `));
  const readinessV2 = await json(await request.post(
    `/api/v1/finance/invoices/${invoiceId}/readiness-runs`,
    {
      headers: {
        ...mutationHeaders(tokens.vendor, "e2e-07-readiness-v2"),
        "If-Match": String(invoiceVersion),
      },
      data: { expectedVersion: invoiceVersion },
    },
  ), 200);
  expect(readinessV2.readiness.eligibleForSubmission).toBe(true);
  expect(readinessV2.readiness.runId).not.toBe(readinessV1.readiness.runId);
  expect(Number(readinessV2.version)).toBeGreaterThan(
    Number(readinessV1.version),
  );
  expect(queryDatabase(`
    SELECT checksum FROM attendance_snapshot_versions
    WHERE id = '${originalSnapshotId}'::uuid;
  `)).toBe(originalSnapshotChecksum);
  expect(queryDatabase(`
    SELECT canonical_input_hash FROM evidence_package_versions
    WHERE id = '${packageV1.packageId}'::uuid;
  `)).toBe(originalPackageHash);
  expect(queryDatabase(`
    SELECT COUNT(*) FROM evidence_package_versions
    WHERE engagement_month_id = '${monthId}'::uuid;
  `)).toBe("2");
});

test("[E2E-10] provider outage, stale truth, canary abort and recovery preserve evidence", async ({
  request,
}) => {
  const originalFactCount = queryDatabase(
    "SELECT COUNT(*) FROM greythr_imported_facts;",
  );
  const originalFactApplicationCount = queryDatabase(
    "SELECT COUNT(*) FROM greythr_fact_applications;",
  );
  const originalGreytHrAttendanceEventCount = queryDatabase(
    "SELECT COUNT(*) FROM attendance_events WHERE source = 'GREYTHR';",
  );
  const originalGreytHrLeaveEffectCount = queryDatabase(`
    SELECT COUNT(*) FROM leave_balance_ledger
    WHERE reference_type = 'GREYTHR_FACT';
  `);
  const originalSnapshotCount = queryDatabase(
    "SELECT COUNT(*) FROM attendance_snapshot_versions;",
  );
  const originalConfirmationActionCount = queryDatabase(
    "SELECT COUNT(*) FROM business_confirmation_actions;",
  );
  const originalPackageCount = queryDatabase(
    "SELECT COUNT(*) FROM evidence_package_versions;",
  );
  const originalInvoiceCount = queryDatabase(
    "SELECT COUNT(*) FROM invoices;",
  );

  queryDatabase(`
    UPDATE greythr_recorded_pages
    SET response_mode = 'UNAVAILABLE'
    WHERE connection_id = '${greytHrConnectionId}';
  `);
  const linearOutage = await json(await request.post(
    `/api/v1/integrations/linear/connections/${linearConnectionId}/reconciliations`,
    {
      headers: mutationHeaders(tokens.vendor, "e2e-10-linear-outage"),
      data: {
        outcome: "UNAVAILABLE",
        errorCode: "PROVIDER_UNAVAILABLE",
        reason: "Recorded provider reconciliation exhausted its bounded retry",
      },
    },
  ), 201);
  expect(linearOutage).toMatchObject({
    jobStatus: "FAILED",
    connectionStatus: "ACTION_REQUIRED",
    errorCode: "PROVIDER_UNAVAILABLE",
    replay: false,
  });
  expect(linearOutage.commandChecksum).toMatch(/^[0-9a-f]{64}$/);
  expect(linearOutage.correlationId).toBeTruthy();
  expect(linearOutage.causationId).toBeTruthy();
  expect(linearOutage.staleIssueCount).toBeGreaterThan(0);
  const linearOutageReplay = await json(await request.post(
    `/api/v1/integrations/linear/connections/${linearConnectionId}/reconciliations`,
    {
      headers: mutationHeaders(tokens.vendor, "e2e-10-linear-outage"),
      data: {
        outcome: "UNAVAILABLE",
        errorCode: "PROVIDER_UNAVAILABLE",
        reason: "Recorded provider reconciliation exhausted its bounded retry",
      },
    },
  ), 201);
  expect(linearOutageReplay).toMatchObject({
    jobId: linearOutage.jobId,
    commandChecksum: linearOutage.commandChecksum,
    correlationId: linearOutage.correlationId,
    causationId: linearOutage.causationId,
    replay: true,
  });
  await expectStatus(request.post(
    `/api/v1/integrations/linear/connections/${linearConnectionId}/reconciliations`,
    {
      headers: mutationHeaders(tokens.vendor, "e2e-10-linear-outage"),
      data: {
        outcome: "UNAVAILABLE",
        errorCode: "PROVIDER_TIMEOUT",
        reason: "Conflicting command must not reuse the same key",
      },
    },
  ), 409);
  const outage = await json(await request.post(
    `/api/v1/integrations/greythr/connections/${greytHrConnectionId}/sync-runs`,
    {
      headers: mutationHeaders(tokens.vendor, "e2e-10-greythr-outage"),
      data: { dateFrom: "2026-07-01", dateTo: "2026-07-31" },
    },
  ), 201);
  expect(outage).toMatchObject({
    status: "DEGRADED",
    errorCode: "PROVIDER_UNAVAILABLE",
    stale: true,
  });
  expect(outage.lastSuccessfulSyncAt).toBeTruthy();
  expect(queryDatabase("SELECT COUNT(*) FROM greythr_imported_facts;"))
    .toBe(originalFactCount);
  expect(queryDatabase("SELECT COUNT(*) FROM greythr_fact_applications;"))
    .toBe(originalFactApplicationCount);
  const outageRetry = await json(await request.post(
    `/api/v1/integrations/greythr/connections/${greytHrConnectionId}/sync-runs`,
    {
      headers: mutationHeaders(tokens.vendor, "e2e-10-greythr-outage-retry"),
      data: { dateFrom: "2026-07-01", dateTo: "2026-07-31" },
    },
  ), 201);
  expect(outageRetry).toMatchObject({
    status: "DEGRADED",
    errorCode: "PROVIDER_UNAVAILABLE",
    stale: true,
    lastSuccessfulSyncAt: outage.lastSuccessfulSyncAt,
  });
  expect(queryDatabase(
    "SELECT COUNT(*) FROM attendance_events WHERE source = 'GREYTHR';",
  )).toBe(originalGreytHrAttendanceEventCount);
  expect(queryDatabase(`
    SELECT COUNT(*) FROM leave_balance_ledger
    WHERE reference_type = 'GREYTHR_FACT';
  `)).toBe(originalGreytHrLeaveEffectCount);
  const linearOutageRetry = await json(await request.post(
    `/api/v1/integrations/linear/connections/${linearConnectionId}/reconciliations`,
    {
      headers: mutationHeaders(tokens.vendor, "e2e-10-linear-outage-retry"),
      data: {
        outcome: "UNAVAILABLE",
        errorCode: "PROVIDER_UNAVAILABLE",
        reason: "Bounded retry remained unavailable",
      },
    },
  ), 201);
  expect(linearOutageRetry).toMatchObject({
    jobStatus: "FAILED",
    connectionStatus: "ACTION_REQUIRED",
  });

  const greytHrHealth = await json(await request.get(
    `/api/v1/integrations/greythr/connections/${greytHrConnectionId}/health`,
    { headers: authorization(tokens.vendor) },
  ), 200);
  expect(greytHrHealth).toMatchObject({
    status: "DEGRADED",
    stale: true,
    lastErrorCode: "PROVIDER_UNAVAILABLE",
  });
  expect(greytHrHealth.lastSuccessAt).toBeTruthy();

  const linearHealth = await json(await request.get(
    `/api/v1/integrations/linear/health?engagementId=${engagementId}`,
    { headers: authorization(tokens.vendor) },
  ), 200);
  expect(linearHealth.status).toBe("ACTION_REQUIRED");
  expect(linearHealth.lastError).toBe("PROVIDER_UNAVAILABLE");
  expect(linearHealth.staleIssueCount).toBeGreaterThan(0);
  expect(linearHealth.lastReconciledAt).toBeTruthy();
  const operations = runJsonCommand([
    "scripts/f07/ops-verify.mjs",
  ]);
  expect(operations).toMatchObject({
    result: "PASS",
    alertCount: 6,
    runbookCount: 15,
  });
  for (const runbook of ["RB-05", "RB-06"]) {
    expect(runJsonCommand([
      "scripts/f07/ops-verify.mjs",
      "--tabletop", runbook,
      "--event", "scripts/f07/fixtures/provider-outage-tabletop.json",
    ])).toMatchObject({
      result: "PASS",
      runbookId: runbook,
      correlationId: "f07-e2e-10-provider-outage",
      missing: [],
    });
  }

  await json(await request.post("/api/v1/governance/feature-flags", {
    headers: mutationHeaders(tokens.governance, "e2e-10-canary-define"),
    data: {
      key: "system.canary",
      owner: "release-operations",
      defaultEnabled: false,
      description: "Local real-system canary used by E2E-10",
      reason: "Exercise bounded canary rollback",
    },
  }), 200);
  await json(await request.post(
    "/api/v1/governance/feature-flags/system.canary/versions",
    {
      headers: mutationHeaders(tokens.governance, "e2e-10-canary-enable"),
      data: {
        scopeType: "ENGAGEMENT",
        organizationId: clientOrganizationId,
        engagementId,
        enabled: true,
        effectiveFrom: "2020-01-01T00:00:00Z",
        dependencies: [],
        reason: "Begin local canary below thresholds",
      },
    },
  ), 200);
  const enabled = await json(await request.get(
    "/api/v1/governance/feature-flags/system.canary/evaluation"
      + `?organizationId=${clientOrganizationId}&engagementId=${engagementId}`,
    { headers: authorization(tokens.governance) },
  ), 200);
  expect(enabled).toMatchObject({
    enabled: true,
    authorizationGranted: false,
  });
  const canaryAbort = runJsonCommand([
    "scripts/f07/rollout-verify.mjs",
    "--metrics", "scripts/f07/fixtures/canary-abort.json",
  ], 1);
  expect(canaryAbort).toMatchObject({
    policy: { result: "PASS" },
    canary: {
      decision: "ABORT_AND_ROLLBACK",
      reasons: expect.arrayContaining(["error-rate threshold exceeded"]),
    },
  });

  await json(await request.post(
    "/api/v1/governance/feature-flags/system.canary/versions",
    {
      headers: mutationHeaders(tokens.governance, "e2e-10-canary-abort"),
      data: {
        scopeType: "ENGAGEMENT",
        organizationId: clientOrganizationId,
        engagementId,
        enabled: false,
        effectiveFrom: "2020-01-01T00:00:00Z",
        dependencies: [],
        reason: "Abort after synthetic error threshold breach",
      },
    },
  ), 200);
  const disabled = await json(await request.get(
    "/api/v1/governance/feature-flags/system.canary/evaluation"
      + `?organizationId=${clientOrganizationId}&engagementId=${engagementId}`,
    { headers: authorization(tokens.governance) },
  ), 200);
  expect(disabled.enabled).toBe(false);
  expect(queryDatabase(
    "SELECT COUNT(*) FROM attendance_snapshot_versions;",
  )).toBe(originalSnapshotCount);

  queryDatabase(`
    UPDATE greythr_recorded_pages
    SET response_mode = 'AVAILABLE'
    WHERE connection_id = '${greytHrConnectionId}';
  `);
  const linearRecovered = await json(await request.post(
    `/api/v1/integrations/linear/connections/${linearConnectionId}/reconciliations`,
    {
      headers: mutationHeaders(tokens.vendor, "e2e-10-linear-recovery"),
      data: {
        outcome: "AVAILABLE",
        errorCode: null,
        reason: "Recorded provider reconciliation completed successfully",
      },
    },
  ), 201);
  expect(linearRecovered).toMatchObject({
    jobStatus: "SUCCEEDED",
    connectionStatus: "CONNECTED",
    staleIssueCount: 0,
    errorCode: null,
    replay: false,
  });
  const lateLinearOutageReplay = await json(await request.post(
    `/api/v1/integrations/linear/connections/${linearConnectionId}/reconciliations`,
    {
      headers: mutationHeaders(tokens.vendor, "e2e-10-linear-outage"),
      data: {
        outcome: "UNAVAILABLE",
        errorCode: "PROVIDER_UNAVAILABLE",
        reason: "Recorded provider reconciliation exhausted its bounded retry",
      },
    },
  ), 201);
  expect(lateLinearOutageReplay).toEqual({
    ...linearOutage,
    replay: true,
  });
  const recovered = await json(await request.post(
    `/api/v1/integrations/greythr/connections/${greytHrConnectionId}/sync-runs`,
    {
      headers: mutationHeaders(tokens.vendor, "e2e-10-greythr-recovery"),
      data: { dateFrom: "2026-07-01", dateTo: "2026-07-31" },
    },
  ), 201);
  expect(recovered.status).toBe("COMPLETED");
  expect(recovered.stale).toBe(false);
  expect(queryDatabase("SELECT COUNT(*) FROM greythr_imported_facts;"))
    .toBe(originalFactCount);
  expect(queryDatabase("SELECT COUNT(*) FROM greythr_fact_applications;"))
    .toBe(originalFactApplicationCount);
  expect(queryDatabase(
    "SELECT COUNT(*) FROM attendance_events WHERE source = 'GREYTHR';",
  )).toBe(originalGreytHrAttendanceEventCount);
  expect(queryDatabase(`
    SELECT COUNT(*) FROM leave_balance_ledger
    WHERE reference_type = 'GREYTHR_FACT';
  `)).toBe(originalGreytHrLeaveEffectCount);
  const recoveredGreytHrHealth = await json(await request.get(
    `/api/v1/integrations/greythr/connections/${greytHrConnectionId}/health`,
    { headers: authorization(tokens.vendor) },
  ), 200);
  expect(recoveredGreytHrHealth).toMatchObject({
    status: "ACTIVE",
    stale: false,
    lastErrorCode: null,
  });
  const recoveredLinearHealth = await json(await request.get(
    `/api/v1/integrations/linear/health?engagementId=${engagementId}`,
    { headers: authorization(tokens.vendor) },
  ), 200);
  expect(recoveredLinearHealth).toMatchObject({
    status: "CONNECTED",
    staleIssueCount: 0,
    lastError: null,
  });
  expect(queryDatabase(`
    SELECT COUNT(*) FROM linear_sync_jobs
    WHERE connection_id = '${linearConnectionId}'::uuid
      AND job_type = 'NIGHTLY_RECONCILIATION'
      AND status IN ('FAILED', 'SUCCEEDED')
      AND completed_at IS NOT NULL;
  `)).toBe("3");
  expect(queryDatabase(`
    SELECT COUNT(*) FROM linear_reconciliation_commands
    WHERE connection_id = '${linearConnectionId}'::uuid
      AND actor_subject = 'user-arrow'
      AND command_checksum ~ '^[0-9a-f]{64}$'
      AND correlation_id IS NOT NULL
      AND causation_id IS NOT NULL;
  `)).toBe("3");
  expect(queryDatabase("SELECT COUNT(*) FROM business_confirmation_actions;"))
    .toBe(originalConfirmationActionCount);
  expect(queryDatabase("SELECT COUNT(*) FROM evidence_package_versions;"))
    .toBe(originalPackageCount);
  expect(queryDatabase("SELECT COUNT(*) FROM invoices;"))
    .toBe(originalInvoiceCount);
  expect(queryDatabase(`
    SELECT COUNT(*) FROM f07_feature_flag_transitions transition
    JOIN f07_feature_flags flag ON flag.id = transition.flag_id
    WHERE flag.flag_key = 'system.canary';
  `)).toBe("3");
});

function requiredEnvironment(name: string) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required for F07 system E2E.`);
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

function mutationHeaders(token: string, idempotencyKey: string) {
  return {
    ...authorization(token),
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

function runJsonCommand(args: string[], expectedStatus = 0) {
  const result = spawnSync("node", args, {
    cwd: process.cwd(),
    encoding: "utf8",
    timeout: 30_000,
  });
  expect(
    result.status,
    `${result.stderr}\n${result.stdout}`,
  ).toBe(expectedStatus);
  return JSON.parse(result.stdout);
}

function sha256(value: string) {
  return createHash("sha256").update(value).digest("hex");
}

function linearSignature(body: string) {
  return createHmac("sha256", "test-webhook-secret")
    .update(body)
    .digest("hex");
}
