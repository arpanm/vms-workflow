#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { pathToFileURL } from "node:url";
import {
  commandExists,
  parseArgs,
  readJson,
  repoPath,
  repoRoot,
  run,
  safeError,
  stableJson,
  writeJson,
} from "./lib.mjs";

const blockingSeverities = new Set(["HIGH", "CRITICAL"]);
const governedSeverities = new Set(["MEDIUM", "HIGH", "CRITICAL"]);

function trivySummary(parsed, kind) {
  const findings = [];
  for (const result of parsed.Results ?? []) {
    for (const vulnerability of result.Vulnerabilities ?? []) {
      if (governedSeverities.has(vulnerability.Severity)) {
        findings.push({
          component: vulnerability.PkgName,
          fixedVersion: vulnerability.FixedVersion || null,
          id: vulnerability.VulnerabilityID,
          installedVersion: vulnerability.InstalledVersion,
          severity: vulnerability.Severity,
          target: result.Target,
        });
      }
    }
    for (const secret of result.Secrets ?? []) {
      if (governedSeverities.has(secret.Severity)) {
        findings.push({
          category: secret.Category,
          id: secret.RuleID,
          severity: secret.Severity,
          target: result.Target,
          title: secret.Title,
        });
      }
    }
    for (const misconfiguration of result.Misconfigurations ?? []) {
      if (governedSeverities.has(misconfiguration.Severity)) {
        findings.push({
          id: misconfiguration.ID,
          resolution: misconfiguration.Resolution || null,
          severity: misconfiguration.Severity,
          target: result.Target,
          title: misconfiguration.Title,
        });
      }
    }
  }
  return {
    findingCount: findings.length,
    findings: findings.sort((left, right) =>
      `${left.severity}:${left.id}:${left.target}`.localeCompare(
        `${right.severity}:${right.id}:${right.target}`,
      ),
    ),
    kind,
    result: findings.some((finding) => blockingSeverities.has(finding.severity))
      ? "FAIL"
      : "PASS",
  };
}

function executeTrivy(scanners, kind) {
  const result = run("trivy", [
    "fs",
    "--quiet",
    "--no-progress",
    "--format",
    "json",
    "--scanners",
    scanners,
    ".",
  ]);
  if (result.error || result.status !== 0) {
    throw new Error(`${kind} scanner failed before a trustworthy report was produced`);
  }
  try {
    return trivySummary(JSON.parse(result.stdout), kind);
  } catch {
    throw new Error(`${kind} scanner returned invalid JSON`);
  }
}

export function parseNpmAuditExecution(result) {
  if (
    result.error ||
    result.signal ||
    ![0, 1].includes(result.status)
  ) {
    throw new Error("npm audit did not complete with a trustworthy exit status");
  }
  let parsed;
  try {
    parsed = JSON.parse(result.stdout);
  } catch {
    throw new Error("npm audit did not return a trustworthy JSON report");
  }
  const auditVulnerabilities = parsed.vulnerabilities;
  const counts = parsed.metadata?.vulnerabilities;
  const countKeys = ["info", "low", "moderate", "high", "critical", "total"];
  if (
    parsed.auditReportVersion !== 2 ||
    parsed.error !== undefined ||
    auditVulnerabilities === null ||
    typeof auditVulnerabilities !== "object" ||
    Array.isArray(auditVulnerabilities) ||
    counts === null ||
    typeof counts !== "object" ||
    Array.isArray(counts) ||
    countKeys.some(
      (key) => !Number.isInteger(counts[key]) || counts[key] < 0,
    ) ||
    counts.total !==
      counts.info + counts.low + counts.moderate + counts.high + counts.critical ||
    (result.status === 0 &&
      (counts.total !== 0 || Object.keys(auditVulnerabilities).length !== 0)) ||
    (result.status === 1 &&
      (counts.total === 0 || Object.keys(auditVulnerabilities).length === 0))
  ) {
    throw new Error("npm audit JSON metadata is absent, contradictory, or untrustworthy");
  }
  const normalized = Object.entries(auditVulnerabilities).map(([name, value]) => {
    const rawSeverity = String(value?.severity ?? "").toUpperCase();
    const severity = rawSeverity === "MODERATE" ? "MEDIUM" : rawSeverity;
    if (
      !["INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL"].includes(severity) ||
      !Array.isArray(value?.via)
    ) {
      throw new Error(`npm audit vulnerability ${name} is malformed`);
    }
    return [name, value, severity];
  });
  for (const [countKey, severity] of [
    ["info", "INFO"],
    ["low", "LOW"],
    ["moderate", "MEDIUM"],
    ["high", "HIGH"],
    ["critical", "CRITICAL"],
  ]) {
    if (
      (counts[countKey] > 0) !==
      normalized.some(([, , findingSeverity]) => findingSeverity === severity)
    ) {
      throw new Error(`npm audit ${countKey} count contradicts vulnerability entries`);
    }
  }
  const vulnerabilities = normalized
    .filter(([, , severity]) => governedSeverities.has(severity))
    .map(([name, value, severity]) => ({
      component: name,
      direct: Boolean(value.isDirect),
      severity,
      via: (value.via ?? [])
        .map((item) => (typeof item === "string" ? item : item.source))
        .filter(Boolean)
        .sort(),
    }))
    .sort((left, right) => left.component.localeCompare(right.component));
  return {
    findingCount: vulnerabilities.length,
    findings: vulnerabilities,
    kind: "npm-production-dependencies",
    result:
      !vulnerabilities.some((finding) =>
        blockingSeverities.has(finding.severity),
      )
        ? "PASS"
        : "FAIL",
  };
}

