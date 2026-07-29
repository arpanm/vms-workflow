#!/usr/bin/env node

import { readdir, readFile } from "node:fs/promises";
import { relative, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import {
  commandExists,
  existingRepoPath,
  gitMetadata,
  isLoopbackUrl,
  parseArgs,
  readJson,
  redactDatabaseUrl,
  repoPath,
  repoRoot,
  run,
  safeError,
  sha256Bytes,
  sha256File,
  stableJson,
  writeJson,
} from "./lib.mjs";
import { createProvenance } from "./provenance.mjs";

const migrationDirectory = repoPath("backend/src/main/resources/db/migration");
const bootstrapPath = repoPath("scripts/f07/bootstrap-database-roles.sql");
const destructiveExceptionPath = repoPath(
  "scripts/f07/migration-destructive-exceptions.json",
);
const protectedBaselinePath = repoPath(
  "docs/features/07-hardening-go-live/migration-baseline.json",
);

export function validatesRoleBootstrapContract(bootstrap) {
  return (
    /CREATE ROLE %I NOLOGIN NOSUPERUSER NOCREATEDB[\s\S]*NOCREATEROLE INHERIT NOREPLICATION NOBYPASSRLS/.test(
      bootstrap,
    ) &&
    /'vms_migration_processor'/.test(bootstrap) &&
    /GRANT vms_migration_owner TO :"migration_login";/.test(bootstrap) &&
    /pg_has_role\([\s\S]*:'migration_login',\s*'vms_migration_owner',\s*'MEMBER'/.test(
      bootstrap,
    ) &&
    bootstrap.includes("\\set ON_ERROR_STOP on")
  );
}

export const requiredMigrationExecutions = {
  "empty-database-migrate": {
    command: "node scripts/f07/bootstrap-rehearsal.mjs --execute",
    evidenceId: "F07-CI-DB-BOOTSTRAP-SQL",
  },
  "upgrade-database-migrate": {
    command: "npm run e2e:migration:system",
    evidenceId: "F07-CI-MIGRATION-SYSTEM",
  },
  "flyway-validate": {
    command: "mvn -B -f backend/pom.xml -Dit.test=F07MigrationBootstrapIT verify",
    evidenceId: "F07-CI-DB-BOOTSTRAP",
  },
  "forward-application-compatibility": {
    command: "npm run e2e:migration:system",
    evidenceId: "F07-CI-MIGRATION-SYSTEM",
  },
  "backward-application-compatibility": {
    command: "npm run e2e:migration:system",
    evidenceId: "F07-CI-MIGRATION-SYSTEM",
  },
  "evidence-counts-preserved": {
    command: "npm run e2e:migration:system",
    evidenceId: "F07-CI-MIGRATION-SYSTEM",
  },
};

export function destructiveSqlOperations(sql) {
  const normalized = stripSqlComments(sql);
  const statements = normalized
    .split(";")
    .map((statement) => statement.trim())
    .filter(Boolean);
  const operations = [];
  const patterns = [
    ["DROP_OBJECT", /\bDROP\s+(?:TABLE|SCHEMA|DATABASE|SEQUENCE|VIEW|MATERIALIZED\s+VIEW|TYPE|INDEX)\b/i],
    ["TRUNCATE", /\bTRUNCATE(?!\s+ON\b)\b/i],
    ["DELETE", /\bDELETE\s+FROM\b/i],
    ["DROP_COLUMN", /\bALTER\s+TABLE\b[\s\S]*?\bDROP\s+COLUMN\b/i],
    ["ALTER_COLUMN_TYPE", /\bALTER\s+TABLE\b[\s\S]*?\bALTER\s+COLUMN\b[\s\S]*?\bTYPE\b/i],
  ];
  for (const [category, pattern] of patterns) {
    if (statements.some((statement) => pattern.test(statement))) {
      operations.push(category);
    }
  }
  for (const statement of statements) {
    if (
      /^\s*UPDATE\b/i.test(statement) &&
      /\bSET\b/i.test(statement) &&
      !/\bWHERE\b/i.test(statement)
    ) {
      operations.push("UNQUALIFIED_UPDATE");
    }
  }
  return [...new Set(operations)].sort();
}

function flywayChecksum(sql) {
  let crc = 0xffffffff;
  for (const line of sql.split(/\r?\n/)) {
    const bytes = Buffer.from(line, "utf8");
    for (const byte of bytes) {
      crc ^= byte;
      for (let bit = 0; bit < 8; bit += 1) {
        crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
      }
    }
  }
  return (crc ^ 0xffffffff) | 0;
}

export async function staticPreflight(baseRef, options = {}) {
  const exceptionDocument = await readJson(destructiveExceptionPath);
  const destructiveExceptions = new Map(
    (exceptionDocument.entries ?? []).map((entry) => [entry.migration, entry]),
  );
  const names = (await readdir(migrationDirectory))
    .filter((name) => /^V\d+__[A-Za-z0-9_]+\.sql$/.test(name))
    .sort((left, right) => {
      const leftVersion = Number(left.match(/^V(\d+)/)[1]);
      const rightVersion = Number(right.match(/^V(\d+)/)[1]);
      return leftVersion - rightVersion || left.localeCompare(right);
    });
  const versions = new Set();
  const migrations = [];
  const findings = [];
  for (const name of names) {
    const version = Number(name.match(/^V(\d+)/)[1]);
    if (versions.has(version)) {
      findings.push(`duplicate Flyway version V${version}`);
    }
    versions.add(version);
    const path = resolve(migrationDirectory, name);
    const sql = await readFile(path, "utf8");
    const destructiveOperations = destructiveSqlOperations(sql);
    if (destructiveOperations.length > 0) {
      const exception = destructiveExceptions.get(name);
      if (
        !exception ||
        exception.sha256 !== (await sha256File(path)) ||
        !exception.owner ||
        !exception.reviewedBy ||
        exception.owner === exception.reviewedBy ||
        !exception.reason ||
        !Number.isFinite(Date.parse(exception.approvedAt)) ||
        !Number.isFinite(Date.parse(exception.expiresAt)) ||
        new Date(exception.expiresAt) <= new Date() ||
        stableJson([...(exception.operations ?? [])].sort()) !==
          stableJson(destructiveOperations)
      ) {
        findings.push(
          `${name}: destructive operations ${destructiveOperations.join(",")} lack current independent reviewed exception metadata`,
        );
      }
    }
    migrations.push({
      flywayChecksum: flywayChecksum(sql),
      path: relative(repoRoot, path),
      sha256: await sha256File(path),
      version,
    });
  }
  if (names.length === 0) {
    findings.push("no Flyway migrations found");
  }
  try {
    const bootstrap = await readFile(bootstrapPath, "utf8");
    if (!validatesRoleBootstrapContract(bootstrap)) {
      findings.push("database role bootstrap does not enforce the constrained migration-owner contract");
    }
  } catch {
    findings.push("scripts/f07/bootstrap-database-roles.sql is required");
  }

  const appendOnlyFindings = [];
  if (baseRef) {
    const head = gitMetadata().commit;
    const trustedBaseRef = options.trustedBaseRef;
    if (!/^[0-9a-f]{40}$/.test(baseRef)) {
      appendOnlyFindings.push("trusted base ref must be an exact 40-character commit");
    }
    if (
      !/^[0-9a-f]{40}$/.test(trustedBaseRef ?? "") ||
      trustedBaseRef !== baseRef
    ) {
      appendOnlyFindings.push(
        "base ref must exactly match the protected F07_TRUSTED_MIGRATION_BASE",
      );
    }
    if (baseRef === head) {
      appendOnlyFindings.push("trusted migration base must be a strict ancestor of HEAD");
    }
    const verified = run("git", ["rev-parse", "--verify", `${baseRef}^{commit}`]);
    const ancestor = run("git", ["merge-base", "--is-ancestor", baseRef, "HEAD"]);
    if (verified.status !== 0 || verified.stdout.trim() !== baseRef || ancestor.status !== 0) {
      appendOnlyFindings.push("trusted base commit is invalid or not an ancestor of HEAD");
    }
    const diff = run("git", [
      "diff",
      "--name-status",
      "--diff-filter=DMRT",
      baseRef,
      "--",
      "backend/src/main/resources/db/migration",
    ]);
    if (diff.status !== 0) {
      appendOnlyFindings.push(`cannot compare migrations with ${baseRef}`);
    } else if (diff.stdout.trim()) {
      appendOnlyFindings.push(
        ...diff.stdout
          .trim()
          .split("\n")
          .map((line) => `append-only violation: ${line}`),
      );
    }
  }
  findings.push(...appendOnlyFindings);
  return {
    baseRef: baseRef ?? null,
    findings: findings.sort(),
    migrationCount: migrations.length,
    migrations,
    roleBootstrap: {
      path: "scripts/f07/bootstrap-database-roles.sql",
      sha256: await sha256File(bootstrapPath).catch(() => null),
    },
    destructiveExceptionInventory: {
      path: "scripts/f07/migration-destructive-exceptions.json",
      sha256: await sha256File(destructiveExceptionPath),
    },
    result: findings.length === 0 ? "PASS" : "FAIL",
  };
}

function stripSqlComments(sql) {
  return sql.replace(/--.*$/gm, "").replace(/\/\*[\s\S]*?\*\//g, "");
}

async function liveHistoryCheck(rawUrl) {
  const parsedUrl = new URL(rawUrl.replace(/^jdbc:/, ""));
  if (
    !["postgres:", "postgresql:"].includes(parsedUrl.protocol) ||
    !["127.0.0.1", "localhost", "::1", "[::1]"].includes(parsedUrl.hostname)
  ) {
    throw new Error("live preflight is restricted to loopback PostgreSQL targets");
  }
  const descriptor = redactDatabaseUrl(rawUrl.replace(/^jdbc:/, ""));
  if (!descriptor.database.endsWith("_f07_preflight")) {
    throw new Error("live target database name must end with _f07_preflight");
  }
  if (!commandExists("psql")) {
    throw new Error("psql is required for live migration history verification");
  }
  const membership = run("psql", [
    rawUrl.replace(/^jdbc:/, ""),
    "--no-psqlrc",
    "--set",
    "ON_ERROR_STOP=1",
    "--tuples-only",
    "--no-align",
    "--command",
    "SELECT current_user || ':' || pg_has_role(current_user, 'vms_migration_owner', 'MEMBER');",
  ]);
  if (membership.status !== 0 || !membership.stdout.trim().endsWith(":t")) {
    throw new Error(
      "migration login is not a member of vms_migration_owner; a platform admin must run the role bootstrap first",
    );
  }
  const result = run("psql", [
    rawUrl.replace(/^jdbc:/, ""),
    "--no-psqlrc",
    "--set",
    "ON_ERROR_STOP=1",
    "--tuples-only",
    "--no-align",
    "--field-separator",
    "|",
    "--command",
    "SELECT version, checksum, success FROM flyway_schema_history ORDER BY installed_rank;",
  ]);
  if (result.status !== 0) {
    throw new Error("isolated Flyway history query failed; credentials and SQL are not echoed");
  }
  return {
    database: descriptor,
    migrationLoginMembership: "VERIFIED",
    history: result.stdout
      .trim()
      .split("\n")
      .filter(Boolean)
      .map((line) => {
        const [version, checksum, success] = line.split("|");
        return { checksum: Number(checksum), success: success === "t", version };
      }),
    result: "CONFIGURED_UNVERIFIED",
  };
}

async function validatedReference(reference, label) {
  if (!reference?.path || !reference?.sha256) {
    throw new Error(`${label} needs path and sha256`);
  }
  const path = await existingRepoPath(reference.path, label);
  if ((await sha256File(path)) !== reference.sha256) {
    throw new Error(`${label} checksum drift`);
  }
  return { document: await readJson(path), path };
}

export async function validateRehearsalEvidence(path, context = {}) {
  const findings = [];
  let evidence;
  try {
    const canonical = await existingRepoPath(path, "migration rehearsal report");
    evidence = await readJson(canonical);
  } catch (error) {
    return { findings: [safeError(error)], result: "FAIL" };
  }
  if (
    evidence.schemaVersion !== 1 ||
    evidence.releaseCommit !== context.releaseCommit ||
    evidence.record?.id !== "F07-REL-003" ||
    evidence.record?.kind !== "test" ||
    evidence.record?.result !== "PASS" ||
    !Number.isFinite(evidence.record?.durationMs)
  ) {
    findings.push("rehearsal report metadata is incomplete or bound to another release");
  }
  const requiredExecutions = Object.keys(requiredMigrationExecutions);
  const executions = new Map(
    (evidence.executions ?? []).map((execution) => [execution.id, execution]),
  );
  if (executions.size !== (evidence.executions ?? []).length) {
    findings.push("migration rehearsal contains duplicate execution IDs");
  }
  for (const id of requiredExecutions) {
    const execution = executions.get(id);
    const expectedExecution = requiredMigrationExecutions[id];
    if (
      !execution ||
      execution.result !== "PASS" ||
      execution.exitCode !== 0 ||
      !execution.command ||
      execution.commandSha256 !==
        (execution.command ? sha256Bytes(execution.command) : null) ||
      !Number.isFinite(execution.durationMs)
    ) {
      findings.push(`invalid or absent migration execution: ${id}`);
      continue;
    }
    try {
      const referenced = await validatedReference(
        execution.evidence,
        `${id} command evidence`,
      );
      const commandEvidence = referenced.document;
      if (
        execution.command !== expectedExecution.command ||
        commandEvidence.schemaVersion !== 1 ||
        commandEvidence.releaseCommit !== context.releaseCommit ||
        commandEvidence.runner?.name !== "scripts/f07/command-evidence.mjs" ||
        commandEvidence.runner?.schemaVersion !== 1 ||
        commandEvidence.record?.id !== expectedExecution.evidenceId ||
        commandEvidence.record?.kind !== "test" ||
        commandEvidence.record?.result !== "PASS" ||
        commandEvidence.record?.command !== expectedExecution.command ||
        commandEvidence.record?.environment !== "github-actions" ||
        commandEvidence.record?.durationMs !== execution.durationMs ||
        commandEvidence.commandOutput?.exitCode !== 0 ||
        !/^[0-9a-f]{64}$/.test(commandEvidence.commandOutput?.stdoutSha256 ?? "") ||
        !/^[0-9a-f]{64}$/.test(commandEvidence.commandOutput?.stderrSha256 ?? "") ||
        stableJson(commandEvidence.provenance) !== stableJson(evidence.provenance)
      ) {
        findings.push(`untrusted structured migration execution: ${id}`);
      }
    } catch (error) {
      findings.push(safeError(error));
    }
  }
  try {
    const provenance = await validatedReference(evidence.provenance, "migration provenance");
    if (
      provenance.document.schemaVersion !== 1 ||
      provenance.document.predicateType !== "https://slsa.dev/provenance/v1" ||
      provenance.document.commit !== context.releaseCommit ||
      provenance.document.worktreeDirty !== false ||
      (!provenance.document.expectedInputs?.includes(
        "scripts/f07/bootstrap-database-roles.sql",
      ) &&
        !provenance.document.expectedInputs?.includes("scripts/f07"))
    ) {
      findings.push("migration provenance is dirty, wrong-commit, or omits role bootstrap");
    } else {
      const decisionTime = await createProvenance(provenance.document.expectedInputs, {
        expectedCommit: context.releaseCommit,
        requireClean: true,
      });
      if (
        stableJson(decisionTime.artifacts) !== stableJson(provenance.document.artifacts) ||
        stableJson(decisionTime.composeImages) !== stableJson(provenance.document.composeImages)
      ) {
        findings.push("migration provenance artifacts drifted before validation");
      }
    }
  } catch (error) {
    findings.push(safeError(error));
  }
  const expectedHistory = (context.sourceMigrations ?? []).map((migration) => ({
    checksum: migration.flywayChecksum,
    success: true,
    version: String(migration.version),
  }));
  if (stableJson(expectedHistory) !== stableJson(context.liveHistory ?? [])) {
    findings.push("live Flyway versions/checksums do not match source migration inventory");
  }
  return {
    findings,
    result: findings.length === 0 ? "PASS" : "FAIL",
  };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const protectedBaseline = await readJson(protectedBaselinePath);
  const report = await staticPreflight(args["base-ref"], {
    trustedBaseRef: protectedBaseline.commit,
  });
  if (args["schema-only"]) {
    report.mode = "SCHEMA_ONLY";
  } else if (
    !args["base-ref"] ||
    !args.live ||
    !args["rehearsal-evidence"] ||
    args["release-commit"] !== gitMetadata().commit
  ) {
    report.findings.push(
      "release preflight requires exact --base-ref, --release-commit, --live, and --rehearsal-evidence",
    );
    report.result = "ACTION_REQUIRED";
    report.live = {
      result: "ACTION_REQUIRED",
      reason: "trusted base and isolated live rehearsal evidence are mandatory",
    };
  } else {
    const rawUrl = process.env.F07_PREFLIGHT_DATABASE_URL;
    if (!rawUrl) {
      throw new Error("F07_PREFLIGHT_DATABASE_URL is required with --live");
    }
    report.live = await liveHistoryCheck(rawUrl);
    report.rehearsal = await validateRehearsalEvidence(
      repoPath(args["rehearsal-evidence"], "rehearsal evidence"),
      {
        liveHistory: report.live.history,
        releaseCommit: args["release-commit"],
        sourceMigrations: report.migrations,
      },
    );
    report.live.result =
      report.result === "PASS" && report.rehearsal.result === "PASS" ? "PASS" : "FAIL";
    report.result = report.live.result;
  }
  if (args.output) {
    await writeJson(repoPath(args.output, "output"), report);
  }
  process.stdout.write(stableJson(report));
  process.exitCode =
    report.result === "PASS" && (args["schema-only"] || report.live?.result === "PASS") ? 0 : 1;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    process.stderr.write(`F07 migration preflight failed safely: ${safeError(error)}\n`);
    process.exitCode = 1;
  });
}
