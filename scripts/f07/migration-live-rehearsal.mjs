#!/usr/bin/env node

import { randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { once } from "node:events";
import { pathToFileURL } from "node:url";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  gitMetadata,
  parseArgs,
  readJson,
  repoPath,
  repoRoot,
  run,
  safeError,
  sha256Bytes,
  stableJson,
} from "./lib.mjs";
import { staticPreflight } from "./migration-preflight.mjs";

const image =
  "postgres:18-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15";

function checked(label, args, deadline, timeoutMs = 30_000) {
  const remainingMs = Math.floor(deadline - performance.now());
  if (remainingMs <= 0) {
    throw new Error(`${label} exceeded the live rehearsal deadline`);
  }
  const result = run("docker", args, {
    timeoutMs: Math.max(1, Math.min(timeoutMs, remainingMs)),
  });
  if (result.status !== 0) {
    throw new Error(`${label} ${result.timedOut ? "timed out" : "failed"}`);
  }
  return result.stdout.trim();
}

async function availablePort() {
  const server = createServer();
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const address = server.address();
  const port = typeof address === "object" && address ? address.port : null;
  server.close();
  await once(server, "close");
  if (!port) throw new Error("unable to allocate loopback application port");
  return port;
}

async function waitForDatabase(container, admin, database, deadline) {
  let consecutive = 0;
  while (performance.now() < deadline) {
    const remainingMs = Math.max(1, Math.floor(deadline - performance.now()));
    const result = run(
      "docker",
      [
        "exec", container, "psql", "--no-psqlrc", "--tuples-only", "--no-align",
        "--username", admin, "--dbname", database, "--command", "SELECT 1",
      ],
      { timeoutMs: Math.min(5_000, remainingMs) },
    );
    consecutive = result.status === 0 ? consecutive + 1 : 0;
    if (consecutive >= 2) return;
    const delayMs = Math.min(250, Math.max(0, deadline - performance.now()));
    if (delayMs > 0) {
      await new Promise((resolveDelay) => setTimeout(resolveDelay, delayMs));
    }
  }
  throw new Error("isolated live-preflight PostgreSQL readiness timed out");
}

async function waitForHealth(port, child, deadline) {
  while (performance.now() < deadline) {
    if (child.exitCode !== null) {
      throw new Error("Spring migration rehearsal exited before readiness");
    }
    try {
      const remainingMs = Math.max(1, Math.floor(deadline - performance.now()));
      const response = await fetch(`http://127.0.0.1:${port}/actuator/health`, {
        signal: AbortSignal.timeout(Math.min(5_000, remainingMs)),
      });
      if (response.ok) return;
    } catch {
      // Bounded retry while Spring and Flyway start.
    }
    const delayMs = Math.min(1_000, Math.max(0, deadline - performance.now()));
    if (delayMs > 0) {
      await new Promise((resolveDelay) => setTimeout(resolveDelay, delayMs));
    }
  }
  throw new Error("Spring migration rehearsal readiness timed out");
}

async function stopChild(child) {
  if (child.exitCode !== null) return;
  child.kill("SIGTERM");
  await Promise.race([
    once(child, "exit"),
    new Promise((resolveDelay) => setTimeout(resolveDelay, 15_000)),
  ]);
  if (child.exitCode === null) child.kill("SIGKILL");
}

function checkedProcess(
  label,
  executable,
  args,
  deadline,
  timeoutMs = 120_000,
  cwd = repoRoot,
) {
  const remainingMs = Math.floor(deadline - performance.now());
  if (remainingMs <= 0) {
    throw new Error(`${label} exceeded the live rehearsal deadline`);
  }
  const result = run(executable, args, {
    cwd,
    timeoutMs: Math.max(1, Math.min(timeoutMs, remainingMs)),
  });
  if (result.status !== 0) {
    throw new Error(`${label} ${result.timedOut ? "timed out" : "failed"}`);
  }
  return result.stdout.trim();
}

function buildApplication(root, deadline) {
  checkedProcess(
    `build ${root === repoRoot ? "current" : "prior"} application`,
    "mvn",
    ["-B", "-q", "-f", "backend/pom.xml", "-DskipTests", "package"],
    deadline,
    180_000,
    root,
  );
  return join(
    root, "backend", "target", "workflow-backend-0.1.0-SNAPSHOT.jar",
  );
}

