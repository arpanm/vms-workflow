#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import {
  existingRepoPath,
  gitMetadata,
  parseArgs,
  readJson,
  repoPath,
  repoRoot,
  safeError,
  sha256Bytes,
  sha256File,
  stableJson,
  writeJson,
} from "./lib.mjs";
import { createProvenance } from "./provenance.mjs";
import {
  allowedIndependentApproverRoles,
  canonicalProvenanceInputs,
  protectedMigrationBaseCommit,
  recordEvidencePolicy,
  requiredCiLanes,
} from "./evidence-policy.mjs";
import { createHmac, timingSafeEqual } from "node:crypto";
import {
  deriveVerifiedRecords,
  parseLaneMachineReport,
} from "./machine-reports.mjs";
import { validateOperationalDocument } from "./operational-report.mjs";
import { commandOutputProvesSuccess } from "./command-evidence.mjs";

const allowedResults = new Set([
  "PASS",
  "FAIL",
  "NOT_RUN",
  "ACTION_REQUIRED",
  "DERIVED_FROM_CI",
]);
const allowedPriorities = new Set(["P0", "P1", "P2", "P3"]);
const mandatoryRequirements = new Set(["RQ-033", "RQ-034", "RQ-035"]);
export { requiredCiLanes };
export const requiredCiLaneIds = Object.keys(requiredCiLanes).sort();

export function expandInventory(inventory) {
  const range = inventory.taskIdRange;
  if (
    range?.prefix !== "F07-T" ||
    !Number.isInteger(range.from) ||
    !Number.isInteger(range.to) ||
    range.from > range.to
  ) {
    throw new Error("required inventory has an invalid taskIdRange");
  }
  const taskIds = Array.from(
    { length: range.to - range.from + 1 },
    (_, index) => `${range.prefix}${String(range.from + index).padStart(3, "0")}`,
  );
  return {
    externalTaskIds: new Set(inventory.externalTaskIds ?? []),
    externalTestIds: new Set(inventory.externalTestIds ?? []),
    taskIds,
    testIds: [...(inventory.testIds ?? [])],
  };
}

async function validateInventoryAgainstCatalog(inventory, state) {
  const expanded = expandInventory(inventory);
  const taskCatalog = await readFile(
    repoPath("docs/features/07-hardening-go-live/TASKS.md"),
    "utf8",
  );
  const testCatalog = await readFile(
    repoPath("docs/features/07-hardening-go-live/TEST_CASES.md"),
    "utf8",
  );
  const catalogTasks = new Set(taskCatalog.match(/F07-T\d{3}/g) ?? []);
  const catalogTests = new Set(
    [...testCatalog.matchAll(/\*\*(F07-[A-Z0-9-]+|T-DR-001|E2E-\d{2})\*\*/g)].map(
      (match) => match[1],
    ),
  );
  compareSets("task", new Set(expanded.taskIds), catalogTasks, state.errors);
  compareSets("test", new Set(expanded.testIds), catalogTests, state.errors);
  if (new Set(expanded.testIds).size !== expanded.testIds.length) {
    state.errors.push("required inventory contains duplicate test IDs");
  }
  const catalogExternalTasks = new Set(
    [...taskCatalog.matchAll(/\*\*(F07-T\d{3}) — (?:EXTERNAL|LOCAL\/EXTERNAL)/g)].map(
      (match) => match[1],
    ),
  );
  const catalogExternalTests = new Set(
    [...catalogTests].filter((id) => id.includes("-EXT-")),
  );
  compareSets(
    "external task classification",
    expanded.externalTaskIds,
    catalogExternalTasks,
    state.errors,
  );
  compareSets(
    "external test classification",
    expanded.externalTestIds,
    catalogExternalTests,
    state.errors,
  );
  for (const id of expanded.externalTaskIds) {
    if (!catalogTasks.has(id)) {
      state.errors.push(`external task is not in the F07 catalog: ${id}`);
    }
  }
  for (const id of expanded.externalTestIds) {
    if (!catalogTests.has(id)) {
      state.errors.push(`external test is not in the F07 catalog: ${id}`);
    }
  }
  return expanded;
}

function compareSets(kind, inventory, catalog, errors) {
  for (const id of catalog) {
    if (!inventory.has(id)) {
      errors.push(`required inventory is missing ${kind} ID: ${id}`);
    }
  }
  for (const id of inventory) {
    if (!catalog.has(id)) {
      errors.push(`required inventory has unknown ${kind} ID: ${id}`);
    }
  }
}

