#!/usr/bin/env node

import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import {
  parseArgs,
  readJson,
  repoPath,
  repoRoot,
  safeError,
  stableJson,
  writeJson,
} from "./lib.mjs";

export function validatePolicy(policy) {
  const findings = [];
  const ids = new Set();
  for (const flag of policy.flags ?? []) {
    if (!flag.id || ids.has(flag.id)) {
      findings.push("flag IDs must be present and unique");
    }
    ids.add(flag.id);
    if (!flag.owner || !flag.scope || !flag.defaultState || !flag.effectiveWindow) {
      findings.push(`${flag.id}: owner, scope, defaultState, and effectiveWindow are required`);
    }
    if (flag.serverAuthoritative !== true) {
      findings.push(`${flag.id}: UI-only authority is forbidden`);
    }
    for (const dependency of flag.dependencies ?? []) {
      if (!policy.flags?.some((candidate) => candidate.id === dependency)) {
        findings.push(`${flag.id}: missing dependency ${dependency}`);
      }
    }
  }
  for (const threshold of [
    "maximumErrorRate",
    "maximumP95Ms",
    "maximumIntegrityFailures",
    "minimumObservationSeconds",
  ]) {
    if (!Number.isFinite(policy.canary?.[threshold])) {
      findings.push(`canary.${threshold} must be numeric`);
    }
  }
  return {
    findings: findings.sort(),
    result: findings.length === 0 ? "PASS" : "FAIL",
  };
}

export function decideCanary(policy, metrics) {
  const required = {
    errorRate: (value) => Number.isFinite(value) && value >= 0 && value <= 1,
    integrityFailures: (value) => Number.isInteger(value) && value >= 0,
    observationSeconds: (value) => Number.isFinite(value) && value >= 0,
    p95Ms: (value) => Number.isFinite(value) && value >= 0,
    requestCount: (value) => Number.isInteger(value) && value > 0,
  };
  const invalid = Object.entries(required)
    .filter(([field, predicate]) => !predicate(metrics?.[field]))
    .map(([field]) => field);
  if (invalid.length > 0) {
    throw new Error(`canary metrics are missing or invalid: ${invalid.join(", ")}`);
  }
  const reasons = [];
  if (metrics.integrityFailures > policy.canary.maximumIntegrityFailures) {
    reasons.push("integrity threshold exceeded");
  }
  if (metrics.errorRate > policy.canary.maximumErrorRate) {
    reasons.push("error-rate threshold exceeded");
  }
  if (metrics.p95Ms > policy.canary.maximumP95Ms) {
    reasons.push("latency threshold exceeded");
  }
  if (reasons.length > 0) {
    return { decision: "ABORT_AND_ROLLBACK", reasons };
  }
  if (metrics.observationSeconds < policy.canary.minimumObservationSeconds) {
    return { decision: "HOLD", reasons: ["observation window is incomplete"] };
  }
  return { decision: "ADVANCE", reasons: [] };
}

export function verifyRollback(policy, evidence) {
  const missing = [];
  for (const field of policy.rollback.requiredEvidence ?? []) {
    if (evidence[field] !== true && !evidence[field]) {
      missing.push(field);
    }
  }
  return {
    missing,
    result: missing.length === 0 ? "PASS" : "FAIL",
  };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const policyPath = resolve(
    repoRoot,
    args.policy ?? "docs/features/07-hardening-go-live/rollout-policy.json",
  );
  const policy = await readJson(policyPath);
  const result = { policy: validatePolicy(policy) };
  if (args["schema-only"]) {
    process.stdout.write(stableJson(result));
    process.exitCode = result.policy.result === "PASS" ? 0 : 1;
    return;
  }
  if (args.metrics) {
    result.canary = decideCanary(policy, await readJson(repoPath(args.metrics, "metrics")));
  } else if (args.rollback) {
    result.rollback = verifyRollback(
      policy,
      await readJson(repoPath(args.rollback, "rollback evidence")),
    );
  } else {
    throw new Error("--metrics is required for a canary decision; use --schema-only explicitly");
  }
  if (args.output) {
    await writeJson(repoPath(args.output, "output"), result);
  }
  process.stdout.write(stableJson(result));
  process.exitCode = result.canary
    ? result.policy.result === "PASS" && result.canary.decision === "ADVANCE"
      ? 0
      : 1
    : result.policy.result === "PASS" && result.rollback?.result === "PASS"
      ? 0
      : 1;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    process.stderr.write(`F07 rollout verification failed safely: ${safeError(error)}\n`);
    process.exitCode = 1;
  });
}
