import { readdir, readFile, stat } from "node:fs/promises";
import { resolve } from "node:path";

function decodeXml(value) {
  return String(value)
    .replaceAll("&quot;", '"')
    .replaceAll("&apos;", "'")
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">")
    .replaceAll("&amp;", "&");
}

function xmlAttribute(source, name) {
  const match = source.match(new RegExp(`\\b${name}=(["'])(.*?)\\1`));
  return match ? decodeXml(match[2]) : null;
}

export async function collectJUnitReports(directories, sinceMs = 0) {
  const documents = [];
  for (const directory of directories) {
    let names = [];
    try {
      names = await readdir(directory);
    } catch {
      continue;
    }
    for (const name of names.filter((entry) => /^TEST-.*\.xml$/.test(entry)).sort()) {
      const path = resolve(directory, name);
      if ((await stat(path)).mtimeMs + 1_000 < sinceMs) continue;
      documents.push({
        content: await readFile(path, "utf8"),
        path: `${directory.split("/").at(-1)}/${name}`,
      });
    }
  }
  if (documents.length === 0) {
    throw new Error("no current-run Surefire/Failsafe JUnit reports were observed");
  }
  return documents;
}

export function parseJUnitDocuments(documents) {
  const cases = [];
  for (const document of documents) {
      const xml = document.content;
      const name = document.path;
      for (const match of xml.matchAll(/<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g)) {
        const attributes = match[1];
        const body = match[2] ?? "";
        const className = xmlAttribute(attributes, "classname");
        const testName = xmlAttribute(attributes, "name");
        const seconds = Number(xmlAttribute(attributes, "time"));
        if (!className || !testName || !Number.isFinite(seconds) || seconds < 0) {
          throw new Error(`invalid JUnit testcase metadata in ${name}`);
        }
        const status = /<(?:failure|error)\b/.test(body)
          ? "FAILED"
          : /<skipped\b/.test(body)
            ? "SKIPPED"
            : "PASSED";
        cases.push({
          durationMs: Math.round(seconds * 1000),
          id: `${className}#${testName}`,
          source: `junit:${name}`,
          status,
        });
      }
  }
  if (cases.length === 0) {
    throw new Error("no Surefire/Failsafe JUnit testcases were observed");
  }
  return cases;
}

export async function parseJUnitDirectories(directories, sinceMs = 0) {
  return parseJUnitDocuments(await collectJUnitReports(directories, sinceMs));
}

function walkPlaywrightSuites(suites, cases) {
  for (const suite of suites ?? []) {
    for (const spec of suite.specs ?? []) {
      const match = String(spec.title ?? "").match(/\[([A-Z0-9-]+)\]/);
      if (!match) continue;
      for (const test of spec.tests ?? []) {
        const results = test.results ?? [];
        const final = results.at(-1);
        const projectName = test.projectName ?? "unknown-project";
        cases.push({
          durationMs: results.reduce(
            (total, result) =>
              total + (Number.isFinite(result.duration) ? result.duration : 0),
            0,
          ),
          id: `${match[1]}@${projectName}`,
          source: `playwright:${spec.file ?? suite.file ?? "unknown"}`,
          status:
            test.expectedStatus === "passed" && final?.status === "passed"
              ? "PASSED"
              : final?.status === "skipped"
                ? "SKIPPED"
                : "FAILED",
        });
      }
    }
    walkPlaywrightSuites(suite.suites, cases);
  }
}

export function parsePlaywrightJson(stdout) {
  let report;
  try {
    report = JSON.parse(stdout);
  } catch {
    throw new Error("Playwright did not emit its required JSON report");
  }
  const cases = [];
  walkPlaywrightSuites(report.suites, cases);
  if (cases.length === 0) {
    throw new Error("Playwright JSON contains no stable bracketed case IDs");
  }
  return cases;
}

export function parseVitestJson(stdout) {
  let report;
  try {
    report = JSON.parse(stdout);
  } catch {
    throw new Error("Vitest did not emit its required JSON report");
  }
  const cases = [];
  for (const file of report.testResults ?? []) {
    for (const [assertionIndex, assertion] of (file.assertionResults ?? []).entries()) {
      const match = String(assertion.title ?? assertion.fullName ?? "").match(
        /\[([A-Z0-9-]+)\]/,
      );
      if (!match) continue;
      cases.push({
        durationMs: Number.isFinite(assertion.duration)
          ? assertion.duration
          : 0,
        id: `${match[1]}@${String(file.name ?? "unknown").split("/").at(-1)}:${assertionIndex}`,
        source: `vitest:${file.name ?? "unknown"}`,
        status:
          assertion.status === "passed"
            ? "PASSED"
            : assertion.status === "pending" ||
                assertion.status === "skipped" ||
                assertion.status === "todo"
              ? "SKIPPED"
              : "FAILED",
      });
    }
  }
  return cases;
}

export function parseStructuredCases(stdout, expectedKind) {
  let report;
  try {
    report = JSON.parse(stdout);
  } catch {
    throw new Error("command did not emit its required structured JSON report");
  }
  if (
    report.schemaVersion !== 1 ||
    (expectedKind && report.kind !== expectedKind) ||
    report.result !== "PASS" ||
    !Array.isArray(report.cases)
  ) {
    throw new Error("structured command report metadata is invalid");
  }
  return {
    cases: report.cases,
    report,
  };
}

export function validateObservedCases(cases) {
  const seen = new Set();
  return cases.map((entry) => {
    if (
      !/^[A-Za-z0-9_.@#$:-]+$/.test(entry?.id ?? "") ||
      !["PASSED", "FAILED", "SKIPPED"].includes(entry?.status) ||
      !Number.isFinite(entry?.durationMs) ||
      entry.durationMs < 0 ||
      !entry.source ||
      seen.has(entry.id)
    ) {
      throw new Error(`machine report has invalid or duplicate testcase ${entry?.id ?? "<missing>"}`);
    }
    seen.add(entry.id);
    return {
      durationMs: entry.durationMs,
      id: entry.id,
      source: entry.source,
      status: entry.status,
    };
  });
}

export function deriveVerifiedRecords(lane, observedCases) {
  const byId = new Map(observedCases.map((entry) => [entry.id, entry]));
  return Object.entries(lane.recordRequirements ?? {})
    .filter(([, requiredCases]) =>
      requiredCases.length > 0 &&
      requiredCases.every((caseId) => byId.get(caseId)?.status === "PASSED"),
    )
    .map(([recordId]) => recordId)
    .sort();
}

export function parseLaneMachineReport(lane, raw) {
  if (lane.evidenceParser === "none") {
    if (raw !== null) {
      throw new Error("non-reporting lane unexpectedly supplied a machine report");
    }
    return { cases: [], structuredResult: undefined };
  }
  if (lane.evidenceParser === "junit") {
    if (!Array.isArray(raw)) {
      throw new Error("JUnit lane machine report must contain XML documents");
    }
    return { cases: validateObservedCases(parseJUnitDocuments(raw)) };
  }
  if (typeof raw !== "string") {
    throw new Error("stdout machine report must be a string");
  }
  if (lane.evidenceParser === "playwright") {
    return { cases: validateObservedCases(parsePlaywrightJson(raw)) };
  }
  if (lane.evidenceParser === "vitest") {
    return { cases: validateObservedCases(parseVitestJson(raw)) };
  }
  if (lane.evidenceParser === "structured") {
    const parsed = parseStructuredCases(raw, lane.structuredKind);
    return {
      cases: validateObservedCases(parsed.cases),
      structuredResult: parsed.report,
    };
  }
  throw new Error("lane has no supported machine-report parser");
}
