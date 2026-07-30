#!/usr/bin/env node

import { randomUUID } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import {
  gitMetadata,
  parseArgs,
  readJson,
  repoPath,
  repoRoot,
  run,
  safeError,
  stableJson,
  writeJson,
} from "./lib.mjs";
import { validatePostgresClientContainerName } from "./backup-drill.mjs";

export function validateDrillDatabaseNames(source, target) {
  if (
    !/^[a-z][a-z0-9_]{2,62}_f07_source$/.test(source ?? "") ||
    !/^[a-z][a-z0-9_]{2,62}_f07_drill$/.test(target ?? "") ||
    source === target
  ) {
    throw new Error("DR rehearsal requires distinct explicit source/drill database names");
  }
  return { source, target };
}

function containerCommand(container, url, database, command, args = []) {
  const parsed = new URL(url.replace(/^jdbc:/, ""));
  const result = run("docker", [
    "exec",
    "--env",
    `PGDATABASE=${database}`,
    "--env",
    "PGHOST=127.0.0.1",
    "--env",
    `PGPASSWORD=${decodeURIComponent(parsed.password)}`,
    "--env",
    "PGPORT=5432",
    "--env",
    `PGUSER=${decodeURIComponent(parsed.username)}`,
    container,
    command,
    ...args,
  ]);
  if (result.status !== 0) {
    throw new Error(`isolated DR database ${command} command failed`);
  }
  return result.stdout.trim();
}

function psql(container, url, database, sql) {
  return containerCommand(container, url, database, "psql", [
    "--no-psqlrc",
    "--set",
    "ON_ERROR_STOP=1",
    "--tuples-only",
    "--no-align",
    "--command",
    sql,
  ]);
}

function connectionUrl(adminUrl, database) {
  const parsed = new URL(adminUrl.replace(/^jdbc:/, ""));
  parsed.pathname = `/${database}`;
  return parsed.toString();
}

function assertContainer(container) {
  const inspected = run("docker", [
    "inspect",
    "--format",
    "{{.Config.Image}}|{{.State.Running}}",
    container,
  ]);
  const [image, running] = inspected.stdout.trim().split("|");
  if (
    inspected.status !== 0 ||
    running !== "true" ||
    !/^[^\s@]+@sha256:[0-9a-f]{64}$/.test(image ?? "")
  ) {
    throw new Error("DR rehearsal container must be running from a digest-pinned image");
  }
  return image;
}

function databaseExists(container, adminUrl, adminDatabase, database) {
  return psql(
    container,
    adminUrl,
    adminDatabase,
    `SELECT count(*) FROM pg_database WHERE datname = '${database}'`,
  ) === "1";
}

function createDatabase(container, adminUrl, adminDatabase, database) {
  if (databaseExists(container, adminUrl, adminDatabase, database)) {
    throw new Error(`refusing to reuse existing DR rehearsal database ${database}`);
  }
  psql(
    container,
    adminUrl,
    adminDatabase,
    `CREATE DATABASE "${database}"`,
  );
}

function dropDatabase(container, adminUrl, adminDatabase, database) {
  psql(
    container,
    adminUrl,
    adminDatabase,
    `SELECT pg_terminate_backend(pid) FROM pg_stat_activity ` +
      `WHERE datname = '${database}' AND pid <> pg_backend_pid()`,
  );
  psql(
    container,
    adminUrl,
    adminDatabase,
    `DROP DATABASE IF EXISTS "${database}"`,
  );
}

function seedSource(container, adminUrl, seedDatabase, sourceDatabase) {
  const dump = `/tmp/f07-seed-${randomUUID()}.dump`;
  try {
    containerCommand(container, adminUrl, seedDatabase, "pg_dump", [
      "--format=custom",
      "--no-owner",
      "--no-acl",
      "--file",
      dump,
    ]);
    containerCommand(container, adminUrl, sourceDatabase, "pg_restore", [
      "--exit-on-error",
      "--no-owner",
      "--no-acl",
      "--dbname",
      sourceDatabase,
      dump,
    ]);
  } finally {
    run("docker", ["exec", container, "rm", "-f", dump]);
  }
}

function runDrillCommand(args, environment, acceptActionRequired = false) {
  const result = run(process.execPath, args, {
    cwd: repoRoot,
    env: { ...process.env, ...environment },
    timeoutMs: 10 * 60 * 1000,
  });
  if (
    result.error ||
    result.signal ||
    (result.status !== 0 && !acceptActionRequired)
  ) {
    throw new Error(
      `backup/restore drill subprocess failed safely: ${safeError(
        result.error ?? result.stderr,
      )}`,
    );
  }
  return result;
}

