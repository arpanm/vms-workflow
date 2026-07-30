import { readFile } from "node:fs/promises";
import { readJson, repoPath, run } from "./lib.mjs";

const reviewEvidenceType = "f07-review-evidence-v2";
const traceabilityType = "f07-traceability-matrix-v1";

export function hasRunbookAnchor(markdown, reference) {
  const separator = reference.indexOf("#");
  if (separator < 1 || separator === reference.length - 1) return false;
  const anchor = decodeURIComponent(reference.slice(separator + 1));
  return markdown.includes(`<a id="${anchor}"></a>`);
}

function catalogSections(markdown, idPattern) {
  const records = new Map();
  let section = null;
  for (const line of markdown.split(/\r?\n/)) {
    const heading = line.match(/^## ([A-L])\./);
    if (heading) section = heading[1];
    for (const match of line.matchAll(idPattern)) {
      if (!section) throw new Error(`${match[1]} appears before a catalog section`);
      if (records.has(match[1])) throw new Error(`duplicate catalog ID ${match[1]}`);
      records.set(match[1], section);
    }
  }
  return records;
}

function validPolicy(policy) {
  return (
    Array.isArray(policy?.requirements) &&
    policy.requirements.length > 0 &&
    Array.isArray(policy.prd) &&
    policy.prd.length > 0 &&
    ["schemaImpact", "apiImpact", "uiImpact", "runbook", "rollback"].every(
      (field) => typeof policy[field] === "string" && policy[field].trim().length > 0,
    )
  );
}

export async function validateTraceability(inventory) {
  const matrix = await readJson(
    repoPath("docs/features/07-hardening-go-live/traceability-matrix.json"),
  );
  const taskText = await readFile(
    repoPath("docs/features/07-hardening-go-live/TASKS.md"),
    "utf8",
  );
  const testText = await readFile(
    repoPath("docs/features/07-hardening-go-live/TEST_CASES.md"),
    "utf8",
  );
  const tasks = catalogSections(taskText, /\*\*(F07-T\d{3})\s+—/g);
  const tests = catalogSections(
    testText,
    /\*\*(F07-[A-Z0-9-]+|T-DR-001|E2E-\d{2})\*\*/g,
  );
  const findings = [];
  const records = new Map();
  if (
    matrix?.schemaVersion !== 1 ||
    matrix?.type !== traceabilityType ||
    typeof matrix.taskSections !== "object" ||
    typeof matrix.testSections !== "object"
  ) {
    findings.push(
      `traceability matrix must use schemaVersion 1 and type ${traceabilityType}`,
    );
  }
  const expectedTasks = new Set(inventory.taskIds);
  const expectedTests = new Set(inventory.testIds);
  for (const [id, section] of tasks) {
    if (!expectedTasks.has(id)) findings.push(`${id}: not in required inventory`);
    const policy = matrix.taskSections?.[section];
    if (!validPolicy(policy)) findings.push(`${id}: task section ${section} policy is incomplete`);
    else records.set(id, {...policy, catalogSection: section});
  }
  for (const [id, section] of tests) {
    if (!expectedTests.has(id)) findings.push(`${id}: not in required inventory`);
    const taskSection = matrix.testSections?.[section];
    const policy = matrix.taskSections?.[taskSection];
    if (!validPolicy(policy)) findings.push(`${id}: test section ${section} policy is incomplete`);
    else records.set(id, {...policy, catalogSection: section});
  }
  for (const id of [...expectedTasks, ...expectedTests]) {
    if (!records.has(id)) findings.push(`${id}: traceability record is missing`);
  }
  const requirementCoverage = new Set(
    [...records.values()].flatMap((record) => record.requirements),
  );
  for (const requirement of ["RQ-033", "RQ-034", "RQ-035"]) {
    if (!requirementCoverage.has(requirement)) {
      findings.push(`mandatory requirement ${requirement} is orphaned`);
    }
  }
  for (const record of records.values()) {
    const runbookPath = record.runbook.split("#")[0];
    try {
      const runbook = await readFile(repoPath(runbookPath), "utf8");
      if (!hasRunbookAnchor(runbook, record.runbook)) {
        findings.push(`runbook anchor is unavailable: ${record.runbook}`);
      }
    } catch {
      findings.push(`runbook evidence is unavailable: ${record.runbook}`);
    }
  }
  return {
    findings: [...new Set(findings)].sort(),
    matrix,
    records,
    result: findings.length === 0 ? "PASS" : "FAIL",
  };
}

export async function validateReviewEvidence(options = {}) {
  const evidence =
    options.evidence ??
    (await readJson(
      repoPath("docs/features/07-hardening-go-live/review-evidence.json"),
    ));
  const findings = [];
  const requiredDimensions = new Set([
    "architecture",
    "code",
    "operations",
    "security-privacy",
    "test",
  ]);
  const observed = new Set();
  if (
    evidence?.schemaVersion !== 2 ||
    evidence?.type !== reviewEvidenceType
  ) {
    findings.push(
      `review evidence must use schemaVersion 2 and type ${reviewEvidenceType}`,
    );
  }
  if (!Array.isArray(evidence.dimensions)) {
    findings.push("review dimensions must be an explicit array");
  }
  for (const dimension of evidence.dimensions ?? []) {
    if (
      !dimension ||
      typeof dimension !== "object" ||
      typeof dimension.id !== "string" ||
      !requiredDimensions.has(dimension.id) ||
      observed.has(dimension.id)
    ) {
      findings.push("review dimensions must have unique IDs");
    }
    observed.add(dimension.id);
    for (const field of ["review", "issues"]) {
      if (typeof dimension[field] !== "string" || !dimension[field].trim()) {
        findings.push(`${dimension.id}: ${field} must be a repository path`);
        continue;
      }
      try {
        const content = await readFile(repoPath(dimension[field]), "utf8");
        if (content.trim().length === 0) {
          findings.push(`${dimension.id}: ${field} document is empty`);
        }
      } catch {
        findings.push(`${dimension.id}: ${field} document is unavailable`);
      }
    }
  }
  for (const dimension of requiredDimensions) {
    if (!observed.has(dimension)) findings.push(`review dimension is missing: ${dimension}`);
  }
  if (!/^[0-9a-f]{40}$/.test(evidence.reviewedThroughCommit ?? "")) {
    findings.push("reviewedThroughCommit must be an exact commit");
  } else {
    const commit = evidence.reviewedThroughCommit;
    const object = run("git", ["cat-file", "-e", `${commit}^{commit}`]);
    if (object.status !== 0) {
      findings.push("reviewedThroughCommit must resolve to a Git commit object");
    } else {
      const ancestor = run("git", [
        "merge-base",
        "--is-ancestor",
        commit,
        options.headRef ?? "HEAD",
      ]);
      if (ancestor.status !== 0) {
        findings.push(
          "reviewedThroughCommit must be an ancestor of the validated release",
        );
      }
    }
  }
  if (
    !Array.isArray(evidence.openLocalP0P1) ||
    evidence.openLocalP0P1.some(
      (finding) => typeof finding !== "string" || !finding.trim(),
    )
  ) {
    findings.push("openLocalP0P1 must be an explicit array");
  }
  const dispositions = evidence.closureDispositions;
  const dimensionDocuments = new Map(
    (evidence.dimensions ?? []).map((dimension) => [
      dimension.id,
      {issues: dimension.issues, review: dimension.review},
    ]),
  );
  if (!Array.isArray(dispositions)) {
    findings.push("closureDispositions must be an explicit array");
  } else {
    const dispositionDimensions = new Set();
    for (const disposition of dispositions) {
      if (
        !disposition ||
        typeof disposition !== "object" ||
        !requiredDimensions.has(disposition.dimension) ||
        dispositionDimensions.has(disposition.dimension)
      ) {
        findings.push("closure dispositions must have unique review dimensions");
        continue;
      }
      dispositionDimensions.add(disposition.dimension);
      if (
        disposition.status !== "CLOSED_LOCAL_REVIEW" ||
        disposition.scope !== "LOCAL_REPOSITORY" ||
        typeof disposition.summary !== "string" ||
        !disposition.summary.trim() ||
        disposition.issueDocument !==
          dimensionDocuments.get(disposition.dimension)?.issues ||
        disposition.reviewDocument !==
          dimensionDocuments.get(disposition.dimension)?.review ||
        !Array.isArray(disposition.openLocalP0P1) ||
        disposition.openLocalP0P1.length > 0 ||
        disposition.reviewedThroughCommit !== evidence.reviewedThroughCommit
      ) {
        findings.push(
          `${disposition.dimension}: closure disposition is incomplete`,
        );
      }
    }
    for (const dimension of requiredDimensions) {
      if (!dispositionDimensions.has(dimension)) {
        findings.push(`closure disposition is missing: ${dimension}`);
      }
    }
  }
  try {
    const ledger = await readFile(repoPath(evidence.findingLedger), "utf8");
    if (!ledger.includes("# F07") || ledger.trim().length === 0) {
      findings.push("review finding ledger is empty or malformed");
    }
  } catch {
    findings.push("review finding ledger is unavailable");
  }
  if (
    evidence.result !== "PASS" ||
    evidence.openLocalP0P1?.length > 0 ||
    typeof evidence.scopeNote !== "string" ||
    !evidence.scopeNote.trim()
  ) {
    findings.push("review evidence cannot pass with open local P0/P1 findings");
  }
  return {
    evidence,
    findings: [...new Set(findings)].sort(),
    result: findings.length === 0 ? "PASS" : "FAIL",
  };
}
