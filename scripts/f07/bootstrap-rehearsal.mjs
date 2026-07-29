#!/usr/bin/env node

import { randomUUID } from "node:crypto";
import { pathToFileURL } from "node:url";
import {
  commandExists,
  repoPath,
  run,
  safeError,
  stableJson,
} from "./lib.mjs";

const image =
  "postgres:18-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15";

function checked(label, command, args, deadline, options = {}) {
  const remainingMs = Math.floor(deadline - performance.now());
  if (remainingMs <= 0) {
    throw new Error(`${label} exceeded the bootstrap rehearsal deadline`);
  }
  const result = run(command, args, {
    timeoutMs: Math.max(1, Math.min(30_000, remainingMs)),
    ...options,
  });
  if (result.status !== 0) {
    throw new Error(
      `${label} ${result.timedOut ? "timed out" : "failed"} during the isolated database-role bootstrap rehearsal`,
    );
  }
  return result.stdout.trim();
}

export function bootstrapRehearsalPlan() {
  return {
    database: "new isolated PostgreSQL database",
    image,
    migrationLogin: "LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS",
    result: "ACTION_REQUIRED",
  };
}

async function execute() {
  const started = performance.now();
  const readinessDeadline = performance.now() + 180_000;
  if (!commandExists("docker")) {
    throw new Error("docker is required for the clean PostgreSQL bootstrap rehearsal");
  }
  const container = `f07-bootstrap-${process.pid}-${randomUUID().slice(0, 8)}`;
  const admin = "f07_bootstrap_admin";
  const database = "f07_bootstrap_rehearsal";
  const password = `f07-bootstrap-${randomUUID()}`;
  try {
    checked("start database", "docker", [
      "run",
      "--detach",
      "--rm",
      "--name",
      container,
      "--env",
      `POSTGRES_DB=${database}`,
      "--env",
      `POSTGRES_USER=${admin}`,
      "--env",
      `POSTGRES_PASSWORD=${password}`,
      image,
    ], readinessDeadline);
    let ready = false;
    let consecutiveReadyChecks = 0;
    while (performance.now() < readinessDeadline) {
      const remainingMs = Math.max(
        1,
        Math.floor(readinessDeadline - performance.now()),
      );
      const status = run("docker", [
        "exec",
        container,
        "psql",
        "--no-psqlrc",
        "--tuples-only",
        "--no-align",
        "--username",
        admin,
        "--dbname",
        database,
        "--command",
        "SELECT 1",
      ], { timeoutMs: Math.min(5_000, remainingMs) });
      if (status.status === 0) {
        consecutiveReadyChecks += 1;
        if (consecutiveReadyChecks >= 2) {
          ready = true;
          break;
        }
      } else {
        consecutiveReadyChecks = 0;
      }
      const delayMs = Math.min(
        250,
        Math.max(0, readinessDeadline - performance.now()),
      );
      if (delayMs > 0) {
        await new Promise((resolveDelay) => setTimeout(resolveDelay, delayMs));
      }
    }
    if (!ready) {
      throw new Error("isolated PostgreSQL did not become ready");
    }
    checked("create constrained login", "docker", [
      "exec",
      container,
      "psql",
      "--no-psqlrc",
      "--set",
      "ON_ERROR_STOP=1",
      "--username",
      admin,
      "--dbname",
      database,
      "--command",
      "CREATE ROLE f07_migration_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOREPLICATION NOBYPASSRLS",
    ], readinessDeadline);
    checked("copy bootstrap script", "docker", [
      "cp",
      repoPath("scripts/f07/bootstrap-database-roles.sql"),
      `${container}:/tmp/bootstrap-database-roles.sql`,
    ], readinessDeadline);
    checked("execute bootstrap script", "docker", [
      "exec",
      container,
      "psql",
      "--no-psqlrc",
      "--set",
      "ON_ERROR_STOP=1",
      "--set",
      "migration_login=f07_migration_login",
      "--username",
      admin,
      "--dbname",
      database,
      "--file",
      "/tmp/bootstrap-database-roles.sql",
    ], readinessDeadline);
    const verification = checked("verify role contract", "docker", [
      "exec",
      container,
      "psql",
      "--no-psqlrc",
      "--tuples-only",
      "--no-align",
      "--username",
      admin,
      "--dbname",
      database,
      "--command",
      "SELECT rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolinherit, rolreplication, rolbypassrls, pg_has_role('f07_migration_login', 'vms_migration_owner', 'MEMBER') FROM pg_roles WHERE rolname = 'vms_migration_owner'",
    ], readinessDeadline);
    if (verification !== "f|f|f|f|t|f|f|t") {
      throw new Error("bootstrap role attributes or migration membership were not constrained");
    }
    const processorVerification = checked(
      "verify migration processor capability",
      "docker",
      [
        "exec",
        container,
        "psql",
        "--no-psqlrc",
        "--tuples-only",
        "--no-align",
        "--username",
        admin,
        "--dbname",
        database,
        "--command",
        "SELECT rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolinherit, rolreplication, rolbypassrls FROM pg_roles WHERE rolname = 'vms_migration_processor'",
      ],
      readinessDeadline,
    );
    if (processorVerification !== "f|f|f|f|t|f|f") {
      throw new Error(
        "migration processor capability role was absent or unconstrained",
      );
    }
    process.stdout.write(
      stableJson({
        bootstrapScript: "scripts/f07/bootstrap-database-roles.sql",
        cases: [
          {
            durationMs: Math.round(performance.now() - started),
            id: "DB-BOOTSTRAP-ROLE-CONTRACT",
            source: "docker:postgresql-bootstrap",
            status: "PASSED",
          },
        ],
        image,
        kind: "bootstrap-rehearsal-v1",
        result: "PASS",
        schemaVersion: 1,
        processorVerification,
        verification,
      }),
    );
  } finally {
    run("docker", ["rm", "--force", container], { timeoutMs: 15_000 });
  }
}

async function main() {
  if (process.argv.includes("--execute")) {
    await execute();
  } else {
    process.stdout.write(stableJson(bootstrapRehearsalPlan()));
    process.exitCode = 1;
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    process.stderr.write(`F07 bootstrap rehearsal failed safely: ${safeError(error)}\n`);
    process.exitCode = 1;
  });
}