function startApplication(jar, port, databasePort, database, password) {
  return spawn("java", ["-jar", jar], {
    cwd: repoRoot,
    env: {
      ...process.env,
      SERVER_PORT: String(port),
      VMS_DATABASE_PASSWORD: password,
      VMS_DATABASE_URL:
        `jdbc:postgresql://127.0.0.1:${databasePort}/${database}`,
      VMS_DATABASE_USERNAME: "f07_migration_login",
      VMS_FINANCE_CURSOR_SIGNING_SECRET:
        "live-preflight-cursor-signing-secret-at-least-32-bytes",
      VMS_OIDC_AUDIENCE: "vms-api",
      VMS_OIDC_ISSUER: "https://issuer.invalid",
      VMS_OIDC_JWKS_URI: "https://issuer.invalid/jwks",
    },
    stdio: ["ignore", "ignore", "ignore"],
  });
}

async function expectBlockedBeforeTraffic(port, child, deadline) {
  while (performance.now() < deadline) {
    if (child.exitCode !== null) return true;
    try {
      const response = await fetch(`http://127.0.0.1:${port}/actuator/health`, {
        signal: AbortSignal.timeout(1_000),
      });
      if (response.ok) {
        throw new Error("incompatible application accepted traffic");
      }
    } catch (error) {
      if (error?.message === "incompatible application accepted traffic") {
        throw error;
      }
    }
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 250));
  }
  throw new Error("incompatible application did not fail before traffic deadline");
}

function parseHistory(output) {
  return output.split("\n").filter(Boolean).map((line) => {
    const [version, checksum, success] = line.split("|");
    return { checksum: Number(checksum), success: success === "t", version };
  });
}

function evidenceRows(output) {
  const rows = output.split("\n").filter(Boolean);
  return { count: rows.length, sha256: sha256Bytes(rows.join("\n")) };
}

