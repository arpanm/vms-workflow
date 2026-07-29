#!/usr/bin/env node

import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { requiredCiLanes } from "./evidence-policy.mjs";
import {
  deriveVerifiedRecords,
  parseLaneMachineReport,
} from "./machine-reports.mjs";
import {
  gitMetadata,
  parseArgs,
  safeError,
  stableJson,
} from "./lib.mjs";

const e2eRecords = Array.from(
  { length: 10 },
  (_, index) => `E2E-${String(index + 1).padStart(2, "0")}`,
);
const supportingRecords = ["F07-AUD-001", "F07-OPS-004"];

export async function aggregateEvidence(directory) {
  const commit = gitMetadata().commit;
  const names = (await readdir(directory))
    .filter((name) => name.endsWith(".json"))
    .sort();
  const verified = new Set();
  const consumedLanes = [];
  for (const name of names) {
    const path = resolve(directory, name);
    const raw = JSON.parse(await readFile(path, "utf8"));
    const lane = requiredCiLanes[raw.record?.id];
    if (!lane || raw.schemaVersion !== 1 || raw.releaseCommit !== commit) {
      continue;
    }
    if (
      raw.record?.result !== "PASS"
      || raw.commandOutput?.exitCode !== 0
      || raw.commandOutput?.signal !== null
      || raw.commandOutput?.timedOut !== false
      || raw.suiteId !== lane.suiteId
    ) {
      throw new Error(`${name} is not successful current-commit evidence`);
    }
    const parsed = parseLaneMachineReport(lane, raw.machineReportRaw ?? null);
    const derived = deriveVerifiedRecords(lane, parsed.cases);
    if (
      stableJson(derived) !== stableJson(raw.verifiedResultIds ?? [])
      || stableJson(parsed.cases) !== stableJson(raw.observedCases ?? [])
    ) {
      throw new Error(`${name} does not match its machine report`);
    }
    derived.forEach((record) => verified.add(record));
    consumedLanes.push(raw.record.id);
  }

  return buildAggregate(verified, consumedLanes, commit);
}

export function buildAggregate(verified, consumedLanes, commit) {
  const cases = e2eRecords.map((record) => ({
    durationMs: 0,
    id: `POST-DEPLOY-${record}`,
    source: `aggregate:${record}`,
    status: verified.has(record) ? "PASSED" : "FAILED",
  }));
  cases.push({
    durationMs: 0,
    id: "POST-DEPLOY-AUDIT-OUTBOX",
    source: "aggregate:F07-AUD-001+F07-OPS-004",
    status: supportingRecords.every((record) => verified.has(record))
      ? "PASSED" : "FAILED",
  });
  cases.push({
    durationMs: 0,
    id: "POST-ROLLBACK-INTEGRITY",
    source: "aggregate:E2E-10",
    status: verified.has("E2E-10") ? "PASSED" : "FAILED",
  });
  const passed = cases.every((entry) => entry.status === "PASSED");
  cases.push({
    durationMs: 0,
    id: "POST-DEPLOY-REGRESSION",
    source: "aggregate:all-required-records",
    status: passed ? "PASSED" : "FAILED",
  });
  return {
    cases,
    consumedLanes: [...new Set(consumedLanes)].sort(),
    kind: "post-deploy-regression-v1",
    releaseCommit: commit,
    result: passed ? "PASS" : "FAIL",
    schemaVersion: 1,
    verifiedInputs: [...verified].sort(),
  };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args["evidence-dir"]) {
    throw new Error("--evidence-dir is required");
  }
  const report = await aggregateEvidence(resolve(args["evidence-dir"]));
  process.stdout.write(stableJson(report));
  process.exitCode = report.result === "PASS" ? 0 : 1;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    process.stderr.write(
      `F07 post-deploy regression aggregation failed safely: ${safeError(error)}\n`,
    );
    process.exitCode = 1;
  });
}