function resolveRecords(manifest, inventory, state) {
  const records = [];
  const templates = manifest.recordTemplates ?? {};
  const overrides = manifest.overrides ?? {};
  const groups = [
    ["task", "local", manifest.taskRecords?.local ?? [], inventory.externalTaskIds],
    ["task", "external", manifest.taskRecords?.external ?? [], inventory.externalTaskIds],
    ["test", "local", manifest.testRecords?.local ?? [], inventory.externalTestIds],
    ["test", "external", manifest.testRecords?.external ?? [], inventory.externalTestIds],
  ];
  const seen = { task: new Set(), test: new Set() };
  for (const [kind, classification, ids, externalIds] of groups) {
    const template = templates[classification];
    if (!template) {
      state.errors.push(`record template is missing: ${classification}`);
      continue;
    }
    for (const id of ids) {
      if (seen[kind].has(id)) {
        state.errors.push(`duplicate ${kind} release record: ${id}`);
        continue;
      }
      seen[kind].add(id);
      const shouldBeExternal = externalIds.has(id);
      if (shouldBeExternal !== (classification === "external")) {
        state.errors.push(`${id}: release classification disagrees with required inventory`);
      }
      const override = overrides[id] ?? {};
      if (classification === "local" && Object.keys(override).length > 0) {
        state.errors.push(`${id}: local release record overrides are forbidden`);
      }
      if (
        classification === "external" &&
        Object.keys(override).some((field) =>
          ["command", "environment", "kind", "mandatory", "priority", "requirementIds"].includes(
            field,
          ),
        )
      ) {
        state.errors.push(`${id}: external override changes canonical record policy`);
      }
      if (classification === "local" && !recordEvidencePolicy[id]) {
        state.errors.push(`${id}: canonical record evidence policy is missing`);
      }
      records.push({
        ...template,
        ...override,
        ...(classification === "local"
          ? {
              command: `derived:${recordEvidencePolicy[id]?.laneId ?? "missing"}`,
              environment: "github-actions",
              evidence: null,
              result: "DERIVED_FROM_CI",
            }
          : {}),
        external: classification === "external",
        id,
        kind,
      });
    }
  }
  compareSets("task release record", seen.task, new Set(inventory.taskIds), state.errors);
  compareSets("test release record", seen.test, new Set(inventory.testIds), state.errors);
  for (const id of Object.keys(overrides)) {
    if (!seen.task.has(id) && !seen.test.has(id)) {
      state.errors.push(`override targets an unknown release record: ${id}`);
    }
  }
  return records;
}

async function readCanonicalEvidenceReference(reference, prefix, state, parseJson = false) {
  if (!reference?.path || !reference?.sha256) {
    state.errors.push(`${prefix} needs an evidence path and sha256`);
    return false;
  }
  try {
    const canonicalPath = await existingRepoPath(reference.path, `${prefix} evidence`);
    const actual = await sha256File(canonicalPath);
    if (actual !== reference.sha256) {
      state.blockers.push(`${prefix} evidence checksum drift`);
      return false;
    }
    if (!parseJson) {
      return { canonicalPath };
    }
    return {
      canonicalPath,
      document: JSON.parse(await readFile(canonicalPath, "utf8")),
    };
  } catch (error) {
    state.blockers.push(`${prefix} evidence unavailable (${safeError(error)})`);
    return null;
  }
}

