#!/usr/bin/env node

import { createHmac, timingSafeEqual } from "node:crypto";
import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";
import {
  existingRepoPath,
  gitMetadata,
  parseArgs,
  readJson,
  repoPath,
  safeError,
  sha256Bytes,
  stableJson,
} from "./lib.mjs";

const outputKinds = {
  dr: "dr-rehearsal-v1",
  load: "load-rehearsal-v1",
  rollout: "rollout-rehearsal-v1",
  "soak-24h": "soak-24h-v1",
};

const canonicalLoadProfiles = {
  migrationExport: {
    budget: {
      maximumErrorRate: 0,
      maximumP95Ms: 2000,
    },
    caseId: "LOAD-MIGRATION-EXPORT",
    fixtures: {
      attendanceRows: 300000,
      employees: 10000,
      engagements: 500,
    },
    id: "migration-export-mixed",
    path: "scripts/f07/profiles/migration-export-mixed.json",
    requestCounts: {
      attendanceDays: 333,
      financeReports: 333,
      migrationJobs: 334,
    },
    requests: 1000,
    sha256: "931da4096c0e6bc46fc8e6054404971e498ef322159e790b1c2d5b5a902f0aa9",
  },
  packageConcurrency: {
    budget: {
      maximumErrorRate: 0,
      maximumP95Ms: 2000,
    },
    caseId: "LOAD-PACKAGE",
    fixtures: {
      engagementMonths: 1,
      readinessRuns: 1,
    },
    id: "package-determinism-concurrency",
    path: "scripts/f07/profiles/package-concurrency.json",
    requestCounts: {
      packageGeneration: 200,
    },
    requests: 200,
    sha256: "064d8000355921e9035971171f2fbf67b5cac981d0af8d07ec3e012a0e4904f3",
  },
  webhookBurst: {
    acknowledgementBudget: {
      maximumMsExclusive: 5000,
      maximumP95Ms: 1000,
    },
    budget: {
      maximumErrorRate: 0,
      maximumP95Ms: 1000,
    },
    caseId: "LOAD-WEBHOOK",
    fixtures: {
      distinctWebhookDeliveries: 1,
      issueMetadataRows: 100000,
    },
    id: "linear-webhook-duplicate-storm",
    path: "scripts/f07/profiles/webhook-duplicate-storm.json",
    requestCounts: {
      linearHealth: 50000,
      linearWebhook: 50000,
    },
    requests: 100000,
    sha256: "56de980c5abfb9a5c0e7e40ae46d0cf0e216a3eaee5be8f1ed57e4195718ae62",
  },
};

function verifySignature(document) {
  const key = process.env.F07_OPERATIONAL_REPORT_KEY ?? "";
  const { signature, ...unsigned } = document;
  if (
    key.length < 32 ||
    signature?.algorithm !== "HMAC-SHA256" ||
    !signature.keyId ||
    !/^[0-9a-f]{64}$/.test(signature.value ?? "")
  ) {
    throw new Error("operational report signature metadata/key is invalid");
  }
  const expected = Buffer.from(
    createHmac("sha256", key).update(stableJson(unsigned)).digest("hex"),
    "hex",
  );
  const actual = Buffer.from(signature.value, "hex");
  if (expected.length !== actual.length || !timingSafeEqual(expected, actual)) {
    throw new Error("operational report signature verification failed");
  }
}

function exactObject(value, expectedKeys, label) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`${label} must be an object`);
  }
  const actualKeys = Object.keys(value).sort();
  if (stableJson(actualKeys) !== stableJson([...expectedKeys].sort())) {
    throw new Error(`${label} contains unknown or missing fields`);
  }
}

function exactCardinalities(actual, expected, label) {
  exactObject(actual, Object.keys(expected), label);
  for (const [key, value] of Object.entries(expected)) {
    if (!Number.isInteger(actual[key]) || actual[key] !== value) {
      throw new Error(`${label}.${key} must equal canonical cardinality ${value}`);
    }
  }
}

function validateProfileIdentity(identity, policy, label) {
  exactObject(identity, ["id", "path", "sha256"], `${label}.profileIdentity`);
  if (
    identity.id !== policy.id ||
    identity.path !== policy.path ||
    identity.sha256 !== policy.sha256 ||
    !/^[0-9a-f]{64}$/.test(identity.sha256)
  ) {
    throw new Error(`${label} is not bound to its canonical load profile`);
  }
  const checkedInHash = sha256Bytes(readFileSync(repoPath(policy.path, "load profile")));
  if (checkedInHash !== policy.sha256) {
    throw new Error(`${label} canonical load profile changed without policy review`);
  }
}

function passed(id, durationMs) {
  return {
    durationMs,
    id,
    source: "signed-operational-report",
    status: "PASSED",
  };
}