function executeNpmAudit() {
  return parseNpmAuditExecution(
    run("npm", ["audit", "--omit=dev", "--json"]),
  );
}

function createSbom() {
  const result = run("trivy", [
    "fs",
    "--quiet",
    "--no-progress",
    "--format",
    "cyclonedx",
    ".",
  ]);
  if (result.error || result.status !== 0) {
    throw new Error("SBOM generation failed");
  }
  let parsed;
  try {
    parsed = JSON.parse(result.stdout);
  } catch {
    throw new Error("SBOM generator returned invalid JSON");
  }
  return parsed;
}

function executeSast() {
  const argumentsList = [
    "scan",
    "--config",
    "scripts/f07/sast-rules.yml",
    "--error",
    "--json",
    "backend/src",
    "src",
    "scripts",
  ];
  const semgrepImage = process.env.F07_SEMGREP_IMAGE;
  let result;
  if (semgrepImage) {
    validateImageReference(semgrepImage);
    result = run("docker", [
      "run",
      "--rm",
      "--volume",
      `${repoRoot}:/src:ro`,
      "--workdir",
      "/src",
      semgrepImage,
      "semgrep",
      ...argumentsList,
    ]);
  } else {
    result = run("semgrep", argumentsList);
  }
  let parsed;
  try {
    parsed = JSON.parse(result.stdout);
  } catch {
    throw new Error("Java/JavaScript SAST did not return trustworthy JSON");
  }
  const findings = (parsed.results ?? []).map((finding) => ({
    checkId: finding.check_id,
    endLine: finding.end?.line,
    path: finding.path,
    severity: finding.extra?.severity,
    startLine: finding.start?.line,
  }));
  return {
    findingCount: findings.length,
    findings,
    kind: "java-javascript-sast",
    result: result.status === 0 && findings.length === 0 ? "PASS" : "FAIL",
  };
}

export function applyMediumRiskDispositions(report, inventory, now = new Date()) {
  const entries = new Map((inventory.entries ?? []).map((entry) => [entry.key, entry]));
  const unresolved = [];
  for (const finding of report.findings ?? []) {
    if (finding.severity !== "MEDIUM") continue;
    const key = [
      report.kind,
      finding.id ?? finding.component ?? finding.checkId,
      finding.component ?? "",
      finding.target ?? "",
    ].join(":");
    const disposition = entries.get(key);
    if (
      !disposition?.owner ||
      !disposition?.risk ||
      !disposition?.approvedBy ||
      !disposition?.expiresAt ||
      !Number.isFinite(Date.parse(disposition.expiresAt)) ||
      new Date(disposition.expiresAt) <= now
    ) {
      unresolved.push(key);
    }
  }
  return {
    ...report,
    mediumDispositionFindings: unresolved.sort(),
    result:
      report.result === "PASS" && unresolved.length === 0 ? "PASS" : "FAIL",
  };
}

async function licenseInventory(sbom) {
  const policy = await readJson(repoPath("scripts/f07/license-policy.json"));
  const allowed = new Set(policy.allowed);
  const denied = new Set(policy.denied);
  const components = (sbom.components ?? []).map((component) => {
    const licenses = (component.licenses ?? [])
      .map((entry) => entry.license?.id ?? entry.license?.name ?? entry.expression)
      .filter(Boolean)
      .sort();
    return {
      licenses,
      name: component.name,
      purl: component.purl ?? null,
      version: component.version ?? null,
    };
  });
  const findings = [];
  for (const component of components) {
    if (component.licenses.length === 0 && policy.missingLicenseAction === "FAIL") {
      findings.push(`${component.name}@${component.version ?? "unknown"}: license missing`);
    }
    for (const license of component.licenses) {
      if (denied.has(license)) {
        findings.push(`${component.name}: denied license ${license}`);
      } else if (!allowed.has(license)) {
        findings.push(`${component.name}: unapproved license ${license}`);
      }
    }
  }
  return {
    components,
    findings: findings.sort(),
    kind: "license-inventory",
    result: findings.length === 0 ? "PASS" : "FAIL",
  };
}

