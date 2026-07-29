#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import {
  parseArgs,
  readJson,
  repoPath,
  safeError,
  stableJson,
  writeJson,
} from "./lib.mjs";

const requiredTabletopFields = [
  "correlationId",
  "detectedAt",
  "diagnostics",
  "safeActions",
  "escalation",
  "communications",
  "closureEvidence",
];

export async function verifyOperations() {
  const started = performance.now();
  const catalog = await readJson(
    repoPath("docs/features/07-hardening-go-live/runbook-catalog.json"),
  );
  const policy = await readJson(
    repoPath("docs/features/07-hardening-go-live/observability-policy.json"),
  );
  const runbook = await readFile(repoPath("docs/operations/F07-RUNBOOKS.md"), "utf8");
  const findings = [];
  if (catalog.runbooks?.length !== 15) {
    findings.push(`expected 15 runbooks, found ${catalog.runbooks?.length ?? 0}`);
  }
  const ids = new Set();
  for (const entry of catalog.runbooks ?? []) {
    if (!entry.id || ids.has(entry.id)) {
      findings.push("runbook IDs must be present and unique");
    }
    ids.add(entry.id);
    if (!entry.owner || !entry.detection || !entry.section) {
      findings.push(`${entry.id}: owner, detection and section are required`);
    }
    if (!runbook.includes(`<a id="${entry.section}"></a>`)) {
      findings.push(`${entry.id}: documentation anchor is missing`);
    }
    const sectionStart = runbook.indexOf(`<a id="${entry.section}"></a>`);
    const nextSection = runbook.indexOf("<a id=", sectionStart + 1);
    const section = runbook.slice(sectionStart, nextSection < 0 ? undefined : nextSection);
    for (const heading of [
      "Detection",
      "Owner",
      "Diagnostics",
      "Safe actions",
      "Escalation",
      "Communications",
      "Rollback or containment",
      "Closure evidence",
    ]) {
      if (!section.includes(`**${heading}:**`)) {
        findings.push(`${entry.id}: ${heading} is missing`);
      }
    }
  }
  const deniedLabels = new Set(policy.labelDenylist ?? []);
  for (const label of policy.labelAllowlist ?? []) {
    if (deniedLabels.has(label)) {
      findings.push(`metric label is both allowed and denied: ${label}`);
    }
  }
  for (const alert of policy.alerts ?? []) {
    if (!alert.owner || !alert.threshold || !alert.deduplicationKey || !alert.runbook) {
      findings.push(`${alert.id}: alert owner, threshold, deduplication key and runbook are required`);
    }
  }
  return {
    alertCount: policy.alerts?.length ?? 0,
    cases: [
      {
        durationMs: Math.round(performance.now() - started),
        id: "OPS-RUNBOOK-CATALOG",
        source: "repository:runbooks-observability-policy",
        status: findings.length === 0 ? "PASSED" : "FAILED",
      },
    ],
    findings: findings.sort(),
    kind: "documentation-verification-v1",
    result: findings.length === 0 ? "PASS" : "FAIL",
    runbookCount: catalog.runbooks?.length ?? 0,
    schemaVersion: 1,
  };
}

export async function tabletop(runbookId, eventPath) {
  const catalog = await readJson(
    repoPath("docs/features/07-hardening-go-live/runbook-catalog.json"),
  );
  const entry = catalog.runbooks.find((candidate) => candidate.id === runbookId);
  if (!entry) {
    throw new Error(`unknown runbook ID: ${runbookId}`);
  }
  const event = await readJson(repoPath(eventPath, "tabletop event"));
  const missing = requiredTabletopFields.filter((field) => {
    const value = event[field];
    return value === undefined || value === null || value === "" || value.length === 0;
  });
  return {
    correlationId: event.correlationId ?? null,
    missing,
    owningRole: entry.owner,
    result: missing.length === 0 ? "PASS" : "FAIL",
    runbookId,
    synthetic: true,
  };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const result =
    args.tabletop && args.event
      ? await tabletop(String(args.tabletop), String(args.event))
      : await verifyOperations();
  if (args.output) {
    await writeJson(repoPath(args.output, "output"), result);
  }
  process.stdout.write(stableJson(result));
  process.exitCode = result.result === "PASS" ? 0 : 1;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    process.stderr.write(`F07 operations verification failed safely: ${safeError(error)}\n`);
    process.exitCode = 1;
  });
}