function validateCommon(document, kind) {
  verifySignature(document);
  const durationMs =
    Date.parse(document.finishedAt) - Date.parse(document.startedAt);
  if (
    document.schemaVersion !== 1 ||
    document.kind !== `f07-${kind}-input-v1` ||
    document.releaseCommit !== gitMetadata().commit ||
    document.environment !== "local-isolated" ||
    document.result !== "PASS" ||
    !Number.isFinite(durationMs) ||
    durationMs < 0 ||
    document.durationMs !== durationMs
  ) {
    throw new Error("operational report is not commit-bound, dated, or passing");
  }
  return durationMs;
}

function validateLoad(document, durationMs) {
  exactObject(
    document.profiles,
    Object.keys(canonicalLoadProfiles),
    "load profiles",
  );
  const cases = [];
  for (const [key, policy] of Object.entries(canonicalLoadProfiles)) {
    const profile = document.profiles?.[key];
    exactObject(
      profile,
      [
        "durationMs",
        "fixtures",
        "metrics",
        "profileIdentity",
        "reconciliation",
        "requests",
        "result",
      ],
      `load profiles.${key}`,
    );
    validateProfileIdentity(profile.profileIdentity, policy, `load profiles.${key}`);
    exactCardinalities(
      profile.fixtures,
      policy.fixtures,
      `load profiles.${key}.fixtures`,
    );
    exactObject(
      profile.reconciliation,
      ["countsMatch", "noDuplicateEffects", "tenantIsolation"],
      `load profiles.${key}.reconciliation`,
    );
    if (
      profile.result !== "PASS" ||
      profile.reconciliation.countsMatch !== true ||
      profile.reconciliation.noDuplicateEffects !== true ||
      profile.reconciliation.tenantIsolation !== true
    ) {
      throw new Error(`load profiles.${key} did not pass canonical reconciliation`);
    }
    const metricKeys = [
      "errorRate",
      "failures",
      "p95Ms",
      "requestCounts",
      ...(policy.acknowledgementBudget
        ? [
            "acknowledgedRequests",
            "acknowledgementMaxMs",
            "acknowledgementP95Ms",
            "lostAcknowledgements",
          ]
        : []),
    ];
    exactObject(profile.metrics, metricKeys, `load profiles.${key}.metrics`);
    exactCardinalities(
      profile.metrics.requestCounts,
      policy.requestCounts,
      `load profiles.${key}.metrics.requestCounts`,
    );
    if (!Number.isInteger(profile.requests) || profile.requests !== policy.requests) {
      throw new Error(`load profiles.${key}.requests must equal ${policy.requests}`);
    }
    if (!Number.isFinite(profile.durationMs) || profile.durationMs < 0) {
      throw new Error(`load profiles.${key}.durationMs must be finite and nonnegative`);
    }
    if (
      !Number.isInteger(profile.metrics.failures) ||
      profile.metrics.failures < 0 ||
      profile.metrics.failures > profile.requests ||
      !Number.isFinite(profile.metrics.errorRate) ||
      profile.metrics.errorRate < 0 ||
      profile.metrics.errorRate > policy.budget.maximumErrorRate ||
      profile.metrics.errorRate !== profile.metrics.failures / profile.requests
    ) {
      throw new Error(`load profiles.${key} error metrics are invalid or over budget`);
    }
    if (
      !Number.isFinite(profile.metrics.p95Ms) ||
      profile.metrics.p95Ms < 0 ||
      profile.metrics.p95Ms > policy.budget.maximumP95Ms
    ) {
      throw new Error(`load profiles.${key} p95 is invalid or over budget`);
    }
    if (policy.acknowledgementBudget) {
      const acknowledgedExpected = policy.requestCounts.linearWebhook;
      if (
        !Number.isInteger(profile.metrics.acknowledgedRequests) ||
        profile.metrics.acknowledgedRequests !== acknowledgedExpected ||
        !Number.isInteger(profile.metrics.lostAcknowledgements) ||
        profile.metrics.lostAcknowledgements !== 0 ||
        !Number.isFinite(profile.metrics.acknowledgementP95Ms) ||
        profile.metrics.acknowledgementP95Ms < 0 ||
        profile.metrics.acknowledgementP95Ms >
          policy.acknowledgementBudget.maximumP95Ms ||
        !Number.isFinite(profile.metrics.acknowledgementMaxMs) ||
        profile.metrics.acknowledgementMaxMs < 0 ||
        profile.metrics.acknowledgementMaxMs >=
          policy.acknowledgementBudget.maximumMsExclusive
      ) {
        throw new Error("webhook durable acknowledgement metrics are invalid or over budget");
      }
    }
    cases.push(passed(policy.caseId, profile.durationMs ?? durationMs));
  }
  return cases;
}