async function validateProvenanceReference(reference, releaseCommit, prefix, state) {
  const resolved = await readCanonicalEvidenceReference(reference, `${prefix} provenance`, state, true);
  if (!resolved) {
    return false;
  }
  const provenance = resolved.document;
  if (
    provenance.schemaVersion !== 1 ||
    provenance.predicateType !== "https://slsa.dev/provenance/v1" ||
    provenance.commit !== releaseCommit ||
    provenance.worktreeDirty !== false ||
    !Array.isArray(provenance.expectedInputs) ||
    stableJson([...provenance.expectedInputs].sort()) !==
      stableJson(canonicalProvenanceInputs) ||
    !Array.isArray(provenance.artifacts) ||
    provenance.artifacts.length === 0
  ) {
    state.blockers.push(`${prefix}: provenance is incomplete, dirty, or bound to another commit`);
    return false;
  }
  const cacheKey = reference.sha256;
  let cached = state.provenanceCache.get(cacheKey);
  if (!cached) {
    try {
      cached = {
        decisionTime: await createProvenance(provenance.expectedInputs, {
          expectedCommit: releaseCommit,
          requireClean: true,
        }),
      };
    } catch (error) {
      cached = { error: safeError(error) };
    }
    state.provenanceCache.set(cacheKey, cached);
  }
  if (cached.error) {
    state.blockers.push(
      `${prefix}: decision-time provenance verification failed (${cached.error})`,
    );
    return false;
  }
  const { decisionTime } = cached;
  if (
    stableJson(decisionTime.expectedInputs) !== stableJson(provenance.expectedInputs) ||
    stableJson(decisionTime.artifacts) !== stableJson(provenance.artifacts) ||
    stableJson(decisionTime.composeImages) !== stableJson(provenance.composeImages)
  ) {
    state.blockers.push(`${prefix}: provenance inputs or artifacts drifted before decision`);
    return false;
  }
  return true;
}

async function validateStructuredRecordEvidence(record, releaseCommit, state) {
  const resolved = await readCanonicalEvidenceReference(record.evidence, record.id, state, true);
  if (!resolved) {
    return false;
  }
  const document = resolved.document;
  const expected = {
    command: record.command,
    durationMs: record.durationMs,
    environment: record.environment,
    id: record.id,
    kind: record.kind,
    result: record.result,
  };
  const actual = {
    command: document.record?.command,
    durationMs: document.record?.durationMs,
    environment: document.record?.environment,
    id: document.record?.id,
    kind: document.record?.kind,
    result: document.record?.result,
  };
  if (
    document.schemaVersion !== 1 ||
    document.releaseCommit !== releaseCommit ||
    stableJson(actual) !== stableJson(expected) ||
    !Number.isFinite(document.record?.durationMs) ||
    document.record.durationMs < 0
  ) {
    state.blockers.push(
      `${record.id}: structured evidence metadata does not exactly match the release record`,
    );
    return false;
  }
  if (!record.external) {
    if (
      document.runner?.name !== "scripts/f07/command-evidence.mjs" ||
      document.runner?.schemaVersion !== 1 ||
      !commandOutputProvesSuccess(document.commandOutput) ||
      stableJson(document.commandOutput) !== stableJson(record.commandOutput)
    ) {
      state.blockers.push(`${record.id}: structured runner output is missing or inconsistent`);
      return false;
    }
    if (
      record.suiteId !== undefined &&
      (document.suiteId !== record.suiteId ||
        stableJson(document.verifiedResultIds ?? []) !==
          stableJson(record.verifiedResultIds ?? []) ||
        stableJson(document.observedCases ?? []) !==
          stableJson(record.observedCases ?? []) ||
        stableJson(document.machineReportRaw ?? null) !==
          stableJson(record.machineReportRaw ?? null) ||
        document.machineReportSha256 !== record.machineReportSha256 ||
        document.machineReportSha256 !==
          sha256Bytes(stableJson(document.machineReportRaw ?? null)) ||
        (typeof document.machineReportRaw === "string" &&
          document.commandOutput.stdoutSha256 !==
            sha256Bytes(document.machineReportRaw)) ||
        stableJson(document.structuredResult ?? null) !==
          stableJson(record.structuredResult ?? null))
    ) {
      state.blockers.push(`${record.id}: bound suite/result document is inconsistent`);
      return false;
    }
  }
  return validateProvenanceReference(
    document.provenance,
    releaseCommit,
    record.id,
    state,
  );
}

function compareMigrationVersions(left, right) {
  const leftParts = left.split(".").map(Number);
  const rightParts = right.split(".").map(Number);
  for (let index = 0; index < Math.max(leftParts.length, rightParts.length); index += 1) {
    const difference = (leftParts[index] ?? 0) - (rightParts[index] ?? 0);
    if (difference !== 0) return difference;
  }
  return 0;
}

