import { expect, test, type APIResponse } from "@playwright/test";
import { execFileSync } from "node:child_process";

import "./fixtures/quality-gates";

const monthId = "00000000-0000-0000-0000-000000000602";
const organizationId = "00000000-0000-0000-0000-000000000101";
const employeeId = "00000000-0000-0000-0000-000000000801";
const greytHrConnectionId = "72000000-0000-0000-0000-000000000010";
const postgresContainer = requiredEnvironment("VMS_E2E_POSTGRES_CONTAINER");

const tokens = {
  vendor: requiredEnvironment("VMS_E2E_TOKEN_USER_ARROW"),
  employee: requiredEnvironment("VMS_E2E_TOKEN_USER_EMPLOYEE"),
};

test("[E2E-02] greytHR capability, sync, reconciliation and authority cutover are replay-safe", async ({
  request,
}) => {
  const originalSnapshotId = queryDatabase(`
    SELECT id FROM attendance_snapshot_versions
    WHERE engagement_month_id = '${monthId}'::uuid AND status = 'CLOSED'
    ORDER BY version DESC LIMIT 1;
  `);
  expect(originalSnapshotId).not.toBe("");

  const discovered = await json(await request.get(
    `/api/v1/integrations/greythr/connections/${greytHrConnectionId}/capabilities`,
    { headers: authorization(tokens.vendor) },
  ), 200);
  expect(discovered.discoveredCapabilities.sort()).toEqual(
    ["ATTENDANCE", "EMPLOYEES", "LEAVE"],
  );
  expect(JSON.stringify(discovered)).not.toMatch(
    /secret:\/\/|credential|salary|payroll|compensation/i,
  );

  const certified = await json(await request.post(
    `/api/v1/integrations/greythr/connections/${greytHrConnectionId}/certifications`,
    {
      headers: authorization(tokens.vendor),
      data: {
        organizationId,
        capabilities: ["EMPLOYEES", "ATTENDANCE", "LEAVE"],
      },
    },
  ), 201);
  expect(certified).toMatchObject({
    status: "ACTIVE",
    certificationId: expect.any(String),
    probeEvidenceId: expect.any(String),
    adapterMode: "RECORDED_FIXTURE",
  });
  expect(certified.probeEvidenceHash).toMatch(/^[0-9a-f]{64}$/);
  expect(certified.probedAt).toBeTruthy();
  expect(queryDatabase(`
    SELECT COUNT(*)
    FROM greythr_capability_probe_evidence probe
    JOIN greythr_certification_evidence certification
      ON certification.provider_probe_evidence_id = probe.id
    WHERE probe.id = '${certified.probeEvidenceId}'::uuid
      AND probe.connection_id = '${greytHrConnectionId}'::uuid
      AND probe.status = 'PASSED'
      AND probe.evidence_hash = '${certified.probeEvidenceHash}'
      AND probe.adapter_mode = 'RECORDED_FIXTURE'
      AND probe.authority_classification = 'SIMULATED_NON_PRODUCTION';
  `)).toBe("1");

  const syncBody = { dateFrom: "2026-07-01", dateTo: "2026-07-31" };
  const first = await json(await request.post(
    `/api/v1/integrations/greythr/connections/${greytHrConnectionId}/sync-runs`,
    {
      headers: mutationHeaders(tokens.vendor, "e2e-02-july-sync"),
      data: syncBody,
    },
  ), 201);
  expect(first).toMatchObject({
    status: "COMPLETED",
    employeeCount: 1,
    attendanceCount: 1,
    leaveCount: 1,
    conflictCount: 1,
    pageCount: 2,
    stale: false,
  });
  expect(first.lastSuccessfulSyncAt).toBeTruthy();

  const replay = await json(await request.post(
    `/api/v1/integrations/greythr/connections/${greytHrConnectionId}/sync-runs`,
    {
      headers: mutationHeaders(tokens.vendor, "e2e-02-july-sync"),
      data: syncBody,
    },
  ), 201);
  expect(replay).toEqual(first);
  expect(queryDatabase("SELECT COUNT(*) FROM greythr_sync_runs;")).toBe("1");
  expect(queryDatabase("SELECT COUNT(*) FROM greythr_imported_facts;")).toBe("3");
  expect(queryDatabase(`
    SELECT COUNT(*) FROM greythr_fact_applications
    WHERE connection_id = '${greytHrConnectionId}'::uuid;
  `)).toBe("0");
  expect(queryDatabase(`
    SELECT COUNT(*) FROM attendance_events
    WHERE employee_id = '${employeeId}'::uuid
      AND source = 'GREYTHR';
  `)).toBe("0");
  expect(queryDatabase(`
    SELECT COUNT(*) FROM leave_balance_ledger
    WHERE employee_id = '${employeeId}'::uuid
      AND reference_type = 'GREYTHR_FACT';
  `)).toBe("0");

  const reconciliations = await json(await request.get(
    `/api/v1/integrations/greythr/connections/${greytHrConnectionId}/reconciliations`,
    { headers: authorization(tokens.vendor) },
  ), 200);
  expect(reconciliations).toHaveLength(1);
  expect(reconciliations[0]).toMatchObject({
    employeeId,
    workDate: "2026-07-07",
    conflictType: "ATTENDANCE_SOURCE_CONFLICT",
    status: "PENDING",
  });
  const decision = await json(await request.post(
    `/api/v1/integrations/greythr/reconciliations/${reconciliations[0].id}/decisions`,
    {
      headers: authorization(tokens.vendor),
      data: {
        decision: "USE_GREYTHR",
        reason: "Certified provider wins the explicit parallel-run conflict",
      },
    },
  ), 200);
  expect(decision).toMatchObject({
    status: "USE_GREYTHR",
    decisionReason: "Certified provider wins the explicit parallel-run conflict",
  });
  expect(queryDatabase(`
    SELECT COUNT(*) FROM greythr_fact_applications
    WHERE connection_id = '${greytHrConnectionId}'::uuid;
  `)).toBe("0");
  expect(queryDatabase(`
    SELECT net_minutes FROM attendance_sessions
    WHERE employee_id = '${employeeId}'::uuid
      AND work_date = '2026-07-07'
      AND status = 'CLOSED';
  `)).toBe("510");

  const cutover = await json(await request.post(
    `/api/v1/integrations/greythr/connections/${greytHrConnectionId}/cutovers`,
    {
      headers: authorization(tokens.vendor),
      data: {
        employeeId,
        effectiveFrom: "2026-07-01",
        reason: "Reconciled source authority cutover",
      },
    },
  ), 201);
  expect(cutover).toMatchObject({
    employeeId,
    mode: "GREYTHR_AUTHORITATIVE",
    authoritativeSource: "GREYTHR",
    effectiveFrom: "2026-07-01",
    certificationId: certified.certificationId,
  });
  expect(queryDatabase(`
    SELECT COUNT(*) FROM attendance_source_mode_assignments
    WHERE employee_id = '${employeeId}'::uuid
      AND authoritative_source = 'GREYTHR'
      AND valid_from = '2026-07-01'
      AND valid_to IS NULL;
  `)).toBe("1");
  await expectStatus(request.post("/api/v1/attendance/punches", {
    headers: authorization(tokens.employee),
    data: {
      employeeId,
      eventType: "CHECK_IN",
      idempotencyKey: "e2e-02-internal-blocked",
    },
  }), 409);

  const reopened = await json(await request.post(
    `/api/v1/attendance/month-snapshots/${originalSnapshotId}/reopen`,
    {
      headers: authorization(tokens.vendor),
      data: { reason: "Regenerate after approved greytHR authority cutover" },
    },
  ), 201);
  expect(reopened).toMatchObject({
    version: 2,
    status: "REOPENED",
    supersedesId: originalSnapshotId,
  });
  const closed = await json(await request.post(
    "/api/v1/attendance/month-snapshots",
    {
      headers: authorization(tokens.vendor),
      data: { engagementMonthId: monthId },
    },
  ), 201);
  expect(closed).toMatchObject({
    version: 3,
    status: "CLOSED",
    supersedesId: reopened.id,
  });
  expect(closed.checksum).toMatch(/^[0-9a-f]{64}$/);
  expect(queryDatabase(`
    SELECT COUNT(*) FROM attendance_snapshot_days
    WHERE snapshot_id = '${closed.id}'::uuid
      AND employee_id = '${employeeId}'::uuid
      AND work_date = '2026-07-07'
      AND source_mode = 'GREYTHR_AUTHORITATIVE'
      AND net_minutes = 540
      AND final_status = 'PRESENT_FULL_DAY';
  `)).toBe("1");
  expect(queryDatabase(`
    SELECT COUNT(*) FROM leave_balance_ledger
    WHERE employee_id = '${employeeId}'::uuid
      AND reference_type = 'GREYTHR_FACT'
      AND effective_date = '2026-07-08'
      AND quantity = -0.5;
  `)).toBe("1");
  expect(queryDatabase(`
    SELECT COUNT(*) FROM greythr_fact_applications application
    JOIN greythr_imported_facts fact ON fact.id = application.provider_fact_id
    WHERE fact.connection_id = '${greytHrConnectionId}'::uuid
      AND application.action = 'APPLY';
  `)).toBe("2");
  const distinctKeyReplay = await json(await request.post(
    `/api/v1/integrations/greythr/connections/${greytHrConnectionId}/sync-runs`,
    {
      headers: mutationHeaders(tokens.vendor, "e2e-02-july-sync-new-key"),
      data: syncBody,
    },
  ), 201);
  expect(distinctKeyReplay).toMatchObject({
    status: "COMPLETED",
    employeeCount: 1,
    attendanceCount: 1,
    leaveCount: 1,
    conflictCount: 0,
  });
  expect(distinctKeyReplay.id).not.toBe(first.id);
  expect(queryDatabase("SELECT COUNT(*) FROM greythr_sync_runs;")).toBe("2");
  expect(queryDatabase("SELECT COUNT(*) FROM greythr_imported_facts;")).toBe("3");
  expect(queryDatabase(`
    SELECT COUNT(*) FROM greythr_fact_applications application
    JOIN greythr_imported_facts fact
      ON fact.id = application.provider_fact_id
    WHERE fact.connection_id = '${greytHrConnectionId}'::uuid
      AND application.action = 'APPLY';
  `)).toBe("2");
  expect(queryDatabase(`
    SELECT COUNT(*) FROM attendance_sessions
    WHERE employee_id = '${employeeId}'::uuid
      AND work_date = '2026-07-07'
      AND status = 'CLOSED'
      AND net_minutes = 540;
  `)).toBe("1");
  expect(queryDatabase(`
    SELECT COUNT(*) FROM greythr_imported_facts
    WHERE payload ?| ARRAY[
      'salary', 'payroll', 'compensation', 'bankAccount', 'taxIdentifier'
    ];
  `)).toBe("0");

  const health = await json(await request.get(
    `/api/v1/integrations/greythr/connections/${greytHrConnectionId}/health`,
    { headers: authorization(tokens.vendor) },
  ), 200);
  expect(health).toMatchObject({
    status: "ACTIVE",
    stale: false,
    pendingReconciliations: 0,
  });
  expect(health.lastSuccessAt).toBeTruthy();
});

function requiredEnvironment(name: string) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required for F07 system E2E.`);
  return value;
}

function authorization(token: string) {
  return { Authorization: `Bearer ${token}` };
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
