#!/usr/bin/env node

import { once } from "node:events";
import { createServer } from "node:http";
import { spawn } from "node:child_process";
import { createHmac } from "node:crypto";
import {
  lstat,
  mkdir,
  mkdtemp,
  readFile,
  rm,
  symlink,
  writeFile,
} from "node:fs/promises";
import { relative, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import {
  evaluateRelease,
  requiredCiLaneIds,
  requiredCiLanes,
  validateMigrationLiveResult,
  validateSupplyStructuredResult,
} from "./release-gate.mjs";
import { runProfile } from "./load-harness.mjs";
import { createProvenance } from "./provenance.mjs";
import { decideCanary, validatePolicy, verifyRollback } from "./rollout-verify.mjs";
import {
  requiredMigrationExecutions,
  destructiveSqlOperations,
  staticPreflight,
  validatesRoleBootstrapContract,
  validateRehearsalEvidence,
} from "./migration-preflight.mjs";
import { verifyOperations } from "./ops-verify.mjs";
import {
  requiredRestoreAssertions,
  createPrivateRestoreTemporary,
  authenticateManifest,
  assertRestoreNotReplayed,
  releaseRestoreClaim,
  validateBackupFreshness,
  validateTarMembers,
  validateDistinctKeyMaterial,
  validateLocalAssertions,
  verifyAuthenticatedManifest,
} from "./backup-drill.mjs";
import {
  applyMediumRiskDispositions,
  createLicenseInventory,
  evaluateLicenseExpression,
  parseNpmAuditExecution,
  supplyChainPlan,
  validateImageReference,
} from "./supply-chain.mjs";
import {
  canonicalProvenanceInputs,
  protectedMigrationBaseCommit,
  recordEvidencePolicy,
} from "./evidence-policy.mjs";
import {
  deriveVerifiedRecords,
  validateObservedCases,
} from "./machine-reports.mjs";
import { validateOperationalDocument } from "./operational-report.mjs";
import {
  commandExecutionSucceeded,
  commandOutputProvesSuccess,
} from "./command-evidence.mjs";
import {
  aggregateEvidence,
  buildAggregate,
} from "./post-deploy-regression.mjs";
import {
  hasRunbookAnchor,
  validateReviewEvidence,
  validateTraceability,
} from "./traceability.mjs";
import {
  gitMetadata,
  readJson,
  repoPath,
  repoRoot,
  run,
  safeError,
  sha256Bytes,
  sha256File,
  stableJson,
  writeJson,
} from "./lib.mjs";

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

async function expectReject(action, message) {
  let rejected = false;
  try {
    await action();
  } catch {
    rejected = true;
  }
  assert(rejected, message);
}

async function releaseGateTests(temporary) {
  const manifestPath = repoPath(
    "docs/features/07-hardening-go-live/release-evidence.json",
  );
  const manifest = await readJson(manifestPath);
  const inventory = await readJson(repoPath(manifest.requiredInventory));
  const localRecordIds = [
    ...manifest.taskRecords.local,
    ...manifest.testRecords.local,
  ].sort();
  assert(
    stableJson(Object.keys(recordEvidencePolicy).sort()) ===
      stableJson(localRecordIds),
    "canonical record evidence policy must cover every and only local record ID",
  );
  for (const [recordId, policy] of Object.entries(recordEvidencePolicy)) {
    assert(
      policy.requiredCases.length > 0,
      `${recordId} must be bound to exact machine-observed cases`,
    );
  }
  const traceability = await validateTraceability({
    ...inventory,
    taskIds: Array.from(
      {length: inventory.taskIdRange.to - inventory.taskIdRange.from + 1},
      (_, index) =>
        `${inventory.taskIdRange.prefix}${String(inventory.taskIdRange.from + index).padStart(3, "0")}`,
    ),
  });
  assert(
    traceability.result === "PASS" && traceability.records.size === 161,
    `every F07 task/test must have complete canonical impacts: ${traceability.findings.join("; ")}`,
  );
  const reviewEvidence = await validateReviewEvidence();
  assert(
    reviewEvidence.result === "PASS",
    `independent review evidence is incomplete: ${reviewEvidence.findings.join("; ")}`,
  );
  const malformedReviewSchema = await validateReviewEvidence({
    evidence: {...reviewEvidence.evidence, schemaVersion: 1},
  });
  assert(
    malformedReviewSchema.result === "FAIL" &&
      malformedReviewSchema.findings.some((finding) =>
        finding.includes("schemaVersion 2"),
      ),
    "review evidence must reject stale schema/type contracts",
  );
  const treeObject = run("git", ["rev-parse", "HEAD^{tree}"]).stdout.trim();
  const nonCommitReview = await validateReviewEvidence({
    evidence: {
      ...reviewEvidence.evidence,
      reviewedThroughCommit: treeObject,
      closureDispositions: reviewEvidence.evidence.closureDispositions.map(
        (entry) => ({...entry, reviewedThroughCommit: treeObject}),
      ),
    },
  });
  assert(
    nonCommitReview.result === "FAIL" &&
      nonCommitReview.findings.includes(
        "reviewedThroughCommit must resolve to a Git commit object",
      ),
    "review evidence must validate the Git object type, not only SHA syntax",
  );
  const nonAncestorReview = await validateReviewEvidence({
    evidence: reviewEvidence.evidence,
    headRef: protectedMigrationBaseCommit,
  });
  assert(
    nonAncestorReview.result === "FAIL" &&
      nonAncestorReview.findings.includes(
        "reviewedThroughCommit must be an ancestor of the validated release",
      ),
    "review evidence must reject a real commit outside release ancestry",
  );
  const missingDisposition = await validateReviewEvidence({
    evidence: {
      ...reviewEvidence.evidence,
      closureDispositions:
        reviewEvidence.evidence.closureDispositions.slice(1),
    },
  });
  assert(
    missingDisposition.result === "FAIL" &&
      missingDisposition.findings.some((finding) =>
        finding.startsWith("closure disposition is missing:"),
      ),
    "review evidence must require structured closure for every dimension",
  );
  const runbookText = await readFile(
    repoPath("docs/operations/F07-RUNBOOKS.md"),
    "utf8",
  );
  assert(
    hasRunbookAnchor(
      runbookText,
      "docs/operations/F07-RUNBOOKS.md#rb-14-security-incident-and-evidence-preservation",
    ) &&
      !hasRunbookAnchor(
        runbookText,
        "docs/operations/F07-RUNBOOKS.md#missing-review-anchor",
      ),
    "traceability must validate exact runbook anchors, not only files",
  );
  const aggregateDirectory = resolve(temporary, "post-deploy-aggregate");
  await mkdir(aggregateDirectory, { recursive: true });
  const missingAggregate = await aggregateEvidence(aggregateDirectory);
  assert(
    missingAggregate.result === "FAIL" &&
      missingAggregate.cases.filter((entry) => entry.status === "FAILED")
        .length === 13,
    "post-deploy aggregate must fail when underlying evidence is absent",
  );
  const completeAggregate = buildAggregate(
    new Set([
      ...Array.from(
        { length: 10 },
        (_, index) => `E2E-${String(index + 1).padStart(2, "0")}`,
      ),
      "F07-AUD-001",
      "F07-OPS-004",
    ]),
    ["F07-CI-MAVEN-VERIFY", "F07-CI-ROLLOUT"],
    gitMetadata().commit,
  );
  assert(
    completeAggregate.result === "PASS" &&
      completeAggregate.cases.length === 13 &&
      completeAggregate.cases.every((entry) => entry.status === "PASSED"),
    "post-deploy aggregate must require every E2E/audit/rollback input",
  );
  const financeLane = requiredCiLanes["F07-CI-FINANCE-SYSTEM"];
  const f07SystemLane = requiredCiLanes["F07-CI-F07-SYSTEM"];
  assert(
    f07SystemLane.command === "npm run --silent e2e:f07:system" &&
      stableJson(f07SystemLane.recordRequirements) === stableJson({
        "E2E-01": ["E2E-01@f07-workforce-system-chromium"],
        "E2E-02": ["E2E-02@f07-greythr-system-chromium"],
        "E2E-03": [
          "E2E-03@f07-delivery-confirmation-system-chromium",
        ],
        "E2E-04": [
          "E2E-04@f07-delivery-confirmation-system-chromium",
        ],
        "E2E-05": [
          "E2E-05@f07-delivery-confirmation-system-chromium",
        ],
        "E2E-07": ["E2E-07@f07-real-system-chromium"],
        "E2E-10": ["E2E-10@f07-real-system-chromium"],
      }),
    "F07 real-system evidence must bind all seven exact project cases",
  );
  await writeJson(resolve(aggregateDirectory, "forged.json"), {
    commandOutput: {
      errorCode: null,
      exitCode: 0,
      signal: null,
      stderrSha256: "0".repeat(64),
      stdoutSha256: "0".repeat(64),
      timedOut: false,
    },
    machineReportRaw: JSON.stringify({ suites: [] }),
    observedCases: [],
    record: { id: "F07-CI-FINANCE-SYSTEM", result: "PASS" },
    releaseCommit: gitMetadata().commit,
    schemaVersion: 1,
    suiteId: financeLane.suiteId,
    verifiedResultIds: ["E2E-06", "E2E-09"],
  });
  await expectReject(
    () => aggregateEvidence(aggregateDirectory),
    "post-deploy aggregate must reject self-declared IDs without raw cases",
  );
  assert(
    requiredCiLanes["F07-CI-SOAK-24H"].actionRequiredWhenAbsent === true &&
      recordEvidencePolicy["F07-PERF-006"].requiredCases.includes("SOAK-24H"),
    "24-hour soak must require its real signed duration report",
  );
  const operationalKey = "self-test-operational-key-material-32-bytes";
  const soakUnsigned = {
    assertions: {
      boundedQueues: true,
      recoverableDeadLetters: true,
      stableConnections: true,
      stableMemory: true,
      zeroDuplicateEffects: true,
      zeroLostAcknowledgedEffects: true,
    },
    completedDurationMs: 86_400_000,
    durationMs: 86_400_000,
    environment: "local-isolated",
    finishedAt: "2026-07-29T00:00:00.000Z",
    kind: "f07-soak-24h-input-v1",
    releaseCommit: gitMetadata().commit,
    result: "PASS",
    samples: 24,
    schemaVersion: 1,
    startedAt: "2026-07-28T00:00:00.000Z",
    targetDurationMs: 86_400_000,
  };
  const signedSoak = (unsigned) => ({
    ...unsigned,
    signature: {
      algorithm: "HMAC-SHA256",
      keyId: "self-test-operational-v1",
      value: createHmac("sha256", operationalKey)
        .update(stableJson(unsigned))
        .digest("hex"),
    },
  });
  process.env.F07_OPERATIONAL_REPORT_KEY = operationalKey;
  assert(
    validateOperationalDocument("soak-24h", signedSoak(soakUnsigned)).cases
      .some((entry) => entry.id === "SOAK-24H"),
    "a signed measured 24-hour report must be recognized",
  );
  const shortSoak = {
    ...soakUnsigned,
    completedDurationMs: 86_399_999,
    durationMs: 86_399_999,
    finishedAt: "2026-07-28T23:59:59.999Z",
  };
  await expectReject(
    async () => validateOperationalDocument("soak-24h", signedSoak(shortSoak)),
    "a signed soak shorter than 24 hours must remain ACTION_REQUIRED",
  );
  for (const [label, mutate] of [
    ["missing completed duration", (soak) => delete soak.completedDurationMs],
    ["string target duration", (soak) => {
      soak.targetDurationMs = "86400000";
    }],
    ["missing samples", (soak) => delete soak.samples],
    ["fractional samples", (soak) => {
      soak.samples = 24.5;
    }],
    ["future timestamps", (soak) => {
      soak.startedAt = "2099-01-01T00:00:00.000Z";
      soak.finishedAt = "2099-01-02T00:00:00.000Z";
    }],
  ]) {
    const invalidSoak = structuredClone(soakUnsigned);
    mutate(invalidSoak);
    await expectReject(
      async () =>
        validateOperationalDocument("soak-24h", signedSoak(invalidSoak)),
      `HMAC-valid soak with ${label} must remain ACTION_REQUIRED`,
    );
  }
  const canonicalProfileIdentities = {
    migrationExport: {
      id: "migration-export-mixed",
      path: "scripts/f07/profiles/migration-export-mixed.json",
      sha256: "931da4096c0e6bc46fc8e6054404971e498ef322159e790b1c2d5b5a902f0aa9",
    },
    packageConcurrency: {
      id: "package-determinism-concurrency",
      path: "scripts/f07/profiles/package-concurrency.json",
      sha256: "064d8000355921e9035971171f2fbf67b5cac981d0af8d07ec3e012a0e4904f3",
    },
    webhookBurst: {
      id: "linear-webhook-duplicate-storm",
      path: "scripts/f07/profiles/webhook-duplicate-storm.json",
      sha256: "56de980c5abfb9a5c0e7e40ae46d0cf0e216a3eaee5be8f1ed57e4195718ae62",
    },
  };
  const passingLoad = {
    durationMs: 60000,
    environment: "local-isolated",
    finishedAt: "2026-07-29T00:01:00.000Z",
    kind: "f07-load-input-v1",
    profiles: {
      migrationExport: {
        durationMs: 20000,
        fixtures: {
          attendanceRows: 300000,
          employees: 10000,
          engagements: 500,
        },
        metrics: {
          errorRate: 0,
          failures: 0,
          p95Ms: 1900,
          requestCounts: {
            attendanceDays: 333,
            financeReports: 333,
            migrationJobs: 334,
          },
        },
        profileIdentity: canonicalProfileIdentities.migrationExport,
        reconciliation: {
          countsMatch: true,
          noDuplicateEffects: true,
          tenantIsolation: true,
        },
        requests: 1000,
        result: "PASS",
      },
      packageConcurrency: {
        durationMs: 20000,
        fixtures: {
          engagementMonths: 1,
          readinessRuns: 1,
        },
        metrics: {
          errorRate: 0,
          failures: 0,
          p95Ms: 1900,
          requestCounts: {
            packageGeneration: 200,
          },
        },
        profileIdentity: canonicalProfileIdentities.packageConcurrency,
        reconciliation: {
          countsMatch: true,
          noDuplicateEffects: true,
          tenantIsolation: true,
        },
        requests: 200,
        result: "PASS",
      },
      webhookBurst: {
        durationMs: 20000,
        fixtures: {
          distinctWebhookDeliveries: 1,
          issueMetadataRows: 100000,
        },
        metrics: {
          acknowledgedRequests: 50000,
          acknowledgementMaxMs: 4999,
          acknowledgementP95Ms: 999,
          errorRate: 0,
          failures: 0,
          lostAcknowledgements: 0,
          p95Ms: 999,
          requestCounts: {
            linearHealth: 50000,
            linearWebhook: 50000,
          },
        },
        profileIdentity: canonicalProfileIdentities.webhookBurst,
        reconciliation: {
          countsMatch: true,
          noDuplicateEffects: true,
          tenantIsolation: true,
        },
        requests: 100000,
        result: "PASS",
      },
    },
    releaseCommit: gitMetadata().commit,
    result: "PASS",
    schemaVersion: 1,
    startedAt: "2026-07-29T00:00:00.000Z",
  };
  const signedOperational = (unsigned) => signedSoak(unsigned);
  assert(
    validateOperationalDocument("load", signedOperational(passingLoad)).cases.length === 3,
    "only all three policy-bound canonical load profiles may pass",
  );
  const invalidLoadReports = [
    ["one-request webhook forgery", (load) => {
      load.profiles.webhookBurst.requests = 1;
      load.profiles.webhookBurst.metrics.p95Ms = 999999999;
      load.profiles.webhookBurst.metrics.acknowledgedRequests = 1;
    }],
    ["unknown profile", (load) => {
      load.profiles.attackerProfile = structuredClone(load.profiles.webhookBurst);
    }],
    ["missing profile", (load) => {
      delete load.profiles.packageConcurrency;
    }],
    ["profile hash drift", (load) => {
      load.profiles.migrationExport.profileIdentity.sha256 = "0".repeat(64);
    }],
    ["fixture cardinality drift", (load) => {
      load.profiles.migrationExport.fixtures.employees = 9999;
    }],
    ["unknown fixture", (load) => {
      load.profiles.packageConcurrency.fixtures.untrusted = 1;
    }],
    ["request cardinality drift", (load) => {
      load.profiles.webhookBurst.metrics.requestCounts.linearWebhook = 1;
    }],
    ["negative duration", (load) => {
      load.profiles.packageConcurrency.durationMs = -1;
    }],
    ["missing error rate", (load) => {
      delete load.profiles.migrationExport.metrics.errorRate;
    }],
    ["forged error rate", (load) => {
      load.profiles.migrationExport.metrics.errorRate = 0.01;
    }],
    ["missing p95", (load) => {
      delete load.profiles.packageConcurrency.metrics.p95Ms;
    }],
    ["p95 over budget", (load) => {
      load.profiles.packageConcurrency.metrics.p95Ms = 2001;
    }],
    ["unknown metric", (load) => {
      load.profiles.packageConcurrency.metrics.selfDeclaredPass = true;
    }],
    ["lost webhook acknowledgement", (load) => {
      load.profiles.webhookBurst.metrics.lostAcknowledgements = 1;
    }],
    ["webhook acknowledgement p95 over budget", (load) => {
      load.profiles.webhookBurst.metrics.acknowledgementP95Ms = 1001;
    }],
    ["webhook acknowledgement max over budget", (load) => {
      load.profiles.webhookBurst.metrics.acknowledgementMaxMs = 5000;
    }],
  ];
  for (const [label, mutate] of invalidLoadReports) {
    const invalid = structuredClone(passingLoad);
    mutate(invalid);
    await expectReject(
      async () => validateOperationalDocument("load", signedOperational(invalid)),
      `HMAC-valid ${label} must not prove load evidence`,
    );
  }
  delete process.env.F07_OPERATIONAL_REPORT_KEY;
  const performanceLane = requiredCiLanes["F07-CI-PERFORMANCE"];
  const performanceCase =
    recordEvidencePolicy["F07-PERF-001"].requiredCases[0];
  assert(
    deriveVerifiedRecords(
      performanceLane,
      validateObservedCases([
        {
          durationMs: 1,
          id: performanceCase,
          source: "junit:synthetic.xml",
          status: "SKIPPED",
        },
      ]),
    ).length === 0,
    "skipped machine cases must never verify a release record",
  );
  const verifiedRegistry = {
    entries: [
      {
        affectedWorkflow: "self-test",
        external: false,
        id: "synthetic-verified",
        mandatory: true,
        owningRole: "independent-test-owner",
        state: "VERIFIED",
      },
    ],
  };
  const actual = await evaluateRelease(manifestPath);
  assert(actual.errors.length === 0, `checked-in manifest schema errors: ${actual.errors.join("; ")}`);
  assert(
    actual.verdict === "NO_GO_ACTION_REQUIRED" && actual.actionRequired.length > 0,
    "checked-in external blockers must keep the real release at NO_GO",
  );

  const git = gitMetadata();
  const provenance = await createProvenance(
    [
      "scripts/f07/release-gate.mjs",
      "backend/compose.yaml",
      "scripts/f07/bootstrap-database-roles.sql",
    ],
    { expectedCommit: git.commit },
  );
  provenance.worktreeDirty = false;
  const provenancePath = resolve(temporary, "provenance.json");
  await writeJson(provenancePath, provenance);
  const provenanceReference = {
    path: relative(repoRoot, provenancePath),
    sha256: await sha256File(provenancePath),
  };
  const approvalPath = resolve(temporary, "approval.json");
  await writeJson(approvalPath, {
    approvedAt: "2026-07-28T00:00:00Z",
    approver: "synthetic-independent-approver",
  });
  const approvalEvidence = {
    path: relative(repoRoot, approvalPath),
    sha256: await sha256File(approvalPath),
  };
  const passing = structuredClone(manifest);
  passing.recordTemplates.local = {
    ...passing.recordTemplates.local,
    result: "PASS",
  };
  passing.recordTemplates.external = {
    ...passing.recordTemplates.external,
    approvalEvidence: {
      approvedAt: "2026-07-28T00:00:00Z",
      approver: "synthetic-independent-approver",
      approverRole: "synthetic-approval-role",
      evidence: approvalEvidence,
    },
    result: "PASS",
  };
  for (const [classification, ids] of [
    ["local", [...passing.taskRecords.local, ...passing.testRecords.local]],
    ["external", [...passing.taskRecords.external, ...passing.testRecords.external]],
  ]) {
    const template = passing.recordTemplates[classification];
    for (const id of ids) {
      const kind = id.startsWith("F07-T") ? "task" : "test";
      const evidencePath = resolve(temporary, `${id}.json`);
      await writeJson(evidencePath, {
        provenance: provenanceReference,
        record: {
          command: template.command,
          durationMs: template.durationMs,
          environment: template.environment,
          id,
          kind,
          result: "PASS",
        },
        releaseCommit: passing.release.commit,
        schemaVersion: 1,
      });
      passing.overrides[id] = {
        evidence: {
          path: relative(repoRoot, evidencePath),
          sha256: await sha256File(evidencePath),
        },
        result: "PASS",
      };
    }
  }
  const ciEntries = [];
  for (const id of requiredCiLaneIds) {
    const evidencePath = resolve(temporary, `${id}.json`);
    const record = {
      command: `synthetic-ci-command ${id}`,
      durationMs: 1,
      environment: "synthetic-ci",
      id,
      kind: "test",
      result: "PASS",
    };
    await writeJson(evidencePath, {
      provenance: provenanceReference,
      record,
      releaseCommit: passing.release.commit,
      schemaVersion: 1,
    });
    ciEntries.push({
      ...record,
      evidence: {
        path: relative(repoRoot, evidencePath),
        sha256: await sha256File(evidencePath),
      },
    });
  }
  const ciBundlePath = resolve(temporary, "ci-evidence-bundle.json");
  await writeJson(ciBundlePath, {
    entries: ciEntries,
    releaseCommit: passing.release.commit,
    schemaVersion: 1,
  });
  passing.ciEvidenceBundle = {
    path: relative(repoRoot, ciBundlePath),
    sha256: await sha256File(ciBundlePath),
  };
  const passingPath = resolve(temporary, "passing-manifest.json");
  await writeFile(passingPath, stableJson(passing));
  const syntheticDecision = await evaluateRelease(passingPath, {
    inventory,
    registry: verifiedRegistry,
  });
  assert(
    syntheticDecision.verdict !== "GO" &&
      syntheticDecision.blockers.some((blocker) =>
        blocker.includes("CI lane metadata or command is not allowlisted"),
      ),
    "synthetic CI commands must never produce GO",
  );
  assert(
    syntheticDecision.blockers.includes(
      "release decision requires a clean tracked and untracked worktree",
    ) === gitMetadata().worktreeDirty,
    "decision-time worktree cleanliness must be evaluated from the actual repository state",
  );
  assert(
    syntheticDecision.errors.some((error) =>
      error.includes("local release record overrides are forbidden"),
    ),
    "local task/test records must never self-select command, result or evidence overrides",
  );
  assert(
    syntheticDecision.errors.some((error) =>
      error.includes("configuration synthetic-verified needs an evidence path and sha256"),
    ),
    "VERIFIED configuration without signed canonical evidence must be invalid",
  );
  assert(
    syntheticDecision.blockers.some((blocker) =>
      blocker.includes("approval verification key is unavailable"),
    ),
    "external PASS with arbitrary approval metadata/file must fail signature verification",
  );
  const allowlistedWithoutOutputEntries = [];
  for (const id of requiredCiLaneIds) {
    const lane = requiredCiLanes[id];
    const operationalKind = {
      "F07-CI-DR-REHEARSAL": "dr",
      "F07-CI-LOAD-SYSTEM": "load",
      "F07-CI-ROLLOUT": "rollout",
      "F07-CI-SOAK-24H": "soak-24h",
    }[id];
    const patternCommand =
      id === "F07-CI-MIGRATION-LIVE"
        ? `node scripts/f07/migration-live-rehearsal.mjs --execute --base-ref ${git.commit} --release-commit ${git.commit}`
        : id === "F07-CI-SUPPLY"
          ? "node scripts/f07/supply-chain.mjs --run --artifact dist,backend/target/workflow-backend-0.1.0-SNAPSHOT.jar --report-dir .f07-evidence/1-1/supply-chain"
          : `node scripts/f07/operational-report.mjs --kind ${operationalKind} --input .f07-evidence/inputs/synthetic.json`;
    const record = {
      command: lane.command ?? patternCommand,
      durationMs: 1,
      environment: "github-actions",
      id,
      kind: "test",
      result: "PASS",
    };
    const evidencePath = resolve(temporary, `${id}-missing-output.json`);
    await writeJson(evidencePath, {
      provenance: provenanceReference,
      record,
      releaseCommit: passing.release.commit,
      runner: {
        name: "scripts/f07/command-evidence.mjs",
        schemaVersion: 1,
      },
      machineReportRaw: null,
      observedCases: [],
      suiteId: lane.suiteId,
      schemaVersion: 1,
      verifiedResultIds: Object.keys(lane.recordRequirements),
    });
    allowlistedWithoutOutputEntries.push({
      ...record,
      evidence: {
        path: relative(repoRoot, evidencePath),
        sha256: await sha256File(evidencePath),
      },
      machineReportRaw: null,
      observedCases: [],
      suiteId: lane.suiteId,
      verifiedResultIds: Object.keys(lane.recordRequirements),
    });
  }
  const missingOutputBundlePath = resolve(temporary, "ci-missing-output-bundle.json");
  await writeJson(missingOutputBundlePath, {
    entries: allowlistedWithoutOutputEntries,
    releaseCommit: passing.release.commit,
    schemaVersion: 1,
  });
  const missingOutputManifest = structuredClone(passing);
  missingOutputManifest.ciEvidenceBundle = {
    path: relative(repoRoot, missingOutputBundlePath),
    sha256: await sha256File(missingOutputBundlePath),
  };
  const missingOutputManifestPath = resolve(temporary, "ci-missing-output-manifest.json");
  await writeJson(missingOutputManifestPath, missingOutputManifest);
  const missingOutputDecision = await evaluateRelease(missingOutputManifestPath, {
    inventory,
    registry: verifiedRegistry,
  });
  assert(
    missingOutputDecision.blockers.some((blocker) =>
      blocker.includes("machine report is invalid") ||
      blocker.includes("structured runner output is missing or inconsistent"),
    ),
    "allowlisted CI commands cannot synthesize record IDs without machine reports",
  );
  const buildLane = requiredCiLanes["F07-CI-BUILD"];
  const unsafeCommandOutput = {
    errorCode: "ETIMEDOUT",
    exitCode: 0,
    signal: "SIGKILL",
    stderrSha256: "0".repeat(64),
    stdoutSha256: "0".repeat(64),
    timedOut: true,
  };
  assert(
    !commandOutputProvesSuccess(unsafeCommandOutput),
    "exit code zero cannot override a timeout, signal, or execution error",
  );
  const unsafeBuildRecord = {
    command: buildLane.command,
    commandOutput: unsafeCommandOutput,
    durationMs: 1,
    environment: "github-actions",
    id: "F07-CI-BUILD",
    kind: "test",
    machineReportRaw: null,
    machineReportSha256: sha256Bytes(stableJson(null)),
    observedCases: [],
    result: "PASS",
    suiteId: buildLane.suiteId,
    verifiedResultIds: [],
  };
  const unsafeBuildEvidencePath = resolve(
    temporary,
    "unsafe-command-output-build.json",
  );
  await writeJson(unsafeBuildEvidencePath, {
    ...unsafeBuildRecord,
    provenance: provenanceReference,
    record: {
      command: unsafeBuildRecord.command,
      durationMs: unsafeBuildRecord.durationMs,
      environment: unsafeBuildRecord.environment,
      id: unsafeBuildRecord.id,
      kind: unsafeBuildRecord.kind,
      result: unsafeBuildRecord.result,
    },
    releaseCommit: passing.release.commit,
    runner: {
      name: "scripts/f07/command-evidence.mjs",
      schemaVersion: 1,
    },
    schemaVersion: 1,
  });
  const unsafeBuildBundlePath = resolve(
    temporary,
    "unsafe-command-output-bundle.json",
  );
  await writeJson(unsafeBuildBundlePath, {
    entries: [
      {
        ...unsafeBuildRecord,
        evidence: {
          path: relative(repoRoot, unsafeBuildEvidencePath),
          sha256: await sha256File(unsafeBuildEvidencePath),
        },
      },
    ],
    releaseCommit: passing.release.commit,
    schemaVersion: 1,
  });
  const unsafeBuildManifest = structuredClone(passing);
  unsafeBuildManifest.ciEvidenceBundle = {
    path: relative(repoRoot, unsafeBuildBundlePath),
    sha256: await sha256File(unsafeBuildBundlePath),
  };
  const unsafeBuildManifestPath = resolve(
    temporary,
    "unsafe-command-output-manifest.json",
  );
  await writeJson(unsafeBuildManifestPath, unsafeBuildManifest);
  const unsafeBuildDecision = await evaluateRelease(unsafeBuildManifestPath, {
    inventory,
    registry: verifiedRegistry,
  });
  assert(
    unsafeBuildDecision.blockers.includes(
      "F07-CI-BUILD: structured runner output is missing or inconsistent",
    ),
    "hand-crafted bundle/bound evidence cannot bypass failed command classification",
  );
  const migrationHistory = [{ checksum: 707, success: true, version: "24" }];
  const syntheticMigrationReleaseCommit = "1".repeat(40);
  const validMigrationResult = {
    database: { database: "vms_f07_preflight" },
    kind: "migration-live-v1",
    liveHistory: migrationHistory,
    liveHistorySha256: sha256Bytes(stableJson(migrationHistory)),
    protectedBaseCommit: protectedMigrationBaseCommit,
    releaseCommit: syntheticMigrationReleaseCommit,
    result: "PASS",
    schemaVersion: 1,
    sourceHistory: migrationHistory,
    sourceHistorySha256: sha256Bytes(stableJson(migrationHistory)),
  };
  assert(
    validateMigrationLiveResult(validMigrationResult, syntheticMigrationReleaseCommit, {
      blockers: [],
    }),
    "valid migration-live semantic evidence must pass",
  );
  for (const [label, mutate] of [
    ["wrong protected base", (result) => {
      result.protectedBaseCommit = "0".repeat(40);
    }],
    ["unsafe database suffix", (result) => {
      result.database.database = "vms_workflow";
    }],
    ["forged history hash", (result) => {
      result.liveHistorySha256 = "0".repeat(64);
    }],
    ["empty histories", (result) => {
      result.sourceHistory = [];
      result.liveHistory = [];
      result.sourceHistorySha256 = sha256Bytes(stableJson([]));
      result.liveHistorySha256 = sha256Bytes(stableJson([]));
    }],
    ["malformed history entry", (result) => {
      result.sourceHistory[0].success = "true";
      result.liveHistory[0].success = "true";
      result.sourceHistorySha256 = sha256Bytes(stableJson(result.sourceHistory));
      result.liveHistorySha256 = sha256Bytes(stableJson(result.liveHistory));
    }],
    ["unordered duplicate history", (result) => {
      result.sourceHistory = [
        { checksum: 707, success: true, version: "24" },
        { checksum: 706, success: true, version: "23" },
      ];
      result.liveHistory = structuredClone(result.sourceHistory);
      result.sourceHistorySha256 = sha256Bytes(stableJson(result.sourceHistory));
      result.liveHistorySha256 = sha256Bytes(stableJson(result.liveHistory));
    }],
  ]) {
    const invalid = structuredClone(validMigrationResult);
    mutate(invalid);
    const state = { blockers: [] };
    assert(
      !validateMigrationLiveResult(
        invalid,
        syntheticMigrationReleaseCommit,
        state,
      ) &&
        state.blockers.length === 1,
      `otherwise-PASS migration result with ${label} must fail semantically`,
    );
  }

  const arbitrary = structuredClone(passing);
  arbitrary.overrides["F07-REL-001"].evidence = {
    path: "scripts/f07/release-gate.mjs",
    sha256: await sha256File(repoPath("scripts/f07/release-gate.mjs")),
  };
  const arbitraryPath = resolve(temporary, "arbitrary-manifest.json");
  await writeJson(arbitraryPath, arbitrary);
  assert(
    (await evaluateRelease(arbitraryPath, { inventory, registry: verifiedRegistry }))
      .verdict !== "GO",
    "arbitrary file plus checksum must never qualify as PASS evidence",
  );

  const mismatchedEvidencePath = resolve(temporary, "mismatched-record.json");
  await writeJson(mismatchedEvidencePath, {
    provenance: provenanceReference,
    record: {
      command: passing.recordTemplates.local.command,
      durationMs: passing.recordTemplates.local.durationMs,
      environment: passing.recordTemplates.local.environment,
      id: "F07-REL-WRONG",
      kind: "test",
      result: "PASS",
    },
    releaseCommit: passing.release.commit,
    schemaVersion: 1,
  });
  const mismatched = structuredClone(passing);
  mismatched.overrides["F07-REL-002"].evidence = {
    path: relative(repoRoot, mismatchedEvidencePath),
    sha256: await sha256File(mismatchedEvidencePath),
  };
  const mismatchedPath = resolve(temporary, "mismatched-manifest.json");
  await writeJson(mismatchedPath, mismatched);
  assert(
    (await evaluateRelease(mismatchedPath, { inventory, registry: verifiedRegistry }))
      .verdict !== "GO",
    "mismatched record identity must never qualify as PASS",
  );

  const wrongProvenance = { ...provenance, commit: "0".repeat(40) };
  const wrongProvenancePath = resolve(temporary, "wrong-provenance.json");
  await writeJson(wrongProvenancePath, wrongProvenance);
  const wrongEvidencePath = resolve(temporary, "wrong-provenance-evidence.json");
  await writeJson(wrongEvidencePath, {
    provenance: {
      path: relative(repoRoot, wrongProvenancePath),
      sha256: await sha256File(wrongProvenancePath),
    },
    record: {
      command: passing.recordTemplates.local.command,
      durationMs: passing.recordTemplates.local.durationMs,
      environment: passing.recordTemplates.local.environment,
      id: "F07-REL-004",
      kind: "test",
      result: "PASS",
    },
    releaseCommit: passing.release.commit,
    schemaVersion: 1,
  });
  const wrongCommit = structuredClone(passing);
  wrongCommit.overrides["F07-REL-004"].evidence = {
    path: relative(repoRoot, wrongEvidencePath),
    sha256: await sha256File(wrongEvidencePath),
  };
  const wrongCommitPath = resolve(temporary, "wrong-provenance-manifest.json");
  await writeJson(wrongCommitPath, wrongCommit);
  assert(
    (await evaluateRelease(wrongCommitPath, { inventory, registry: verifiedRegistry }))
      .verdict !== "GO",
    "wrong-commit provenance must prevent false GO",
  );

  const symlinkEvidencePath = resolve(temporary, "symlink-evidence.json");
  await symlink(resolve(temporary, "F07-REL-001.json"), symlinkEvidencePath);
  const symlinked = structuredClone(passing);
  symlinked.overrides["F07-REL-001"].evidence = {
    path: relative(repoRoot, symlinkEvidencePath),
    sha256: await sha256File(resolve(temporary, "F07-REL-001.json")),
  };
  const symlinkedPath = resolve(temporary, "symlinked-manifest.json");
  await writeJson(symlinkedPath, symlinked);
  assert(
    (await evaluateRelease(symlinkedPath, { inventory, registry: verifiedRegistry }))
      .verdict !== "GO",
    "symlinked evidence reference must prevent false GO",
  );

  const noCi = structuredClone(passing);
  noCi.ciEvidenceBundle = null;
  const noCiPath = resolve(temporary, "no-ci-manifest.json");
  await writeJson(noCiPath, noCi);
  assert(
    (await evaluateRelease(noCiPath, { inventory, registry: verifiedRegistry }))
      .verdict !== "GO",
    "missing mandatory CI evidence bundle must prevent false GO",
  );

  const missingInventory = structuredClone(inventory);
  missingInventory.testIds.pop();
  const missing = await evaluateRelease(manifestPath, { inventory: missingInventory });
  assert(
    missing.errors.some((error) => error.includes("required inventory is missing test ID")),
    "canonical test missing from required inventory must invalidate the gate",
  );

  const waived = structuredClone(manifest);
  waived.recordTemplates.local.exception = {
    expiresAt: "2099-01-01T00:00:00Z",
    owner: "same-owner",
    reason: "synthetic invalid waiver",
  };
  const waivedPath = resolve(temporary, "waived-manifest.json");
  await writeFile(waivedPath, stableJson(waived));
  const waivedResult = await evaluateRelease(waivedPath, {
    inventory,
    registry: verifiedRegistry,
  });
  assert(
    waivedResult.errors.some((error) =>
      error.includes("local release record overrides are forbidden"),
    ) ||
      waivedResult.blockers.some((blocker) =>
        blocker.includes("canonical result"),
      ),
    "local P0/P1 records must not be waivable outside canonical CI results",
  );

  const drift = structuredClone(passing);
  drift.overrides["F07-REL-003"].evidence.sha256 = "0".repeat(64);
  const driftPath = resolve(temporary, "drift-manifest.json");
  await writeFile(driftPath, stableJson(drift));
  const driftResult = await evaluateRelease(driftPath, {
    inventory,
    registry: verifiedRegistry,
  });
  assert(
    driftResult.errors.some((error) =>
      error.includes("local release record overrides are forbidden"),
    ),
    "local evidence override attempts must be invalid instead of selectable",
  );

  const schemaCommand = run(process.execPath, [
    "scripts/f07/release-gate.mjs",
    "--schema-only",
  ]);
  const releaseCommand = run(process.execPath, ["scripts/f07/release-gate.mjs"]);
  assert(schemaCommand.status === 0, "explicit schema-only release validation must pass");
  assert(releaseCommand.status !== 0, "normal release command must exit nonzero for NO_GO");
}

async function loadHarnessTest() {
  const server = createServer((_request, response) => {
    response.writeHead(200, { "content-type": "application/json" });
    response.end('{"status":"UP"}');
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  try {
    const address = server.address();
    const report = await runProfile(`http://127.0.0.1:${address.port}`, {
      budget: { maximumErrorRate: 0, p95Ms: 1000 },
      concurrency: 2,
      id: "self-test",
      iterations: 6,
      requests: [{ method: "GET", path: "/health" }],
    });
    assert(report.result === "PASS" && report.requests === 6, "local load fixture must pass");
    await expectReject(
      () =>
        runProfile("https://example.com", {
          concurrency: 1,
          id: "unsafe",
          iterations: 1,
          requests: [{ method: "GET", path: "/" }],
        }),
      "non-loopback load targets must fail",
    );
  } finally {
    server.close();
    await once(server, "close");
  }
}

async function fileSafetyTests(temporary) {
  const exclusivePath = resolve(temporary, "exclusive.json");
  await writeJson(exclusivePath, { first: true });
  await expectReject(
    () => writeJson(exclusivePath, { overwritten: true }),
    "evidence writes must never overwrite",
  );
  const realParent = resolve(temporary, "real-parent");
  const linkedParent = resolve(temporary, "linked-parent");
  await mkdir(realParent);
  await symlink(realParent, linkedParent);
  await expectReject(
    () => writeJson(resolve(linkedParent, "unsafe.json"), { unsafe: true }),
    "output symlink traversal must fail",
  );
  const timed = run(
    process.execPath,
    ["-e", "setTimeout(() => {}, 10000)"],
    { timeoutMs: 50 },
  );
  assert(
    timed.status !== 0 &&
      timed.timedOut &&
      !commandExecutionSucceeded(timed),
    "external process execution must have a classified hard timeout",
  );
  const missingExecutable = run(
    `f07-self-test-missing-executable-${process.pid}`,
    [],
  );
  assert(
    missingExecutable.status === null &&
      missingExecutable.error?.code === "ENOENT" &&
      !commandExecutionSucceeded(missingExecutable),
    "missing child executable must be a classified command-evidence failure",
  );
}

async function executeSelfTests(temporary) {
  const started = performance.now();
  await releaseGateTests(temporary);
  await loadHarnessTest();
  await fileSafetyTests(temporary);

  const git = gitMetadata();
  const provenanceInputs = [
    "scripts/f07/release-gate.mjs",
    "backend/compose.yaml",
    "scripts/f07/bootstrap-database-roles.sql",
  ];
  const firstProvenance = await createProvenance(provenanceInputs, {
    expectedCommit: git.commit,
  });
  const secondProvenance = await createProvenance(provenanceInputs, {
    expectedCommit: git.commit,
  });
  assert(stableJson(firstProvenance) === stableJson(secondProvenance), "provenance must be deterministic");
  assert(
    firstProvenance.composeImages.every((image) => /@sha256:[0-9a-f]{64}$/.test(image)) &&
      firstProvenance.artifacts.some(
        (artifact) => artifact.path === "backend/compose.yaml",
      ) &&
      firstProvenance.artifacts.some(
        (artifact) => artifact.path === "scripts/f07/bootstrap-database-roles.sql",
      ),
    "provenance must bind pinned compose images and role bootstrap",
  );
  for (const requiredInput of [
    ".github/workflows/f07-release-evidence.yml",
    "package-lock.json",
    "backend/pom.xml",
    "backend/src/main/resources/db/migration",
    "docs/features/07-hardening-go-live",
    "dist",
    "backend/target/workflow-backend-0.1.0-SNAPSHOT.jar",
    "scripts/f07",
  ]) {
    assert(
      canonicalProvenanceInputs.includes(requiredInput),
      `canonical provenance policy is missing ${requiredInput}`,
    );
  }
  await expectReject(
    () => createProvenance([], { expectedCommit: git.commit }),
    "empty expected provenance inputs must fail",
  );
  await expectReject(
    () =>
      createProvenance(
        ["does-not-exist", "backend/compose.yaml", "scripts/f07/bootstrap-database-roles.sql"],
        { expectedCommit: git.commit },
      ),
    "missing expected provenance input must fail",
  );
  await expectReject(
    () =>
      createProvenance(provenanceInputs, {
        expectedCommit: "0".repeat(40),
      }),
    "current commit mismatch must fail",
  );
  await expectReject(
    () =>
      createProvenance(provenanceInputs, {
        expectedCommit: git.commit,
        requireClean: true,
      }),
    "untracked/dirty worktree must fail clean provenance",
  );

  const migration = await staticPreflight();
  assert(migration.result === "PASS", `migration schema findings: ${migration.findings.join("; ")}`);
  assert(
    /^[0-9a-f]{64}$/.test(migration.roleBootstrap.sha256 ?? ""),
    "migration schema proof must checksum the platform-admin role bootstrap",
  );
  const roleBootstrap = await readFile(
    resolve(repoRoot, "scripts/f07/bootstrap-database-roles.sql"),
    "utf8",
  );
  assert(
    validatesRoleBootstrapContract(roleBootstrap),
    "role bootstrap must include every required capability role",
  );
  assert(
    !validatesRoleBootstrapContract(
      roleBootstrap.replaceAll("'vms_migration_processor'", "'removed_processor'"),
    ),
    "role bootstrap validation must fail when vms_migration_processor is absent",
  );
  for (const [sql, expectedOperation] of [
    ["ALTER TABLE evidence DROP COLUMN payload", "DROP_COLUMN"],
    ["DROP SEQUENCE evidence_sequence", "DROP_OBJECT"],
    ["DROP VIEW evidence_view", "DROP_OBJECT"],
    ["DROP TYPE evidence_type", "DROP_OBJECT"],
    ["DROP INDEX evidence_index", "DROP_OBJECT"],
    ["ALTER TABLE evidence ALTER COLUMN amount TYPE SMALLINT", "ALTER_COLUMN_TYPE"],
    ["UPDATE evidence SET state = 'lost'", "UNQUALIFIED_UPDATE"],
  ]) {
    assert(
      destructiveSqlOperations(sql).includes(expectedOperation),
      `migration policy must detect ${expectedOperation}`,
    );
  }
  assert(
    destructiveSqlOperations(
      "UPDATE evidence SET state = 'safe' WHERE id = '00000000-0000-0000-0000-000000000000'",
    ).length === 0,
    "qualified UPDATE must not be misclassified as a mass rewrite",
  );
  assert(
    !destructiveSqlOperations(`
      ALTER TABLE evidence ALTER COLUMN organization_id SET NOT NULL;
      -- A later leave type constraint must not span SQL statements.
      ALTER TABLE leave_types ADD CONSTRAINT uq_leave_type UNIQUE (id);
    `).includes("ALTER_COLUMN_TYPE"),
    "SET NOT NULL must not be misclassified by a later TYPE token",
  );
  const invalidBase = await staticPreflight("0".repeat(40));
  assert(
    invalidBase.result === "FAIL" && invalidBase.findings.some((item) => item.includes("trusted")),
    "untrusted migration base must fail",
  );
  const headAsBase = await staticPreflight(git.commit, {
    trustedBaseRef: git.commit,
  });
  assert(
    headAsBase.result === "FAIL" &&
      headAsBase.findings.some((item) => item.includes("strict ancestor")),
    "HEAD must never qualify as the protected migration baseline",
  );
  const incompleteRehearsalPath = resolve(temporary, "rehearsal.json");
  await writeFile(incompleteRehearsalPath, '{"flywayValidate":true}\n');
  assert(
    (await validateRehearsalEvidence(incompleteRehearsalPath)).result === "FAIL",
    "incomplete live migration rehearsal cannot pass",
  );
  const arbitraryMigrationProofPath = resolve(temporary, "arbitrary-migration-proof.json");
  await writeJson(arbitraryMigrationProofPath, { unrelated: true });
  const arbitraryMigrationReference = {
    path: relative(repoRoot, arbitraryMigrationProofPath),
    sha256: await sha256File(arbitraryMigrationProofPath),
  };
  const syntheticRehearsalPath = resolve(temporary, "synthetic-rehearsal.json");
  await writeJson(syntheticRehearsalPath, {
    executions: Object.entries(requiredMigrationExecutions).map(([id, expected]) => ({
      command: expected.command,
      commandSha256: sha256Bytes(expected.command),
      durationMs: 1,
      evidence: arbitraryMigrationReference,
      exitCode: 0,
      id,
      result: "PASS",
    })),
    provenance: arbitraryMigrationReference,
    record: {
      durationMs: 1,
      id: "F07-REL-003",
      kind: "test",
      result: "PASS",
    },
    releaseCommit: git.commit,
    schemaVersion: 1,
  });
  assert(
    (
      await validateRehearsalEvidence(syntheticRehearsalPath, {
        liveHistory: [],
        releaseCommit: git.commit,
        sourceMigrations: [],
      })
    ).result === "FAIL",
    "self-declared migration PASS records with arbitrary files must fail",
  );
  assert(
    run(process.execPath, ["scripts/f07/migration-preflight.mjs"]).status !== 0,
    "release migration preflight without trusted base/live evidence must fail",
  );

  const policy = await readJson(
    repoPath("docs/features/07-hardening-go-live/rollout-policy.json"),
  );
  assert(validatePolicy(policy).result === "PASS", "rollout policy must be valid");
  await expectReject(
    async () =>
      decideCanary(policy, {
        errorRate: 0,
        integrityFailures: 0,
        observationSeconds: 1000,
        p95Ms: 10,
      }),
    "missing strict canary request count must fail",
  );
  assert(
    decideCanary(policy, {
      errorRate: 0,
      integrityFailures: 1,
      observationSeconds: 1000,
      p95Ms: 10,
      requestCount: 100,
    }).decision === "ABORT_AND_ROLLBACK",
    "integrity failure must abort canary",
  );
  assert(
    decideCanary(policy, {
      errorRate: 0,
      integrityFailures: 0,
      observationSeconds: 100,
      p95Ms: 10,
      requestCount: 100,
    }).decision === "HOLD",
    "incomplete canary observation must hold",
  );
  assert(
    decideCanary(policy, {
      errorRate: 0,
      integrityFailures: 0,
      observationSeconds: 1000,
      p95Ms: 10,
      requestCount: 100,
    }).decision === "ADVANCE",
    "complete healthy canary must advance",
  );
  assert(
    run(process.execPath, [
      "scripts/f07/rollout-verify.mjs",
      "--metrics",
      "scripts/f07/fixtures/canary-advance.json",
    ]).status === 0,
    "only an ADVANCE canary command should exit zero",
  );
  assert(
    run(process.execPath, [
      "scripts/f07/rollout-verify.mjs",
      "--metrics",
      "scripts/f07/fixtures/canary-hold.json",
    ]).status !== 0,
    "HOLD canary command must exit nonzero",
  );
  assert(
    verifyRollback(
      policy,
      Object.fromEntries(policy.rollback.requiredEvidence.map((field) => [field, true])),
    ).result === "PASS",
    "complete rollback evidence must pass",
  );

  const dr = await validateLocalAssertions();
  assert(
    dr.result === "ACTION_REQUIRED" &&
      dr.missing.length === requiredRestoreAssertions.length &&
      requiredRestoreAssertions.length === 9,
    "DR cannot pass without every local assertion",
  );
  const arbitraryRestoreProofPath = resolve(temporary, "arbitrary-restore-proof.json");
  await writeJson(arbitraryRestoreProofPath, { unrelated: true });
  const arbitraryRestoreReference = {
    path: relative(repoRoot, arbitraryRestoreProofPath),
    sha256: await sha256File(arbitraryRestoreProofPath),
  };
  const arbitraryAssertionsPath = resolve(temporary, "arbitrary-restore-assertions.json");
  await writeJson(arbitraryAssertionsPath, {
    assertions: Object.fromEntries(
      requiredRestoreAssertions.map((assertion) => [
        assertion,
        {
          command: "self-declared",
          durationMs: 1,
          evidence: arbitraryRestoreReference,
          result: "PASS",
        },
      ]),
    ),
    releaseCommit: git.commit,
    schemaVersion: 1,
  });
  assert(
    (
      await validateLocalAssertions(arbitraryAssertionsPath, {
        releaseCommit: git.commit,
      })
    ).result === "FAIL",
    "self-declared DR assertions with arbitrary files must fail",
  );
  const privateRestoreTemporary = await createPrivateRestoreTemporary("self-test.bin");
  try {
    const details = await lstat(privateRestoreTemporary.path);
    assert(
      details.isFile() &&
        !privateRestoreTemporary.path.startsWith(resolve(temporary, "backups")),
      "restore plaintext must use an exclusive private temporary file",
    );
  } finally {
    await rm(privateRestoreTemporary.root, { force: true, recursive: true });
  }
  const syntheticBackupManifest = {
    backupId: "00000000-0000-4000-8000-000000000707",
    createdAt: "2026-07-28T00:00:00Z",
    releaseCommit: git.commit,
  };
  const integrityKey = "independent-integrity-key-32-characters-minimum";
  const authentication = authenticateManifest(syntheticBackupManifest, integrityKey);
  verifyAuthenticatedManifest(syntheticBackupManifest, authentication, integrityKey);
  validateBackupFreshness(
    syntheticBackupManifest,
    git.commit,
    24,
    Date.parse("2026-07-28T01:00:00Z"),
  );
  await expectReject(
    async () =>
      verifyAuthenticatedManifest(
        { ...syntheticBackupManifest, releaseCommit: "0".repeat(40) },
        authentication,
        integrityKey,
      ),
    "tampered backup manifest must fail HMAC verification",
  );
  await expectReject(
    async () => authenticateManifest(syntheticBackupManifest, "too-short"),
    "short backup integrity key must fail",
  );
  await expectReject(
    async () =>
      validateBackupFreshness(
        syntheticBackupManifest,
        git.commit,
        1,
        Date.parse("2026-07-30T00:00:00Z"),
      ),
    "stale authenticated backup must fail",
  );
  const replayLedgerRoot = resolve(temporary, "restore-ledger");
  const replayClaim = await assertRestoreNotReplayed(
    syntheticBackupManifest.backupId,
    "synthetic_f07_drill",
    replayLedgerRoot,
  );
  await writeJson(replayClaim.ledgerPath, { status: "RESERVED" });
  await releaseRestoreClaim(replayClaim);
  await expectReject(
    () =>
      assertRestoreNotReplayed(
        syntheticBackupManifest.backupId,
        "synthetic_f07_drill",
        replayLedgerRoot,
      ),
    "reserved backup/target pair must be rejected as replay",
  );
  const authorizedRetryClaim = await assertRestoreNotReplayed(
    syntheticBackupManifest.backupId,
    "synthetic_f07_drill",
    replayLedgerRoot,
    { authorizedRetry: true },
  );
  assert(
    authorizedRetryClaim.ledgerPath !== replayClaim.ledgerPath,
    "authorized retry must create a new append-only restore attempt",
  );
  await releaseRestoreClaim(authorizedRetryClaim);
  const concurrentLedgerRoot = resolve(temporary, "restore-ledger-process");
  const claimant = spawn(
    process.execPath,
    [
      "--input-type=module",
      "--eval",
      `process.argv[1] = "f07-claim-child"; const { claimRestoreAttempt, releaseRestoreClaim } = await import(${JSON.stringify(
        new URL("./backup-drill.mjs", import.meta.url).href,
      )}); const claim = await claimRestoreAttempt("process-backup", "process_f07_drill", ${JSON.stringify(
        concurrentLedgerRoot,
      )}); process.stdout.write("CLAIMED\\n"); await new Promise((resolve) => setTimeout(resolve, 3000)); await releaseRestoreClaim(claim);`,
    ],
    { stdio: ["ignore", "pipe", "pipe"] },
  );
  await new Promise((resolveClaim, rejectClaim) => {
    claimant.stdout.once("data", (chunk) => {
      if (String(chunk).includes("CLAIMED")) resolveClaim();
      else rejectClaim(new Error("concurrent claim process did not report readiness"));
    });
    claimant.once("error", rejectClaim);
    claimant.once("exit", (code) => {
      if (code && code !== 0) rejectClaim(new Error("concurrent claim process failed"));
    });
  });
  await expectReject(
    () =>
      assertRestoreNotReplayed(
        "process-backup",
        "process_f07_drill",
        concurrentLedgerRoot,
      ),
    "a concurrent process must not claim the same backup/target pair",
  );
  await once(claimant, "exit");
  await expectReject(
    () =>
      validateDistinctKeyMaterial([
        { id: "passphrase-v1", minimumLength: 24, secret: integrityKey },
        { id: "integrity-v1", minimumLength: 32, secret: integrityKey },
        { id: "signing-v1", minimumLength: 24, secret: "separate-signing-key-material" },
      ]),
    "backup, integrity and signing secrets must be pairwise distinct",
  );
  validateTarMembers(["./safe/", "./safe/object.bin"], [
    "drwx------ 0 user group 0 Jan 1 00:00 ./safe/",
    "-rw------- 0 user group 1 Jan 1 00:00 ./safe/object.bin",
  ]);
  await expectReject(
    async () => validateTarMembers(["../escape"]),
    "tar path traversal must fail before extraction",
  );
  await expectReject(
    async () => validateTarMembers(["./link"], ["lrwxr-xr-x link -> ../escape"]),
    "tar link members must fail before extraction",
  );
  const supplyPlan = supplyChainPlan();
  for (const tool of ["trivy", "npm", "mvn", "semgrep"]) {
    assert(supplyPlan.requiredTools.includes(tool), `supply-chain plan must require ${tool}`);
  }
  assert(
    supplyPlan.steps.some((step) => step.includes("Java/JavaScript")) &&
      supplyPlan.steps.some((step) => step.includes("release artifact")),
    "supply-chain plan must include real SAST and built-artifact scanning",
  );
  validateImageReference(
    "cgr.dev/chainguard/postgres@sha256:dc2f04037c1044a22af76cee4de70b9111885b17c561b939d7ed70103d100759",
  );
  await expectReject(
    async () => validateImageReference("postgres:18-alpine"),
    "mutable image tags must never enter supply-chain scanning",
  );
  await expectReject(
    async () =>
      validateImageReference(
        "gcr.io/INVALID/vms@sha256:"
          + "0".repeat(64),
      ),
    "OCI repository names must be lower case",
  );
  const workerDeployment = await readFile(
    repoPath("backend/deploy/f07-workers.yaml"),
    "utf8",
  );
  const workerImages = [...workerDeployment.matchAll(
    /^\s+image:\s+(\S+)\s*$/gm,
  )].map((match) => match[1]);
  assert(
    workerImages.length === 4,
    "every F07 worker deployment must declare one immutable image",
  );
  for (const workerImage of workerImages) {
    validateImageReference(workerImage);
  }
  const licensePolicy = {
    allowed: [
      "Apache-2.0",
      "GPL-2.0-only WITH classpath-exception",
      "ISC",
      "MIT",
    ],
    denied: ["AGPL-3.0-only", "GPL-2.0-only", "GPL-3.0-only"],
    missingLicenseAction: "FAIL",
    schemaVersion: 1,
  };
  for (const expression of [
    "MIT",
    "MIT AND ISC",
    "MIT OR GPL-3.0-only",
    "GPL-2.0-only WITH classpath-exception",
  ]) {
    assert(
      evaluateLicenseExpression(expression, licensePolicy).approved,
      `approved SPDX expression must pass: ${expression}`,
    );
  }
  for (const expression of [
    "AGPL-3.0-only",
    "MIT AND LicenseRef-Unknown",
    "GPL-2.0-only WITH unknown-exception",
    "MIT AND",
  ]) {
    assert(
      !evaluateLicenseExpression(expression, licensePolicy).approved,
      `forbidden, unknown or malformed SPDX expression must fail: ${expression}`,
    );
  }
  const syntheticManifestInventory = createLicenseInventory(
    {
      components: [
        { name: "backend/pom.xml", type: "application" },
        {
          licenses: [{ expression: "MIT AND ISC" }],
          name: "runtime-library",
          purl: "pkg:npm/runtime-library@1.0.0",
          type: "library",
          version: "1.0.0",
        },
      ],
    },
    licensePolicy,
  );
  assert(
    syntheticManifestInventory.result === "PASS" &&
      syntheticManifestInventory.components.length === 1,
    "license inventory must exclude scanner-input manifests and evaluate SPDX expressions",
  );
  const prohibitedApplicationInventory = createLicenseInventory(
    {
      components: [
        {
          licenses: [{ expression: "AGPL-3.0-only" }],
          name: "third-party-server",
          purl: "pkg:generic/third-party-server@1.0.0",
          type: "application",
          version: "1.0.0",
        },
      ],
    },
    licensePolicy,
  );
  assert(
    prohibitedApplicationInventory.result === "FAIL" &&
      prohibitedApplicationInventory.components.length === 1,
    "third-party application components must never bypass license policy",
  );
  const undisposedMedium = applyMediumRiskDispositions(
    {
      findings: [
        {
          component: "synthetic-component",
          id: "CVE-SYNTHETIC",
          severity: "MEDIUM",
          target: "synthetic-target",
        },
      ],
      kind: "synthetic-scan",
      result: "PASS",
    },
    { entries: [] },
    new Date("2026-07-28T00:00:00Z"),
  );
  assert(
    undisposedMedium.result === "FAIL" &&
      undisposedMedium.mediumDispositionFindings.length === 1,
    "unowned or expired MEDIUM findings must fail the supply-chain gate",
  );
  for (const untrustworthy of [
    { error: new Error("ETIMEDOUT"), signal: null, status: null, stdout: "{}" },
    { error: null, signal: "SIGKILL", status: null, stdout: "{}" },
    { error: null, signal: null, status: 2, stdout: "{}" },
    { error: null, signal: null, status: 1, stdout: "{}" },
    {
      error: null,
      signal: null,
      status: 1,
      stdout: JSON.stringify({
        auditReportVersion: 2,
        error: { code: "EAUDITENDPOINT", summary: "registry unavailable" },
        metadata: {
          vulnerabilities: {
            critical: 0,
            high: 0,
            info: 0,
            low: 0,
            moderate: 0,
            total: 0,
          },
        },
        vulnerabilities: {},
      }),
    },
  ]) {
    await expectReject(
      async () => parseNpmAuditExecution(untrustworthy),
      "npm audit timeout, signal, or unexpected status must fail closed",
    );
  }
  const validSupply = {
    artifacts: [
      {
        artifact: "dist",
        result: "PASS",
      },
      {
        artifact: "backend/target/workflow-backend-0.1.0-SNAPSHOT.jar",
        result: "PASS",
      },
    ],
    cases: [
      "SUPPLY-SCANNERS",
      "SUPPLY-SBOM-LICENSES",
      "SUPPLY-IMAGE-DIGESTS",
      "SUPPLY-SECRETS",
      "SUPPLY-ARTIFACTS",
    ].map((id) => ({
      durationMs: 1,
      id,
      source: "scanner:supply-chain-summary",
      status: "PASSED",
    })),
    images: [
      {
        image:
          "cgr.dev/chainguard/postgres@sha256:dc2f04037c1044a22af76cee4de70b9111885b17c561b939d7ed70103d100759",
        result: "PASS",
      },
    ],
    kind: "supply-chain-v1",
    reports: [
      "repository-secrets",
      "java-node-vulnerabilities",
      "configuration-sast",
      "npm-production-dependencies",
      "java-javascript-sast",
      "license-inventory",
    ].map((kind) => ({ kind, result: "PASS" })),
    result: "PASS",
    sbom: {
      componentCount: 1,
      format: "CycloneDX",
      specVersion: "1.6",
    },
    schemaVersion: 1,
  };
  assert(
    validateSupplyStructuredResult(validSupply, { blockers: [] }),
    "complete exact supply-chain evidence must pass",
  );
  for (const [label, mutate] of [
    ["missing reports", (result) => delete result.reports],
    ["empty artifacts", (result) => {
      result.artifacts = [];
    }],
    ["empty images", (result) => {
      result.images = [];
    }],
    ["missing scanner kind", (result) => {
      result.reports.pop();
    }],
    ["mutable image", (result) => {
      result.images[0].image = "postgres:18-alpine";
    }],
    ["empty SBOM", (result) => {
      result.sbom.componentCount = 0;
    }],
    ["unsupported SBOM spec", (result) => {
      result.sbom.specVersion = "1.0";
    }],
  ]) {
    const invalid = structuredClone(validSupply);
    mutate(invalid);
    const state = { blockers: [] };
    assert(
      !validateSupplyStructuredResult(invalid, state) &&
        state.blockers.length === 1,
      `supply evidence with ${label} must fail closed`,
    );
  }
  assert((await verifyOperations()).result === "PASS", "runbook/alert schema must pass");
  const workflow = await readFile(
    repoPath(".github/workflows/f07-release-evidence.yml"),
    "utf8",
  );
  for (const requiredCommand of [
    "npm run typecheck",
    "npm run lint",
    "npx vitest run",
    "npm run build",
    "backend/pom.xml verify",
    "F07-CI-E2E-WORKFORCE",
    "F07-CI-E2E-DELIVERY",
    "F07-CI-E2E-CERTIFICATION",
    "F07-CI-E2E-FINANCE",
    "F07-CI-E2E-MIGRATION",
    "F07-CI-E2E-ACCESSIBILITY",
    "npm run --silent e2e:finance:system",
    "npm run --silent e2e:migration:system",
    "F07-CI-F07-SYSTEM",
    "npm run --silent e2e:f07:system",
    "F07-CI-DB-BOOTSTRAP",
    "F07-CI-MIGRATION-LIVE",
    "migration-live-rehearsal.mjs --execute",
    "-Dit.test=F07MigrationBootstrapIT verify",
    "F07-CI-OPS",
    "F07-CI-SUPPLY",
  ]) {
    assert(workflow.includes(requiredCommand), `CI is missing mandatory lane: ${requiredCommand}`);
  }
  const playwrightConfiguration = await readFile(
    repoPath("playwright.config.ts"),
    "utf8",
  );
  for (const engine of ["Chromium", "Firefox", "Safari"]) {
    assert(
      playwrightConfiguration.includes(`Desktop ${engine}`) ||
        (engine === "Chromium" && playwrightConfiguration.includes("Pixel 7")),
      `Playwright configuration is missing the ${engine} engine`,
    );
  }
  assert(
    workflow.indexOf("Bind mandatory suite outputs to provenance") <
      workflow.indexOf("Evaluate release decision") &&
      workflow.includes("sha256:62aaded52737fc401299d994f29fcd3d4049bd90bbb77407eca2e29e51ab0d98") &&
      workflow.includes("npx playwright install --with-deps chromium firefox webkit") &&
      !workflow.includes("pipx install") &&
      !workflow.includes("files.pythonhosted.org") &&
      Object.values(requiredCiLanes)
        .filter((lane) => lane.command)
        .every((lane) => workflow.includes(lane.command)),
    "CI must bind suites, install every configured browser and use digest-pinned Semgrep",
  );

  process.stdout.write(
    stableJson({
      cases: [
        "F07-SELF-RELEASE-GATE",
        "F07-SELF-MIGRATION-POLICY",
        "F07-SELF-ROLLOUT-POLICY",
        "F07-SELF-DR-POLICY",
        "F07-SELF-SUPPLY-POLICY",
        "F07-SELF-OPS-DOCS",
        "F07-SELF-CI-CONTRACT",
        "F07-SELF-TRACEABILITY",
        "F07-SELF-REVIEW-CONTROL",
      ].map((id) => ({
        durationMs: Math.max(0, Math.round(performance.now() - started)),
        id,
        source: "node:scripts/f07/self-test.mjs",
        status: "PASSED",
      })),
      checks: [
        "complete required-ID inventory and real NO_GO decision",
        "canonical per-record suite/result policy and decision-time complete provenance",
        "independent P0/P1 exception approval and canonical checksum drift",
        "exclusive atomic writes and symlink rejection",
        "commit-bound nonempty provenance with dirty/untracked rejection",
        "protected strict-base live migration/Flyway/bootstrap proof boundary",
        "strict canary ADVANCE/HOLD/ABORT",
        "HMAC-authenticated, append-only retry-aware backup/DR proof boundary",
        "digest-pinned SAST, MEDIUM dispositions, license and artifact/SBOM gates",
        "mandatory CI suites bound before release with unique run outputs",
        "policy-bound load cardinalities/budgets and forged-report rejection",
        "fail-closed timeout/missing-executable command evidence",
        "loopback-only load harness and 15-runbook schema",
        "complete differentiated task/test impact traceability",
        "five-dimension independent review and issue-ledger evidence",
      ],
      kind: "f07-self-test-v1",
      result: "PASS",
      schemaVersion: 1,
    }),
  );
}

async function main() {
  const temporary = await mkdtemp(resolve(repoRoot, ".f07-self-test-"));
  try {
    await executeSelfTests(temporary);
  } finally {
    await rm(temporary, { force: true, recursive: true });
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    process.stderr.write(`F07 self-test failed: ${safeError(error)}\n`);
    process.exitCode = 1;
  });
}