function validMigrationHistory(history) {
  if (!Array.isArray(history) || history.length === 0) return false;
  const versions = new Set();
  let previous = null;
  for (const entry of history) {
    if (
      entry === null ||
      typeof entry !== "object" ||
      Array.isArray(entry) ||
      stableJson(Object.keys(entry).sort()) !==
        stableJson(["checksum", "success", "version"]) ||
      !/^[1-9]\d*(?:\.\d+)*$/.test(entry.version ?? "") ||
      !Number.isInteger(entry.checksum) ||
      entry.success !== true ||
      versions.has(entry.version) ||
      (previous !== null && compareMigrationVersions(previous, entry.version) >= 0)
    ) {
      return false;
    }
    versions.add(entry.version);
    previous = entry.version;
  }
  return true;
}

export function validateMigrationLiveResult(result, releaseCommit, state) {
  if (
    result?.schemaVersion !== 1 ||
    result?.kind !== "migration-live-v1" ||
    result?.releaseCommit !== releaseCommit ||
    result?.protectedBaseCommit !== protectedMigrationBaseCommit ||
    result.protectedBaseCommit === releaseCommit ||
    !String(result?.database?.database ?? "").endsWith("_f07_preflight") ||
    !validMigrationHistory(result.sourceHistory) ||
    !validMigrationHistory(result.liveHistory) ||
    stableJson(result.sourceHistory ?? []) !== stableJson(result.liveHistory ?? []) ||
    result.sourceHistorySha256 !== sha256Bytes(stableJson(result.sourceHistory ?? [])) ||
    result.liveHistorySha256 !== sha256Bytes(stableJson(result.liveHistory ?? [])) ||
    result.result !== "PASS"
  ) {
    state.blockers.push(
      "F07-CI-MIGRATION-LIVE: source/history checksum or protected live report is invalid",
    );
    return false;
  }
  return true;
}

function validateOperationalStructuredResult(id, result, state) {
  const kind = {
    "F07-CI-DR-REHEARSAL": "dr",
    "F07-CI-LOAD-SYSTEM": "load",
    "F07-CI-ROLLOUT": "rollout",
    "F07-CI-SOAK-24H": "soak-24h",
  }[id];
  if (!kind) return true;
  try {
    const verified = validateOperationalDocument(kind, result?.sourceReport);
    if (stableJson(verified) !== stableJson(result)) {
      throw new Error("derived operational cases differ from the signed source report");
    }
    return true;
  } catch (error) {
    state.blockers.push(`${id}: signed operational report is invalid (${safeError(error)})`);
    return false;
  }
}

export function validateSupplyStructuredResult(result, state) {
  const expectedCaseIds = [
    "SUPPLY-SCANNERS",
    "SUPPLY-SBOM-LICENSES",
    "SUPPLY-IMAGE-DIGESTS",
    "SUPPLY-SECRETS",
    "SUPPLY-ARTIFACTS",
  ];
  const expectedReportKinds = [
    "configuration-sast",
    "java-javascript-sast",
    "java-node-vulnerabilities",
    "license-inventory",
    "npm-production-dependencies",
    "repository-secrets",
  ];
  const expectedArtifacts = [
    "backend/target/workflow-backend-0.1.0-SNAPSHOT.jar",
    "dist",
  ];
  const reports = result?.reports;
  const artifacts = result?.artifacts;
  const images = result?.images;
  if (
    result?.schemaVersion !== 1 ||
    result?.kind !== "supply-chain-v1" ||
    result?.result !== "PASS" ||
    !Array.isArray(reports) ||
    stableJson(reports.map((report) => report?.kind).sort()) !==
      stableJson(expectedReportKinds) ||
    reports.some((report) => report.result !== "PASS") ||
    !Array.isArray(artifacts) ||
    stableJson(artifacts.map((artifact) => artifact?.artifact).sort()) !==
      stableJson(expectedArtifacts) ||
    artifacts.some((artifact) => artifact.result !== "PASS") ||
    !Array.isArray(images) ||
    images.length === 0 ||
    images.some(
      (image) =>
        image.result !== "PASS" ||
        !/^[^\s@]+@sha256:[0-9a-f]{64}$/.test(image.image ?? ""),
    ) ||
    result?.sbom?.format !== "CycloneDX" ||
    !["1.4", "1.5", "1.6"].includes(result?.sbom?.specVersion) ||
    !Number.isInteger(result?.sbom?.componentCount) ||
    result.sbom.componentCount < 1 ||
    !Array.isArray(result?.cases) ||
    stableJson((result?.cases ?? []).map((entry) => entry.id).sort()) !==
      stableJson(expectedCaseIds.sort()) ||
    result.cases.some(
      (entry) =>
        entry.status !== "PASSED" ||
        entry.source !== "scanner:supply-chain-summary",
    )
  ) {
    state.blockers.push("F07-CI-SUPPLY: structured scanner/SBOM result is invalid");
    return false;
  }
  return true;
}