function scanTarget(target, targetKind) {
  const vulnerability = run("trivy", [
    "fs",
    "--quiet",
    "--no-progress",
    "--format",
    "json",
    "--scanners",
    "vuln",
    target,
  ]);
  if (vulnerability.status !== 0) {
    throw new Error(`${targetKind} vulnerability scan failed`);
  }
  const sbom = run("trivy", [
    "fs",
    "--quiet",
    "--no-progress",
    "--format",
    "cyclonedx",
    target,
  ]);
  if (sbom.status !== 0) {
    throw new Error(`${targetKind} SBOM generation failed`);
  }
  return {
    sbom: JSON.parse(sbom.stdout),
    vulnerability: trivySummary(JSON.parse(vulnerability.stdout), `${targetKind}-vulnerabilities`),
  };
}

export function validateImageReference(image) {
  if (!/^[^\s@]+@sha256:[0-9a-f]{64}$/.test(image ?? "")) {
    throw new Error(`container image must be pinned by sha256 digest: ${image}`);
  }
  return image;
}

function scanImage(image) {
  validateImageReference(image);
  const result = run("trivy", [
    "image",
    "--quiet",
    "--no-progress",
    "--format",
    "json",
    "--scanners",
    "vuln,secret,misconfig",
    image,
  ]);
  if (result.status !== 0) {
    throw new Error("container image scan failed");
  }
  const sbom = run("trivy", [
    "image",
    "--quiet",
    "--no-progress",
    "--format",
    "cyclonedx",
    image,
  ]);
  if (sbom.status !== 0) {
    throw new Error("container image SBOM generation failed");
  }
  return {
    image,
    sbom: JSON.parse(sbom.stdout),
    vulnerability: trivySummary(JSON.parse(result.stdout), "container-image"),
  };
}

async function configuredImages() {
  const compose = await readFile(repoPath("backend/compose.yaml"), "utf8").catch(() => "");
  const images = [...compose.matchAll(/^\s*image:\s*["']?([^"'\s]+)["']?\s*$/gm)]
    .map((match) => match[1])
    .sort();
  if (images.length === 0) {
    throw new Error("compose configuration has no container image to scan");
  }
  return images.map(validateImageReference);
}

export function supplyChainPlan() {
  return {
    failClosed: true,
    requiredTools: ["trivy", "npm", "mvn", "semgrep"],
    steps: [
      "trivy fs --scanners secret --format json . (sanitized summary only)",
      "trivy fs --scanners vuln --format json .",
      "trivy fs --scanners misconfig --format json .",
      "npm audit --omit=dev --json",
      "semgrep scan with checked-in Java/JavaScript rules",
      "trivy fs --format cyclonedx .",
      "trivy vulnerability scan and CycloneDX SBOM for each required release artifact",
      "trivy image scan and CycloneDX SBOM for compose and explicitly supplied images",
    ],
    thresholds: {
      unresolvedCritical: 0,
      unresolvedHigh: 0,
    },
  };
}