function validateSoak(document, durationMs) {
  if (
    !Number.isFinite(document.completedDurationMs) ||
    !Number.isInteger(document.completedDurationMs) ||
    !Number.isFinite(document.targetDurationMs) ||
    !Number.isInteger(document.targetDurationMs) ||
    !Number.isFinite(document.samples) ||
    !Number.isInteger(document.samples) ||
    durationMs < 86_400_000 ||
    document.completedDurationMs < 86_400_000 ||
    document.targetDurationMs < 86_400_000 ||
    document.samples < 24 ||
    Date.parse(document.finishedAt) > Date.now() ||
    document.assertions?.stableMemory !== true ||
    document.assertions?.stableConnections !== true ||
    document.assertions?.boundedQueues !== true ||
    document.assertions?.zeroDuplicateEffects !== true ||
    document.assertions?.zeroLostAcknowledgedEffects !== true ||
    document.assertions?.recoverableDeadLetters !== true
  ) {
    throw new Error("24-hour soak duration/resource/effect assertions are incomplete");
  }
  return [passed("SOAK-24H", durationMs)];
}

function validateDr(document, durationMs) {
  if (
    document.sourceDestroyedBeforeRestore !== true ||
    document.backup?.manifestAuthenticated !== true ||
    document.backup?.encrypted !== true ||
    !String(document.restore?.targetDatabase ?? "").endsWith("_f07_drill") ||
    document.restore?.result !== "PASS"
  ) {
    throw new Error("DR report does not prove backup-destroy-restore isolation");
  }
  const cases = [passed("DR-BACKUP", durationMs), passed("DR-RESTORE", durationMs)];
  if (
    document.backup?.authorizedRole === "vms_backup" &&
    document.backup?.unauthorizedReadDenied === true &&
    document.backup?.unauthorizedRestoreDenied === true &&
    document.backup?.diagnosticsRedacted === true &&
    document.restore?.audited === true
  ) {
    cases.push(passed("DR-CONFIDENTIALITY", durationMs));
  }
  if (
    document.recovery?.transactionBoundary === true &&
    document.recovery?.noOrphanedMetadata === true &&
    document.recovery?.noDuplicateEffects === true
  ) {
    cases.push(passed("DR-RECOVERY-BOUNDARY", durationMs));
  }
  if (
    document.reconciliation?.rowCountsMatch === true &&
    document.reconciliation?.flywayHistoryMatch === true &&
    document.reconciliation?.objectHashesMatch === true &&
    document.reconciliation?.accessRevalidated === true &&
    document.reconciliation?.providerStateExplicitlyStale === true
  ) {
    cases.push(passed("DR-RECONCILE", durationMs));
  }
  return cases;
}

function validateRollout(document, durationMs) {
  const cases = [];
  if (
    document.canary?.advance === "ADVANCE" &&
    document.canary?.incomplete === "HOLD" &&
    document.canary?.thresholdBreach === "ABORT_AND_ROLLBACK" &&
    document.canary?.integrityBreach === "ABORT_AND_ROLLBACK"
  ) {
    cases.push(passed("ROLLOUT-CANARY", durationMs));
  }
  if (
    document.rollback?.flagsDisabled === true &&
    document.rollback?.integrationsDisabled === true &&
    document.rollback?.previousArtifactVerified === true &&
    document.rollback?.schemaCompatible === true &&
    document.rollback?.newEventsPreserved === true &&
    document.rollback?.auditOutboxReconciled === true
  ) {
    cases.push(passed("ROLLOUT-ROLLBACK", durationMs));
  }
  return cases;
}

export function validateOperationalDocument(kind, document) {
  if (!outputKinds[kind]) throw new Error("unknown operational report kind");
  const durationMs = validateCommon(document, kind);
  const cases =
    kind === "load"
      ? validateLoad(document, durationMs)
      : kind === "soak-24h"
        ? validateSoak(document, durationMs)
        : kind === "dr"
          ? validateDr(document, durationMs)
          : validateRollout(document, durationMs);
  if (cases.length === 0) {
    throw new Error("operational report proves no canonical cases");
  }
  return {
    cases,
    kind: outputKinds[kind],
    releaseCommit: document.releaseCommit,
    result: "PASS",
    schemaVersion: 1,
    sourceReport: document,
  };
}

export async function verifyOperationalReport(kind, path) {
  return validateOperationalDocument(
    kind,
    await readJson(await existingRepoPath(path, "operational report")),
  );
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.kind || !args.input) {
    throw new Error("--kind and --input are required");
  }
  process.stdout.write(
    stableJson(await verifyOperationalReport(String(args.kind), String(args.input))),
  );
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    process.stderr.write(`F07 operational report failed safely: ${safeError(error)}\n`);
    process.exitCode = 1;
  });
}