async function execute(args) {
  const started = performance.now();
  const rehearsalDeadline = performance.now() + 480_000;
  const baseline = await readJson(
    repoPath("docs/features/07-hardening-go-live/migration-baseline.json"),
  );
  const releaseCommit = gitMetadata().commit;
  if (
    args["base-ref"] !== baseline.commit ||
    args["release-commit"] !== releaseCommit ||
    baseline.schemaVersion !== 1
  ) {
    throw new Error("live rehearsal requires the checked-in protected base and current commit");
  }
  const staticReport = await staticPreflight(args["base-ref"], {
    trustedBaseRef: baseline.commit,
  });
  if (staticReport.result !== "PASS") {
    throw new Error(`static migration preflight failed: ${staticReport.findings.join("; ")}`);
  }

  const container = `f07-live-${process.pid}-${randomUUID().slice(0, 8)}`;
  const admin = "f07_live_admin";
  const database = "isolated_f07_upgrade";
  const emptyDatabase = "isolated_f07_empty";
  const incompatibleDatabase = "isolated_f07_incompatible";
  const adminPassword = `admin-${randomUUID()}`;
  const migrationPassword = `migration-${randomUUID()}`;
  const priorRoot = mkdtempSync(join(tmpdir(), "f07-prior-release-"));
  const priorWorktree = join(priorRoot, "checkout");
  let application;
  try {
    checked("start live-preflight database", [
      "run", "--detach", "--rm", "--name", container,
      "--publish", "127.0.0.1::5432",
      "--env", `POSTGRES_DB=${database}`,
      "--env", `POSTGRES_USER=${admin}`,
      "--env", `POSTGRES_PASSWORD=${adminPassword}`,
      image,
    ], rehearsalDeadline);
    await waitForDatabase(container, admin, database, rehearsalDeadline);
    checked("create migration login", [
      "exec", container, "psql", "--no-psqlrc", "--set", "ON_ERROR_STOP=1",
      "--username", admin, "--dbname", database, "--command",
      `CREATE ROLE f07_migration_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOREPLICATION NOBYPASSRLS PASSWORD '${migrationPassword}'`,
    ], rehearsalDeadline);
    checked("copy bootstrap", [
      "cp", repoPath("scripts/f07/bootstrap-database-roles.sql"),
      `${container}:/tmp/bootstrap.sql`,
    ], rehearsalDeadline);
    checked("execute bootstrap", [
      "exec", container, "psql", "--no-psqlrc", "--set", "ON_ERROR_STOP=1",
      "--set", "migration_login=f07_migration_login", "--username", admin,
      "--dbname", database, "--file", "/tmp/bootstrap.sql",
    ], rehearsalDeadline);
    checked("transfer database ownership", [
      "exec", container, "psql", "--no-psqlrc", "--set", "ON_ERROR_STOP=1",
      "--username", admin, "--dbname", database, "--command",
      `ALTER DATABASE ${database} OWNER TO f07_migration_login; ALTER SCHEMA public OWNER TO f07_migration_login`,
    ], rehearsalDeadline);
    checked("create empty-path database", [
      "exec", container, "psql", "--no-psqlrc", "--set", "ON_ERROR_STOP=1",
      "--username", admin, "--dbname", database, "--command",
      `CREATE DATABASE ${emptyDatabase} OWNER f07_migration_login`,
    ], rehearsalDeadline);
    const mapped = checked("resolve mapped PostgreSQL port", [
      "port", container, "5432/tcp",
    ], rehearsalDeadline);
    const databasePort = mapped.match(/:(\d+)\s*$/)?.[1];
    if (!databasePort) throw new Error("mapped PostgreSQL port is unavailable");

    checkedProcess(
      "materialize protected prior-release worktree",
      "git", ["worktree", "add", "--detach", priorWorktree, baseline.commit],
      rehearsalDeadline, 30_000,
    );
    const priorJar = buildApplication(priorWorktree, rehearsalDeadline);
    const currentJar = buildApplication(repoRoot, rehearsalDeadline);

    let appPort = await availablePort();
    application = startApplication(
      priorJar, appPort, databasePort, database, migrationPassword);
    await waitForHealth(appPort, application, rehearsalDeadline);
    await stopChild(application);
    application = null;

    checked("seed prior-release evidence", [
      "exec", container, "psql", "--no-psqlrc", "--set", "ON_ERROR_STOP=1",
      "--username", admin, "--dbname", database, "--command", `
        INSERT INTO f05_security_events(
          id, event_type, result, reason_code, actor_subject_hash, correlation_id
        ) VALUES (
          '70000000-0000-4000-8000-000000000001',
          'COMPATIBILITY_SENTINEL', 'DENIED', 'PRIOR_RELEASE_EVIDENCE',
          repeat('a', 64), '70000000-0000-4000-8000-000000000002'
        );
        INSERT INTO f05_audit_events(
          id, action, object_type, object_id, result, reason_code,
          authority_snapshot, actor_subject, correlation_id
        ) VALUES (
          '70000000-0000-4000-8000-000000000003',
          'COMPATIBILITY_SENTINEL', 'RELEASE',
          '70000000-0000-4000-8000-000000000004',
          'SUCCESS', 'PRIOR_RELEASE_EVIDENCE', '{"role":"release"}',
          'compatibility-rehearsal',
          '70000000-0000-4000-8000-000000000002'
        )`,
    ], rehearsalDeadline);
    const evidenceSql = `
      SELECT 'audit|' || id || '|' || correlation_id || '|' || action
      FROM f05_audit_events WHERE reason_code = 'PRIOR_RELEASE_EVIDENCE'
      UNION ALL
      SELECT 'security|' || id || '|' || correlation_id || '|' || event_type
      FROM f05_security_events WHERE reason_code = 'PRIOR_RELEASE_EVIDENCE'
      ORDER BY 1`;
    const readEvidence = (label) => evidenceRows(checked(label, [
      "exec", container, "psql", "--no-psqlrc", "--tuples-only", "--no-align",
      "--username", admin, "--dbname", database, "--command", evidenceSql,
    ], rehearsalDeadline));
    const evidenceBefore = readEvidence("read prior-release evidence");

    appPort = await availablePort();
    application = startApplication(
      currentJar, appPort, databasePort, database, migrationPassword);
    await waitForHealth(appPort, application, rehearsalDeadline);
    await stopChild(application);
    application = null;
    const evidenceAfter = readEvidence("read upgraded evidence");

    appPort = await availablePort();
    application = startApplication(
      priorJar, appPort, databasePort, database, migrationPassword);
    await waitForHealth(appPort, application, rehearsalDeadline);
    await stopChild(application);
    application = null;

    appPort = await availablePort();
    application = startApplication(
      currentJar, appPort, databasePort, emptyDatabase, migrationPassword);
    await waitForHealth(appPort, application, rehearsalDeadline);
    await stopChild(application);
    application = null;

    const historyOutput = checked("read live Flyway history", [
      "exec", container, "psql", "--no-psqlrc", "--tuples-only", "--no-align",
      "--field-separator", "|", "--username", admin, "--dbname", database,
      "--command",
      "SELECT version, checksum, success FROM flyway_schema_history ORDER BY installed_rank",
    ], rehearsalDeadline);
    const liveHistory = parseHistory(historyOutput);
    const sourceHistory = staticReport.migrations.map((migration) => ({
      checksum: migration.flywayChecksum,
      success: true,
      version: String(migration.version),
    }));
    const emptyHistory = parseHistory(checked("read empty-path Flyway history", [
      "exec", container, "psql", "--no-psqlrc", "--tuples-only", "--no-align",
      "--field-separator", "|", "--username", admin,
      "--dbname", emptyDatabase, "--command",
      "SELECT version, checksum, success FROM flyway_schema_history ORDER BY installed_rank",
    ], rehearsalDeadline));
    const schemaSql = `
      SELECT md5(string_agg(definition, E'\\n' ORDER BY definition))
      FROM (
        SELECT table_name || '|' || column_name || '|' || data_type || '|'
               || is_nullable || '|' || COALESCE(column_default, '') definition
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name <> 'flyway_schema_history'
        UNION ALL
        SELECT tablename || '|INDEX|' || indexdef
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND tablename <> 'flyway_schema_history'
      ) definitions`;
    const schemaHash = (target) => checked("fingerprint final schema", [
      "exec", container, "psql", "--no-psqlrc", "--tuples-only", "--no-align",
      "--username", admin, "--dbname", target, "--command", schemaSql,
    ], rehearsalDeadline);
    const upgradeSchemaSha256 = schemaHash(database);
    const emptySchemaSha256 = schemaHash(emptyDatabase);

    checked("clone incompatible database", [
      "exec", container, "psql", "--no-psqlrc", "--set", "ON_ERROR_STOP=1",
      "--username", admin, "--dbname", database, "--command",
      `CREATE DATABASE ${incompatibleDatabase} WITH TEMPLATE ${emptyDatabase} OWNER f07_migration_login`,
    ], rehearsalDeadline);
    checked("tamper incompatible migration checksum", [
      "exec", container, "psql", "--no-psqlrc", "--set", "ON_ERROR_STOP=1",
      "--username", admin, "--dbname", incompatibleDatabase, "--command",
      "UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE installed_rank = 1",
    ], rehearsalDeadline);
    appPort = await availablePort();
    application = startApplication(
      currentJar, appPort, databasePort, incompatibleDatabase,
      migrationPassword);
    const incompatibleBlocked = await expectBlockedBeforeTraffic(
      appPort, application,
      Math.min(rehearsalDeadline, performance.now() + 60_000));
    await stopChild(application);
    application = null;

    const sourceMatches =
      stableJson(liveHistory) === stableJson(sourceHistory);
    const convergence =
      stableJson(emptyHistory) === stableJson(liveHistory)
      && emptySchemaSha256 === upgradeSchemaSha256;
    const evidencePreserved =
      evidenceBefore.count === 2
      && stableJson(evidenceBefore) === stableJson(evidenceAfter);
    const report = {
      cases: [
        {
          durationMs: Math.round(performance.now() - started),
          id: "MIGRATION-LIVE-SOURCE-HISTORY",
          source: "docker:postgresql-flyway-history",
          status:
            sourceMatches
              ? "PASSED"
              : "FAILED",
        },
        {
          durationMs: Math.round(performance.now() - started),
          id: "MIGRATION-EMPTY-UPGRADE-CONVERGENCE",
          source: "docker:postgresql-schema-fingerprint",
          status: convergence ? "PASSED" : "FAILED",
        },
        {
          durationMs: Math.round(performance.now() - started),
          id: "MIGRATION-EVIDENCE-PRESERVATION",
          source: "docker:postgresql-prior-current-prior",
          status: evidencePreserved ? "PASSED" : "FAILED",
        },
        {
          durationMs: Math.round(performance.now() - started),
          id: "MIGRATION-INCOMPATIBLE-BLOCKED",
          source: "spring:flyway-validation-before-traffic",
          status: incompatibleBlocked ? "PASSED" : "FAILED",
        },
      ],
      database: {
        database,
        host: "127.0.0.1",
        protocol: "postgresql:",
      },
      kind: "migration-live-v1",
      liveHistory,
      liveHistorySha256: sha256Bytes(stableJson(liveHistory)),
      protectedBaseCommit: baseline.commit,
      releaseCommit,
      result:
        sourceMatches && convergence && evidencePreserved && incompatibleBlocked
          ? "PASS" : "FAIL",
      schemaVersion: 1,
      compatibility: {
        emptySchemaSha256,
        evidenceAfter,
        evidenceBefore,
        incompatibleBlocked,
        priorReleaseCommit: baseline.commit,
        priorReleaseRestartedAfterUpgrade: true,
        upgradeSchemaSha256,
      },
      sourceHistory,
      sourceHistorySha256: sha256Bytes(stableJson(sourceHistory)),
    };
    process.stdout.write(stableJson(report));
    process.exitCode = report.result === "PASS" ? 0 : 1;
  } finally {
    if (application) await stopChild(application);
    run("docker", ["rm", "--force", container], { timeoutMs: 15_000 });
    run("git", ["worktree", "remove", "--force", priorWorktree], {
      cwd: repoRoot,
      timeoutMs: 30_000,
    });
    rmSync(priorRoot, { force: true, recursive: true });
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.execute) {
    throw new Error("--execute is required for the live migration rehearsal");
  }
  await execute(args);
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    process.stderr.write(`F07 live migration rehearsal failed safely: ${safeError(error)}\n`);
    process.exitCode = 1;
  });
}