async function validateCiEvidenceBundle(reference, releaseCommit, state) {
  const resolved = await readCanonicalEvidenceReference(
    reference,
    "mandatory CI evidence bundle",
    state,
    true,
  );
  if (!resolved) {
    return;
  }
  const bundle = resolved.document;
  if (bundle.schemaVersion !== 1 || bundle.releaseCommit !== releaseCommit) {
    state.blockers.push("mandatory CI evidence bundle is bound to another commit");
    return;
  }
  if (!Array.isArray(bundle.entries)) {
    state.errors.push("mandatory CI evidence bundle entries must be an array");
    return;
  }
  const entries = new Map((bundle.entries ?? []).map((entry) => [entry.id, entry]));
  if (entries.size !== bundle.entries.length) {
    state.errors.push("mandatory CI evidence bundle contains duplicate lane IDs");
  }
  for (const id of requiredCiLaneIds) {
    const entry = entries.get(id);
    if (!entry) {
      const collection = requiredCiLanes[id].actionRequiredWhenAbsent
        ? state.actionRequired
        : state.blockers;
      collection.push(`mandatory CI evidence bundle is missing ${id}`);
      continue;
    }
    const lane = requiredCiLanes[id];
    if (
      entry.kind !== "test" ||
      entry.environment !== "github-actions" ||
      entry.result !== "PASS" ||
      (lane.command !== undefined && entry.command !== lane.command) ||
      (lane.commandPattern !== undefined && !lane.commandPattern.test(entry.command ?? ""))
    ) {
      state.blockers.push(`${id}: CI lane metadata or command is not allowlisted`);
      continue;
    }
    if (entry.suiteId !== lane.suiteId) {
      state.blockers.push(`${id}: canonical suite identifier is incorrect`);
      continue;
    }
    let verifiedResultIds;
    try {
      const reparsed = parseLaneMachineReport(
        lane,
        entry.machineReportRaw ?? null,
      );
      verifiedResultIds = deriveVerifiedRecords(lane, reparsed.cases);
      if (
        stableJson(entry.observedCases ?? []) !== stableJson(reparsed.cases) ||
        stableJson(entry.verifiedResultIds ?? []) !==
          stableJson(verifiedResultIds) ||
        stableJson(entry.structuredResult ?? null) !==
          stableJson(reparsed.structuredResult ?? null)
      ) {
        throw new Error("declared cases/results differ from parsed machine report");
      }
    } catch (error) {
      state.blockers.push(`${id}: machine report is invalid (${safeError(error)})`);
      continue;
    }
    const validEvidence = await validateStructuredRecordEvidence(
      entry,
      releaseCommit,
      state,
    );
    if (
      validEvidence &&
      lane.structuredKind === "migration-live-v1" &&
      !validateMigrationLiveResult(entry.structuredResult, releaseCommit, state)
    ) {
      continue;
    }
    if (
      validEvidence &&
      !validateOperationalStructuredResult(id, entry.structuredResult, state)
    ) {
      continue;
    }
    if (
      validEvidence &&
      id === "F07-CI-SUPPLY" &&
      !validateSupplyStructuredResult(entry.structuredResult, state)
    ) {
      continue;
    }
    if (validEvidence) {
      for (const resultId of verifiedResultIds) {
        state.verifiedResultIds.add(resultId);
      }
    }
  }
  for (const id of entries.keys()) {
    if (!requiredCiLaneIds.includes(id)) {
      state.errors.push(`mandatory CI evidence bundle has unknown lane ${id}`);
    }
  }
}

function verifyHmacDocument(document, key, label) {
  if (typeof key !== "string" || key.length < 32) {
    throw new Error(`${label} verification key is unavailable`);
  }
  const { signature, ...unsigned } = document;
  if (
    signature?.algorithm !== "HMAC-SHA256" ||
    !signature.keyId ||
    !/^[0-9a-f]{64}$/.test(signature.value ?? "")
  ) {
    throw new Error(`${label} signature metadata is invalid`);
  }
  const expected = Buffer.from(
    createHmac("sha256", key).update(stableJson(unsigned)).digest("hex"),
    "hex",
  );
  const actual = Buffer.from(signature.value, "hex");
  if (expected.length !== actual.length || !timingSafeEqual(expected, actual)) {
    throw new Error(`${label} signature verification failed`);
  }
}