async function main() {
  const started = performance.now();
  const args = parseArgs(process.argv.slice(2));
  if (!args.run) {
    process.stdout.write(stableJson(supplyChainPlan()));
    return;
  }
  const requiredTools = process.env.F07_SEMGREP_IMAGE
    ? ["trivy", "npm", "mvn", "docker"]
    : supplyChainPlan().requiredTools;
  const missingTools = requiredTools.filter((tool) => !commandExists(tool));
  if (missingTools.length > 0) {
    throw new Error(`required security tools unavailable: ${missingTools.join(", ")}`);
  }
  if (!args["report-dir"]) {
    throw new Error("--report-dir below the repository is required with --run");
  }
  const requestedReportDirectory = String(args["report-dir"]).replace(/\/+$/, "");
  const runId = String(
    process.env.F07_RUN_ID ?? `${Date.now()}-${process.pid}-${randomUUID().slice(0, 8)}`,
  );
  if (!/^[A-Za-z0-9._-]+$/.test(runId)) {
    throw new Error("F07_RUN_ID may contain only letters, numbers, dot, underscore and hyphen");
  }
  const reportDirectory =
    requestedReportDirectory === ".f07-evidence/supply-chain"
      ? `.f07-evidence/${runId}/supply-chain`
      : requestedReportDirectory;
  const artifactInputs = String(args.artifact ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
  if (artifactInputs.length === 0) {
    throw new Error("--artifact requires at least one built frontend/backend artifact");
  }
  const mediumInventory = await readJson(
    repoPath("scripts/f07/medium-risk-dispositions.json"),
  );
  if (mediumInventory.schemaVersion !== 1) {
    throw new Error("MEDIUM risk disposition inventory has an invalid schema");
  }
  const secret = applyMediumRiskDispositions(
    executeTrivy("secret", "repository-secrets"),
    mediumInventory,
  );
  const vulnerability = applyMediumRiskDispositions(
    executeTrivy("vuln", "java-node-vulnerabilities"),
    mediumInventory,
  );
  const misconfiguration = applyMediumRiskDispositions(
    executeTrivy("misconfig", "configuration-sast"),
    mediumInventory,
  );
  const npmAudit = applyMediumRiskDispositions(
    executeNpmAudit(),
    mediumInventory,
  );
  const sast = executeSast();
  const sbom = createSbom();
  const licenses = await licenseInventory(sbom);
  const artifacts = artifactInputs.map((artifact) => {
    const scanned = scanTarget(repoPath(artifact, "release artifact"), "release-artifact");
    return {
      artifact,
      ...scanned,
      vulnerability: applyMediumRiskDispositions(
        scanned.vulnerability,
        mediumInventory,
      ),
    };
  });
  const images = [
    ...new Set([
      ...(args.image ? String(args.image).split(",").map((value) => value.trim()) : []),
      ...(await configuredImages()),
    ]),
  ].filter(Boolean).map(validateImageReference).map(scanImage).map((scanned) => ({
    ...scanned,
    vulnerability: applyMediumRiskDispositions(
      scanned.vulnerability,
      mediumInventory,
    ),
  }));
  await writeJson(repoPath(`${reportDirectory}/secret-summary.json`), secret);
  await writeJson(repoPath(`${reportDirectory}/vulnerability-summary.json`), vulnerability);
  await writeJson(repoPath(`${reportDirectory}/misconfiguration-summary.json`), misconfiguration);
  await writeJson(repoPath(`${reportDirectory}/npm-audit-summary.json`), npmAudit);
  await writeJson(repoPath(`${reportDirectory}/sast-summary.json`), sast);
  await writeJson(repoPath(`${reportDirectory}/license-inventory.json`), licenses);
  await writeJson(repoPath(`${reportDirectory}/sbom.cdx.json`), sbom);
  for (let index = 0; index < artifacts.length; index += 1) {
    await writeJson(
      repoPath(`${reportDirectory}/artifact-${index + 1}-sbom.cdx.json`),
      artifacts[index].sbom,
    );
    await writeJson(
      repoPath(`${reportDirectory}/artifact-${index + 1}-vulnerability.json`),
      artifacts[index].vulnerability,
    );
  }
  for (let index = 0; index < images.length; index += 1) {
    await writeJson(
      repoPath(`${reportDirectory}/image-${index + 1}-scan.json`),
      images[index].vulnerability,
    );
    await writeJson(
      repoPath(`${reportDirectory}/image-${index + 1}-sbom.cdx.json`),
      images[index].sbom,
    );
  }
  const summary = {
    artifacts: artifacts.map(({ artifact, vulnerability: artifactVulnerability }) => ({
      artifact,
      result: artifactVulnerability.result,
    })),
    images: images.map(({ image: imageName, vulnerability: imageVulnerability }) => ({
      image: imageName,
      result: imageVulnerability.result,
    })),
    reports: [secret, vulnerability, misconfiguration, npmAudit, sast, licenses],
    result: [
      secret,
      vulnerability,
      misconfiguration,
      npmAudit,
      sast,
      licenses,
      ...artifacts.map((artifact) => artifact.vulnerability),
      ...images.map((imageResult) => imageResult.vulnerability),
    ].every(
      (report) => report.result === "PASS",
    )
      ? "PASS"
      : "FAIL",
    sbom: {
      componentCount: sbom.components?.length ?? 0,
      format: sbom.bomFormat,
      specVersion: sbom.specVersion,
    },
  };
  const caseStatus = summary.result === "PASS" ? "PASSED" : "FAILED";
  summary.cases = [
    "SUPPLY-SCANNERS",
    "SUPPLY-SBOM-LICENSES",
    "SUPPLY-IMAGE-DIGESTS",
    "SUPPLY-SECRETS",
    "SUPPLY-ARTIFACTS",
  ].map((id) => ({
    durationMs: Math.round(performance.now() - started),
    id,
    source: "scanner:supply-chain-summary",
    status: caseStatus,
  }));
  summary.kind = "supply-chain-v1";
  summary.schemaVersion = 1;
  await writeJson(repoPath(`${reportDirectory}/summary.json`), summary);
  process.stdout.write(stableJson(summary));
  process.exitCode = summary.result === "PASS" ? 0 : 1;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    process.stderr.write(`F07 supply-chain gate failed safely: ${safeError(error)}\n`);
    process.exitCode = 1;
  });
}