async function execute(args) {
  const started = Date.now();
  const container = validatePostgresClientContainerName(
    String(args["postgres-container"] ?? ""),
  );
  const adminUrl = process.env.F07_ADMIN_DATABASE_URL ?? "";
  if (!adminUrl) {
    throw new Error("F07_ADMIN_DATABASE_URL is required");
  }
  const parsedAdmin = new URL(adminUrl.replace(/^jdbc:/, ""));
  if (!["127.0.0.1", "localhost", "::1", "[::1]"].includes(parsedAdmin.hostname)) {
    throw new Error("DR rehearsal admin database must be loopback-only");
  }
  const adminDatabase = parsedAdmin.pathname.replace(/^\//, "");
  const seedDatabase = String(args["seed-database"] ?? adminDatabase);
  if (!/^[a-z][a-z0-9_]{1,62}$/.test(seedDatabase)) {
    throw new Error("seed database name is invalid");
  }
  const names = validateDrillDatabaseNames(
    String(args["source-database"] ?? "vms_workflow_f07_source"),
    String(args["target-database"] ?? "vms_workflow_f07_drill"),
  );
  const confirmation = String(args["confirm-databases"] ?? "");
  if (confirmation !== `${names.source},${names.target}`) {
    throw new Error("--confirm-databases must exactly name source,target");
  }
  const image = assertContainer(container);
  const runId = String(args["run-id"] ?? `local-${Date.now()}`);
  if (!/^[A-Za-z0-9._-]+$/.test(runId)) {
    throw new Error("DR rehearsal run ID is invalid");
  }
  const backupRelative = `.f07-evidence/backups/${runId}`;
  const evidenceRelative = `.f07-evidence/${runId}`;
  const objects = repoPath(`${evidenceRelative}/source-objects`, "object fixture");
  await mkdir(objects, { recursive: true, mode: 0o700 });
  await writeFile(
    resolve(objects, "immutable-package-fixture.txt"),
    `release=${gitMetadata().commit}\n`,
    { encoding: "utf8", flag: "wx", mode: 0o600 },
  );
  const secrets = {
    F07_BACKUP_INTEGRITY_KEY: `integrity-${randomUUID()}-${randomUUID()}`,
    F07_BACKUP_INTEGRITY_KEY_ID: "local-dr-integrity-v1",
    F07_BACKUP_PASSPHRASE: `passphrase-${randomUUID()}`,
    F07_BACKUP_PASSPHRASE_ID: "local-dr-passphrase-v1",
    F07_DRILL_SIGNING_KEY: `signing-${randomUUID()}`,
    F07_DRILL_SIGNING_KEY_ID: "local-dr-signing-v1",
  };
  let sourceCreated = false;
  let targetCreated = false;
  try {
    createDatabase(container, adminUrl, adminDatabase, names.source);
    sourceCreated = true;
    createDatabase(container, adminUrl, adminDatabase, names.target);
    targetCreated = true;
    seedSource(container, adminUrl, seedDatabase, names.source);
    const restoreExecution = runDrillCommand(
      [
        "scripts/f07/backup-drill.mjs",
        "backup",
        "--execute",
        "--postgres-client-container",
        container,
        "--output",
        backupRelative,
      ],
      {
        ...secrets,
        F07_SOURCE_DATABASE_URL: connectionUrl(adminUrl, names.source),
        F07_SOURCE_OBJECT_ROOT: objects,
      },
    );
    const restoreReport = `${evidenceRelative}/restore-drill.json`;
    runDrillCommand(
      [
        "scripts/f07/backup-drill.mjs",
        "restore",
        "--execute",
        "--postgres-client-container",
        container,
        "--input",
        backupRelative,
        "--expected-backup-commit",
        gitMetadata().commit,
        "--max-age-hours",
        "24",
        "--output",
        restoreReport,
      ],
      {
        ...secrets,
        F07_CONFIRM_EMPTY_TARGET: names.target,
        F07_DRILL_DATABASE_URL: connectionUrl(adminUrl, names.target),
        F07_DRILL_OBJECT_ROOT: repoPath(
          `${evidenceRelative}/restored-objects`,
          "restored objects",
        ),
      },
      true,
    );
    try {
      await readFile(repoPath(restoreReport, "restore report"));
    } catch (error) {
      throw new Error(
        `restore report was not produced: ${safeError(
          restoreExecution.error ?? restoreExecution.stderr ?? error,
        )}`,
      );
    }
    const report = await readJson(repoPath(restoreReport, "restore report"));
    const summary = {
      containerImage: image,
      databases: names,
      durationMs: Date.now() - started,
      kind: "f07-local-dr-rehearsal-v1",
      productionPitr: "ACTION_REQUIRED",
      releaseCommit: gitMetadata().commit,
      result: report.result,
      restoreReport: {
        path: restoreReport,
        result: report.result,
      },
      schemaVersion: 1,
    };
    await writeJson(
      repoPath(`${evidenceRelative}/local-dr-summary.json`, "DR summary"),
      summary,
    );
    return summary;
  } finally {
    if (targetCreated) {
      dropDatabase(container, adminUrl, adminDatabase, names.target);
    }
    if (sourceCreated) {
      dropDatabase(container, adminUrl, adminDatabase, names.source);
    }
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.execute) {
    process.stdout.write(stableJson({
      action: "LOCAL_DR_REHEARSAL_PLAN",
      destructive: "creates and later drops only explicitly confirmed *_f07_source/*_f07_drill databases",
      result: "ACTION_REQUIRED",
    }));
    return;
  }
  const result = await execute(args);
  process.stdout.write(stableJson(result));
  process.exitCode = result.result === "PASS" ? 0 : 1;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    process.stderr.write(`F07 local DR rehearsal failed safely: ${safeError(error)}\n`);
    process.exitCode = 1;
  });
}