async function validateExternalApproval(record, releaseCommit, state) {
  const approval = record.approvalEvidence;
  const prefix = `${record.id} approval`;
  const resolved = await readCanonicalEvidenceReference(
    approval?.evidence,
    prefix,
    state,
    true,
  );
  if (!resolved) return false;
  const document = resolved.document;
  try {
    verifyHmacDocument(
      document,
      process.env.F07_APPROVAL_VERIFICATION_KEY,
      prefix,
    );
  } catch (error) {
    state.blockers.push(`${prefix}: ${safeError(error)}`);
    return false;
  }
  if (
    document.schemaVersion !== 1 ||
    document.kind !== "release-approval-v1" ||
    document.id !== record.id ||
    document.releaseCommit !== releaseCommit ||
    document.environment !== "production" ||
    document.decision !== "APPROVED" ||
    !document.approver ||
    document.approver === record.owner ||
    document.approverRole === record.owner ||
    !allowedIndependentApproverRoles.has(document.approverRole) ||
    document.approver !== approval.approver ||
    document.approverRole !== approval.approverRole ||
    document.approvedAt !== approval.approvedAt ||
    !Number.isFinite(Date.parse(document.approvedAt)) ||
    new Date(document.approvedAt) > state.now ||
    !Number.isFinite(Date.parse(document.expiresAt)) ||
    new Date(document.expiresAt) <= state.now
  ) {
    state.blockers.push(`${prefix}: signed dated independent approval is invalid`);
    return false;
  }
  return true;
}

async function validateException(record, now, state) {
  const exception = record.exception;
  if (!exception) {
    return false;
  }
  const prefix = `${record.id}: exception`;
  if (!exception.owner || !exception.reason || !exception.expiresAt) {
    state.errors.push(`${prefix} needs owner, reason, and expiresAt`);
    return false;
  }
  if (Number.isNaN(Date.parse(exception.expiresAt)) || new Date(exception.expiresAt) <= now) {
    state.blockers.push(`${prefix} is expired or has an invalid expiry`);
    return false;
  }
  if (record.external) {
    state.blockers.push(`${prefix} cannot waive an external acceptance requirement`);
    return false;
  }
  if (["P0", "P1"].includes(record.priority)) {
    const approval = exception.independentApproval;
    if (
      !approval?.approver ||
      !approval?.approverRole ||
      approval.approver === exception.owner ||
      !approval.approvedAt
    ) {
      state.blockers.push(`${prefix} lacks independent approval identity and timestamp`);
      return false;
    }
    return Boolean(
      await readCanonicalEvidenceReference(
        approval.evidence,
        `${prefix} approval`,
        state,
      ),
    );
  }
  return true;
}

async function validateRecord(record, releaseCommit, now, state) {
  const prefix = `${record.id}:`;
  if (!allowedPriorities.has(record.priority)) {
    state.errors.push(`${prefix} priority must be P0, P1, P2, or P3`);
  }
  if (!allowedResults.has(record.result)) {
    state.errors.push(`${prefix} invalid result`);
  }
  for (const field of [
    "command",
    "durationMs",
    "environment",
    "mandatory",
    "owner",
    "requirementIds",
  ]) {
    if (record[field] === undefined || record[field] === null || record[field] === "") {
      state.errors.push(`${prefix} ${field} is required`);
    }
  }
  for (const requirement of mandatoryRequirements) {
    if (!record.requirementIds?.includes(requirement)) {
      state.errors.push(`${prefix} mandatory traceability is missing ${requirement}`);
    }
  }
  if (!record.external) {
    const policy = recordEvidencePolicy[record.id];
    if (!policy) {
      state.errors.push(`${prefix} canonical CI result policy is absent`);
    } else if (!state.verifiedResultIds.has(policy.resultId)) {
      const collection =
        requiredCiLanes[policy.laneId]?.actionRequiredWhenAbsent ||
        policy.requiredCases.length === 0
          ? state.actionRequired
          : state.blockers;
      collection.push(
        `${record.id}: canonical result ${policy.resultId} from ${policy.laneId} is absent`,
      );
    }
    return;
  }
  const validException = await validateException(record, now, state);
  if (record.result === "PASS") {
    await validateStructuredRecordEvidence(record, releaseCommit, state);
    if (record.external) {
      const approval = record.approvalEvidence;
      if (!approval?.approver || !approval?.approverRole || !approval?.approvedAt) {
        state.blockers.push(`${prefix} external PASS lacks named approval evidence`);
      } else {
        await validateExternalApproval(record, releaseCommit, state);
      }
    }
    return;
  }
  if (record.external) {
    state.actionRequired.push(
      `${record.id}: ${record.unresolvedIssue || "external acceptance evidence is absent"}`,
    );
    return;
  }
  if (record.mandatory && !validException) {
    state.blockers.push(`${record.id}: mandatory ${record.priority} record is ${record.result}`);
  }
  if (["P0", "P1"].includes(record.priority) && record.result === "FAIL") {
    state.blockers.push(`${record.id}: failed ${record.priority} record`);
  }
}

async function validateRegistry(registry, releaseCommit, state) {
  const allowedStates = new Set([
    "NOT_CONFIGURED",
    "CONFIGURED_UNVERIFIED",
    "VERIFIED",
    "EXPIRED_ACTION_REQUIRED",
  ]);
  if (!Array.isArray(registry.entries)) {
    state.errors.push("configuration registry entries must be an array");
    return;
  }
  const seen = new Set();
  for (const entry of registry.entries) {
    const prefix = `configuration ${entry.id ?? "<missing>"}:`;
    if (!entry.id || seen.has(entry.id)) {
      state.errors.push(`${prefix} missing or duplicate ID`);
    }
    seen.add(entry.id);
    if (!allowedStates.has(entry.state)) {
      state.errors.push(`${prefix} invalid state`);
    }
    for (const field of ["owningRole", "affectedWorkflow", "mandatory", "external"]) {
      if (entry[field] === undefined || entry[field] === "") {
        state.errors.push(`${prefix} ${field} is required`);
      }
    }
    if (entry.effectiveUntil && new Date(entry.effectiveUntil) <= state.now) {
      state.actionRequired.push(`${entry.id}: configuration evidence has expired`);
    }
    if (entry.mandatory && entry.state !== "VERIFIED") {
      const detail = `${entry.id}: mandatory configuration is ${entry.state}`;
      (entry.external ? state.actionRequired : state.blockers).push(detail);
    }
    if (entry.state === "VERIFIED") {
      const resolved = await readCanonicalEvidenceReference(
        entry.evidenceReference,
        `configuration ${entry.id}`,
        state,
        true,
      );
      if (!resolved) continue;
      const document = resolved.document;
      try {
        verifyHmacDocument(
          document,
          process.env.F07_CONFIGURATION_VERIFICATION_KEY,
          `configuration ${entry.id}`,
        );
      } catch (error) {
        state.blockers.push(`configuration ${entry.id}: ${safeError(error)}`);
        continue;
      }
      if (
        document.schemaVersion !== 1 ||
        document.kind !== "configuration-verification-v1" ||
        document.id !== entry.id ||
        document.releaseCommit !== releaseCommit ||
        document.environment !== "production" ||
        document.state !== "VERIFIED" ||
        !document.approver ||
        !allowedIndependentApproverRoles.has(document.approverRole) ||
        document.approver === entry.owningRole ||
        document.approverRole === entry.owningRole ||
        !Number.isFinite(Date.parse(document.effectiveFrom)) ||
        new Date(document.effectiveFrom) > state.now ||
        !Number.isFinite(Date.parse(document.expiresAt)) ||
        new Date(document.expiresAt) <= state.now ||
        entry.effectiveFrom !== document.effectiveFrom ||
        entry.effectiveUntil !== document.expiresAt
      ) {
        state.blockers.push(
          `configuration ${entry.id}: signed commit/environment-bound verification is invalid`,
        );
      }
    }
  }
}

export async function evaluateRelease(manifestPath, options = {}) {
  const state = {
    actionRequired: [],
    blockers: [],
    errors: [],
    now: options.now ?? new Date(),
    provenanceCache: new Map(),
    verifiedResultIds: new Set(),
  };
  let manifest;
  try {
    manifest = await readJson(manifestPath);
  } catch (error) {
    return {
      actionRequired: [],
      blockers: [],
      errors: [`manifest cannot be read: ${safeError(error)}`],
      verdict: "INVALID",
    };
  }
  for (const key of [
    "schemaVersion",
    "release",
    "mandatoryRequirements",
    "requiredInventory",
    "configurationRegistry",
    "recordTemplates",
    "taskRecords",
    "testRecords",
  ]) {
    if (!(key in manifest)) {
      state.errors.push(`missing top-level field: ${key}`);
    }
  }
  if (!manifest.release?.version || !/^[0-9a-f]{40}$/.test(manifest.release?.commit ?? "")) {
    state.errors.push("release must contain a version and exact 40-character commit");
  }
  const currentGit = gitMetadata();
  if (manifest.release?.commit !== currentGit.commit) {
    state.blockers.push("release manifest commit does not match the current repository commit");
  }
  if (currentGit.worktreeDirty) {
    state.blockers.push("release decision requires a clean tracked and untracked worktree");
  }
  if (
    !String(manifest.release?.architecture ?? "").includes("Java 25") ||
    !String(manifest.release?.architecture ?? "").includes("PostgreSQL")
  ) {
    state.errors.push("release architecture must preserve Java 25/PostgreSQL precedence");
  }
  for (const requirement of mandatoryRequirements) {
    if (!manifest.mandatoryRequirements?.includes(requirement)) {
      state.errors.push(`mandatory release requirement is absent: ${requirement}`);
    }
  }

  const ciEvidenceBundle = options.ciEvidenceBundle ?? manifest.ciEvidenceBundle;
  if (ciEvidenceBundle) {
    await validateCiEvidenceBundle(ciEvidenceBundle, manifest.release?.commit, state);
  } else {
    state.blockers.push("mandatory commit-bound CI evidence bundle is absent");
  }

  let inventory;
  try {
    const inventoryDocument =
      options.inventory ?? (await readJson(repoPath(manifest.requiredInventory)));
    inventory = await validateInventoryAgainstCatalog(inventoryDocument, state);
  } catch (error) {
    state.errors.push(`required inventory cannot be validated: ${safeError(error)}`);
  }
  if (inventory) {
    const records = resolveRecords(manifest, inventory, state);
    for (const record of records) {
      await validateRecord(record, manifest.release?.commit, state.now, state);
    }
  }
  try {
    const registry =
      options.registry ?? (await readJson(repoPath(manifest.configurationRegistry)));
    await validateRegistry(registry, manifest.release?.commit, state);
  } catch (error) {
    state.errors.push(`configuration registry cannot be read: ${safeError(error)}`);
  }

  const unique = (items) => [...new Set(items)].sort();
  const result = {
    actionRequired: unique(state.actionRequired),
    blockers: unique(state.blockers),
    errors: unique(state.errors),
    manifest: resolve(manifestPath).replace(`${repoRoot}/`, ""),
    release: manifest.release,
  };
  result.verdict =
    result.errors.length > 0
      ? "INVALID"
      : result.blockers.length > 0 || result.actionRequired.length > 0
        ? "NO_GO_ACTION_REQUIRED"
        : "GO";
  return result;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const manifestPath = resolve(
    repoRoot,
    args.manifest ?? "docs/features/07-hardening-go-live/release-evidence.json",
  );
  const result = await evaluateRelease(manifestPath);
  if (args["ci-bundle"]) {
    const bundlePath = await existingRepoPath(args["ci-bundle"], "CI evidence bundle");
    const resultWithBundle = await evaluateRelease(manifestPath, {
      ciEvidenceBundle: {
        path: resolve(bundlePath).replace(`${repoRoot}/`, ""),
        sha256: await sha256File(bundlePath),
      },
    });
    Object.assign(result, resultWithBundle);
  }
  if (args.report) {
    await writeJson(repoPath(args.report, "report"), result);
  }
  process.stdout.write(stableJson(result));
  process.exitCode = args["schema-only"]
    ? result.errors.length === 0
      ? 0
      : 1
    : result.verdict === "GO"
      ? 0
      : 1;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    process.stderr.write(`F07 release gate failed safely: ${safeError(error)}\n`);
    process.exitCode = 1;
  });
}
