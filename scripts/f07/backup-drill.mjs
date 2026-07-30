#!/usr/bin/env node

import { createHmac, randomUUID, timingSafeEqual } from "node:crypto";
import {
  lstat,
  mkdir,
  mkdtemp,
  open,
  readdir,
  readFile,
  realpath,
  rm,
  unlink,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, dirname, join, relative, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import {
  assertNewDirectory,
  assertNoSymlinkTraversal,
  commandExists,
  existingRepoPath,
  gitMetadata,
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

export function validateDistinctKeyMaterial(entries) {
  if (
    entries.some(
      (entry) =>
        typeof entry.secret !== "string" ||
        entry.secret.length < entry.minimumLength ||
        !/^[A-Za-z0-9._-]{3,128}$/.test(entry.id ?? ""),
    ) ||
    new Set(entries.map((entry) => entry.secret)).size !== entries.length ||
    new Set(entries.map((entry) => entry.id)).size !== entries.length
  ) {
    throw new Error("key secrets and non-secret version IDs must be pairwise distinct");
  }
  return true;
}

export function authenticateManifest(manifest, integrityKey) {
  if (typeof integrityKey !== "string" || integrityKey.length < 32) {
    throw new Error("backup integrity key must contain at least 32 characters");
  }
  const serialized = stableJson(manifest);
  return {
    algorithm: "HMAC-SHA256",
    backupId: manifest.backupId,
    manifestSha256: sha256Bytes(serialized),
    value: createHmac("sha256", integrityKey).update(serialized).digest("hex"),
  };
}

export function verifyAuthenticatedManifest(manifest, authentication, integrityKey) {
  const expected = authenticateManifest(manifest, integrityKey);
  if (
    authentication?.algorithm !== "HMAC-SHA256" ||
    authentication?.backupId !== manifest.backupId ||
    authentication?.manifestSha256 !== expected.manifestSha256 ||
    !/^[0-9a-f]{64}$/.test(authentication?.value ?? "")
  ) {
    throw new Error("backup manifest authentication metadata is invalid");
  }
  const expectedBuffer = Buffer.from(expected.value, "hex");
  const actualBuffer = Buffer.from(authentication.value, "hex");
  if (
    expectedBuffer.length !== actualBuffer.length ||
    !timingSafeEqual(expectedBuffer, actualBuffer)
  ) {
    throw new Error("backup manifest HMAC verification failed");
  }
}

export function validateTarMembers(names, verboseLines = []) {
  for (const name of names) {
    if (name === "./") {
      continue;
    }
    const normalized = name.replace(/^\.\//, "");
    if (
      !normalized ||
      normalized.startsWith("/") ||
      normalized.includes("\\") ||
      normalized.split("/").includes("..")
    ) {
      throw new Error(`unsafe object archive member: ${name}`);
    }
  }
  for (const line of verboseLines) {
    const type = line[0];
    if (!["-", "d"].includes(type)) {
      throw new Error("object archive links and special members are forbidden");
    }
  }
}

export function validateBackupFreshness(
  manifest,
  expectedCommit,
  maxAgeHours = 24,
  now = Date.now(),
) {
  if (
    !/^[0-9a-f]{40}$/.test(manifest.releaseCommit ?? "") ||
    expectedCommit !== manifest.releaseCommit
  ) {
    throw new Error("expected backup commit must exactly match the authenticated manifest");
  }
  const ageMs = now - Date.parse(manifest.createdAt);
  if (
    !manifest.backupId ||
    !Number.isFinite(ageMs) ||
    ageMs < 0 ||
    !Number.isFinite(maxAgeHours) ||
    maxAgeHours <= 0 ||
    maxAgeHours > 168 ||
    ageMs > maxAgeHours * 60 * 60 * 1000
  ) {
    throw new Error("backup is stale, future-dated, or missing authenticated identity");
  }
}

export async function claimRestoreAttempt(
  backupId,
  database,
  ledgerRoot = ".f07-evidence/restore-ledger",
  options = {},
) {
  const ledgerDirectory = repoPath(ledgerRoot, "restore replay ledger");
  await assertNoSymlinkTraversal(ledgerDirectory);
  await mkdir(ledgerDirectory, { recursive: true, mode: 0o700 });
  const prefix = sha256Bytes(`${backupId}\0${database}`);
  const lockPath = resolve(ledgerDirectory, `${prefix}.claim`);
  let claimHandle;
  try {
    claimHandle = await open(lockPath, "wx", 0o600);
    await claimHandle.writeFile(
      stableJson({
        backupId,
        claimedAt: new Date().toISOString(),
        database,
        processId: process.pid,
      }),
    );
    await claimHandle.sync();
  } catch (error) {
    if (error.code === "EEXIST") {
      throw new Error("another restore already holds the authenticated backup/target claim");
    }
    throw error;
  } finally {
    await claimHandle?.close();
  }
  try {
  const existing = (await readdir(ledgerDirectory))
    .filter((name) => name.startsWith(`${prefix}-`) && name.endsWith(".json"))
    .sort();
  const events = await Promise.all(
    existing.map((name) => readJson(resolve(ledgerDirectory, name))),
  );
  if (events.some((event) => event.status === "SUCCESS")) {
    throw new Error("authenticated backup was already restored successfully to this target");
  }
  if (events.length > 0 && options.authorizedRetry !== true) {
    throw new Error("restore retry requires explicit signed authorization");
  }
    return {
      ledgerPath: resolve(
        ledgerDirectory,
        `${prefix}-${Date.now()}-${randomUUID()}-RESERVED.json`,
      ),
      lockPath,
    };
  } catch (error) {
    await unlink(lockPath).catch(() => {});
    throw error;
  }
}

export async function releaseRestoreClaim(claim) {
  await unlink(claim.lockPath);
}

export async function assertRestoreNotReplayed(
  backupId,
  database,
  ledgerRoot = ".f07-evidence/restore-ledger",
  options = {},
) {
  return claimRestoreAttempt(backupId, database, ledgerRoot, options);
}

function ledgerDispositionPath(reservationPath, status) {
  return reservationPath.replace(
    /-RESERVED\.json$/,
    `-${status}-${randomUUID()}.json`,
  );
}

async function validateRetryAuthorization(path, context, signingKey) {
  if (!path) return false;
  const document = await readJson(
    await existingRepoPath(path, "restore retry authorization"),
  );
  const { signature, ...unsigned } = document;
  const expected = createHmac("sha256", signingKey)
    .update(stableJson(unsigned))
    .digest("hex");
  if (
    document.schemaVersion !== 1 ||
    document.kind !== "restore-retry-authorization-v1" ||
    document.backupId !== context.backupId ||
    document.targetDatabase !== context.targetDatabase ||
    !document.authorizedBy ||
    !document.reason ||
    !Number.isFinite(Date.parse(document.expiresAt)) ||
    new Date(document.expiresAt) <= new Date() ||
    signature?.algorithm !== "HMAC-SHA256" ||
    !/^[0-9a-f]{64}$/.test(signature?.value ?? "") ||
    expected !== signature.value
  ) {
    throw new Error("restore retry authorization is invalid or expired");
  }
  return true;
}

export function validatePostgresClientContainerName(name) {
  if (
    typeof name !== "string" ||
    !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$/.test(name)
  ) {
    throw new Error("PostgreSQL client container name is invalid");
  }
  return name;
}

function validatePostgresClientContainer(name) {
  validatePostgresClientContainerName(name);
  if (!commandExists("docker")) {
    throw new Error("docker is required for containerized PostgreSQL clients");
  }
  const inspected = run("docker", [
    "inspect",
    "--format",
    "{{.Config.Image}}|{{.State.Running}}",
    name,
  ]);
  const [image, running] = inspected.stdout.trim().split("|");
  if (
    inspected.status !== 0 ||
    running !== "true" ||
    !/^[^\s@]+@sha256:[0-9a-f]{64}$/.test(image ?? "")
  ) {
    throw new Error(
      "PostgreSQL client container must be running from a digest-pinned image",
    );
  }
  return { image, name };
}

function databaseConnection(rawUrl, suffix, clientContainer) {
  const normalized = rawUrl.replace(/^jdbc:/, "");
  const url = new URL(normalized);
  if (url.protocol !== "postgresql:" && url.protocol !== "postgres:") {
    throw new Error("database URL must use postgresql://");
  }
  if (!["127.0.0.1", "localhost", "::1", "[::1]"].includes(url.hostname)) {
    throw new Error("local DR tooling accepts loopback PostgreSQL only");
  }
  const database = url.pathname.replace(/^\//, "");
  if (!database || (suffix && !database.endsWith(suffix))) {
    throw new Error(`database name must end with ${suffix}`);
  }
  return {
    clientContainer,
    descriptor: redactDatabaseUrl(normalized),
    env: {
      ...process.env,
      PGDATABASE: database,
      PGHOST: url.hostname,
      PGPASSWORD: decodeURIComponent(url.password),
      PGPORT: url.port || "5432",
      PGUSER: decodeURIComponent(url.username),
    },
  };
}

function requireTools(tools) {
  const missing = tools.filter((tool) => !commandExists(tool));
  if (missing.length > 0) {
    throw new Error(`required local DR tools unavailable: ${missing.join(", ")}`);
  }
}

function containerDatabaseArgs(command, args, connection) {
  return [
    "exec",
    "--env",
    `PGDATABASE=${connection.env.PGDATABASE}`,
    "--env",
    "PGHOST=127.0.0.1",
    "--env",
    `PGPASSWORD=${connection.env.PGPASSWORD}`,
    "--env",
    "PGPORT=5432",
    "--env",
    `PGUSER=${connection.env.PGUSER}`,
    connection.clientContainer.name,
    command,
    ...args,
  ];
}

function runDatabase(command, args, connection) {
  const result = connection.clientContainer
    ? run("docker", containerDatabaseArgs(command, args, connection))
    : run(command, args, { env: connection.env });
  if (result.status !== 0) {
    throw new Error(`${command} failed; database details and credentials are not echoed`);
  }
  return result.stdout;
}

function requireDatabaseTools(tools, connection) {
  if (!connection.clientContainer) {
    requireTools(tools);
    return;
  }
  for (const tool of tools) {
    runDatabase(tool, ["--version"], connection);
  }
}

function copyFromDatabaseContainer(connection, containerPath, hostPath) {
  const copied = run("docker", [
    "cp",
    `${connection.clientContainer.name}:${containerPath}`,
    hostPath,
  ]);
  if (copied.status !== 0) {
    throw new Error("containerized PostgreSQL client output could not be copied");
  }
}

function copyToDatabaseContainer(connection, hostPath, containerPath) {
  const copied = run("docker", [
    "cp",
    hostPath,
    `${connection.clientContainer.name}:${containerPath}`,
  ]);
  if (copied.status !== 0) {
    throw new Error("restore input could not be copied to the PostgreSQL client");
  }
}

function removeDatabaseContainerFile(connection, containerPath) {
  if (connection.clientContainer) {
    run("docker", [
      "exec",
      connection.clientContainer.name,
      "rm",
      "-f",
      containerPath,
    ]);
  }
}

function dumpDatabase(connection, hostPath) {
  if (!connection.clientContainer) {
    runDatabase(
      "pg_dump",
      ["--format=custom", "--no-owner", "--no-acl", "--file", hostPath],
      connection,
    );
    return;
  }
  const containerPath = `/tmp/f07-backup-${randomUUID()}.dump`;
  try {
    runDatabase(
      "pg_dump",
      ["--format=custom", "--no-owner", "--no-acl", "--file", containerPath],
      connection,
    );
    copyFromDatabaseContainer(connection, containerPath, hostPath);
  } finally {
    removeDatabaseContainerFile(connection, containerPath);
  }
}

function restoreDatabase(connection, hostPath) {
  if (!connection.clientContainer) {
    runDatabase(
      "pg_restore",
      ["--exit-on-error", "--no-owner", "--no-acl", "--dbname",
        connection.descriptor.database, hostPath],
      connection,
    );
    return;
  }
  const containerPath = `/tmp/f07-restore-${randomUUID()}.dump`;
  try {
    copyToDatabaseContainer(connection, hostPath, containerPath);
    runDatabase(
      "pg_restore",
      ["--exit-on-error", "--no-owner", "--no-acl", "--dbname",
        connection.descriptor.database, containerPath],
      connection,
    );
  } finally {
    removeDatabaseContainerFile(connection, containerPath);
  }
}

const countSql = `
SELECT COALESCE(jsonb_object_agg(scoped_name, row_count ORDER BY scoped_name), '{}'::jsonb)
FROM (
  SELECT table_schema || '.' || table_name AS scoped_name,
    ((xpath('/row/count/text()', query_to_xml(
      format('SELECT count(*) AS count FROM %I.%I', table_schema, table_name),
      false, true, ''
    )))[1]::text)::bigint AS row_count
  FROM information_schema.tables
  WHERE table_type = 'BASE TABLE'
    AND table_schema NOT IN ('pg_catalog', 'information_schema')
) AS inventory;`;

const flywaySql = `
SELECT COALESCE(jsonb_agg(jsonb_build_object(
  'installedRank', installed_rank,
  'version', version,
  'description', description,
  'checksum', checksum,
  'success', success
) ORDER BY installed_rank), '[]'::jsonb)
FROM flyway_schema_history;`;

function queryJson(connection, sql) {
  const output = runDatabase(
    "psql",
    ["--no-psqlrc", "--set", "ON_ERROR_STOP=1", "--tuples-only", "--no-align", "--command", sql],
    connection,
  ).trim();
  return JSON.parse(output);
}

async function inventoryTree(root) {
  const canonicalRoot = await realpath(root);
  const files = [];
  async function visit(path) {
    const details = await lstat(path);
    if (details.isSymbolicLink()) {
      throw new Error(`object inventory rejects symbolic links: ${path}`);
    }
    if (details.isFile()) {
      files.push({
        path: relative(canonicalRoot, path),
        sha256: await sha256File(path),
        size: details.size,
      });
      return;
    }
    if (!details.isDirectory()) {
      throw new Error(`unsupported object-store entry: ${path}`);
    }
    for (const name of (await readdir(path)).sort()) {
      await visit(resolve(path, name));
    }
  }
  await visit(canonicalRoot);
  return files.sort((left, right) => left.path.localeCompare(right.path));
}

function encryptFile(input, output, passphrase) {
  const result = run(
    "openssl",
    [
      "enc",
      "-aes-256-cbc",
      "-pbkdf2",
      "-salt",
      "-in",
      input,
      "-out",
      output,
      "-pass",
      "env:F07_BACKUP_PASSPHRASE",
    ],
    { env: { ...process.env, F07_BACKUP_PASSPHRASE: passphrase } },
  );
  if (result.status !== 0) {
    throw new Error("backup encryption failed; no passphrase is logged");
  }
}

function decryptFile(input, output, passphrase) {
  const result = run(
    "openssl",
    [
      "enc",
      "-d",
      "-aes-256-cbc",
      "-pbkdf2",
      "-in",
      input,
      "-out",
      output,
      "-pass",
      "env:F07_BACKUP_PASSPHRASE",
    ],
    { env: { ...process.env, F07_BACKUP_PASSPHRASE: passphrase } },
  );
  if (result.status !== 0) {
    throw new Error("backup decryption failed; no passphrase is logged");
  }
}

export async function createPrivateRestoreTemporary(filename) {
  if (basename(filename) !== filename) {
    throw new Error("restore temporary filename must be a plain filename");
  }
  const root = await mkdtemp(join(tmpdir(), "f07-restore-"));
  const path = resolve(root, filename);
  try {
    const handle = await open(path, "wx", 0o600);
    await handle.close();
    return { path, root };
  } catch (error) {
    await rm(root, { force: true, recursive: true });
    throw error;
  }
}

async function backup(args) {
  if (!args.execute) {
    return {
      action: "BACKUP_PLAN",
      destructive: false,
      requiredEnvironment: [
        "F07_SOURCE_DATABASE_URL (loopback database ending _f07_source)",
        "--postgres-client-container <digest-pinned running PostgreSQL container> (optional when host clients are absent)",
        "F07_BACKUP_PASSPHRASE (at least 24 characters)",
        "F07_BACKUP_INTEGRITY_KEY (independent, at least 32 characters)",
        "F07_SOURCE_OBJECT_ROOT (optional explicit local content root)",
      ],
      result: "ACTION_REQUIRED",
    };
  }
  const clientContainer = args["postgres-client-container"]
    ? validatePostgresClientContainer(String(args["postgres-client-container"]))
    : null;
  requireTools(["openssl"]);
  const passphrase = process.env.F07_BACKUP_PASSPHRASE ?? "";
  const integrityKey = process.env.F07_BACKUP_INTEGRITY_KEY ?? "";
  const passphraseKeyId = process.env.F07_BACKUP_PASSPHRASE_ID ?? "";
  const integrityKeyId = process.env.F07_BACKUP_INTEGRITY_KEY_ID ?? "";
  if (passphrase.length < 24) {
    throw new Error("F07_BACKUP_PASSPHRASE must contain at least 24 characters");
  }
  if (integrityKey.length < 32 || integrityKey === passphrase) {
    throw new Error("independent F07_BACKUP_INTEGRITY_KEY must contain at least 32 characters");
  }
  if (
    !/^[A-Za-z0-9._-]{3,128}$/.test(passphraseKeyId) ||
    !/^[A-Za-z0-9._-]{3,128}$/.test(integrityKeyId) ||
    passphraseKeyId === integrityKeyId
  ) {
    throw new Error("distinct non-secret backup passphrase/integrity key IDs are required");
  }
  validateDistinctKeyMaterial([
    { id: passphraseKeyId, minimumLength: 24, secret: passphrase },
    { id: integrityKeyId, minimumLength: 32, secret: integrityKey },
  ]);
  if (!process.env.F07_SOURCE_DATABASE_URL) {
    throw new Error("F07_SOURCE_DATABASE_URL is required");
  }
  if (!args.output) {
    throw new Error("--output below .f07-evidence/backups is required");
  }
  const relativeOutput = String(args.output).replace(/\/+$/, "");
  if (!relativeOutput.startsWith(".f07-evidence/backups/")) {
    throw new Error("backup output must be below .f07-evidence/backups/");
  }
  const output = repoPath(relativeOutput, "backup output");
  await assertNoSymlinkTraversal(dirname(output));
  await mkdir(dirname(output), { recursive: true, mode: 0o700 });
  await assertNewDirectory(output);
  await mkdir(output, { mode: 0o700 });
  const source = databaseConnection(
    process.env.F07_SOURCE_DATABASE_URL,
    "_f07_source",
    clientContainer,
  );
  requireDatabaseTools(["pg_dump", "psql"], source);
  const dumpPlain = resolve(output, ".database.dump");
  const dumpEncrypted = resolve(output, "database.dump.enc");
  const objectPlain = resolve(output, ".objects.tar");
  const objectEncrypted = resolve(output, "objects.tar.enc");
  const started = Date.now();
  let objectInventory = [];
  try {
    dumpDatabase(source, dumpPlain);
    encryptFile(dumpPlain, dumpEncrypted, passphrase);

    if (process.env.F07_SOURCE_OBJECT_ROOT) {
      requireTools(["tar"]);
      await assertNoSymlinkTraversal(process.env.F07_SOURCE_OBJECT_ROOT);
      const objectRoot = await realpath(process.env.F07_SOURCE_OBJECT_ROOT);
      objectInventory = await inventoryTree(objectRoot);
      const archived = run("tar", ["-C", objectRoot, "-cf", objectPlain, "."]);
      if (archived.status !== 0) {
        throw new Error("object content archive failed");
      }
      encryptFile(objectPlain, objectEncrypted, passphrase);
    }
  } finally {
    await unlink(dumpPlain).catch(() => {});
    await unlink(objectPlain).catch(() => {});
  }

  const registry = await readJson(
    repoPath("docs/features/07-hardening-go-live/configuration-registry.json"),
  );
  const manifest = {
    artifacts: {
      database: {
        file: basename(dumpEncrypted),
        sha256: await sha256File(dumpEncrypted),
      },
      objects:
        objectInventory.length > 0
          ? {
              file: basename(objectEncrypted),
              inventory: objectInventory,
              sha256: await sha256File(objectEncrypted),
            }
          : {
              result: "ACTION_REQUIRED",
              reason: "F07_SOURCE_OBJECT_ROOT was not supplied",
            },
    },
    backupId: randomUUID(),
    configurationInventory: registry.entries.map((entry) => ({
      id: entry.id,
      state: entry.state,
    })),
    createdAt: new Date().toISOString(),
    database: source.descriptor,
    durationMs: Date.now() - started,
    flywayHistory: queryJson(source, flywaySql),
    keyReferences: {
      integrityKeyId,
      passphraseKeyId,
      version: 1,
    },
    releaseCommit: gitMetadata().commit,
    result: objectInventory.length > 0 ? "BACKUP_CREATED" : "ACTION_REQUIRED",
    rowCounts: queryJson(source, countSql),
    schemaVersion: 1,
    source: source.descriptor,
  };
  const manifestPath = resolve(output, "integrity-manifest.json");
  await writeJson(manifestPath, manifest);
  await writeJson(
    resolve(output, "integrity-manifest.auth.json"),
    authenticateManifest(manifest, integrityKey),
  );
  return manifest;
}

export const requiredRestoreAssertions = [
  "flywayValidate",
  "packageAndEvidenceRehash",
  "accessRevalidation",
  "queueCheckpointResume",
  "legalHoldIntegrity",
  "transactionBoundaryRecovery",
  "noOrphanedMetadata",
  "noDuplicateBusinessEffects",
  "providerStateExplicitlyStale",
];
export const requiredRestoreAssertionProofs = {
  flywayValidate: {
    command: "mvn -B -f backend/pom.xml -Dit.test=F07MigrationBootstrapIT verify",
    evidenceId: "F07-CI-DB-BOOTSTRAP",
    resultId: "F07-REL-003",
  },
  packageAndEvidenceRehash: {
    command: "node scripts/f07/release.mjs schema",
    evidenceId: "F07-CI-OPS",
    resultId: "F07-T029",
  },
  accessRevalidation: {
    command: "mvn -B -f backend/pom.xml verify",
    evidenceId: "F07-CI-MAVEN-VERIFY",
    resultId: "F07-IAM-006",
  },
  queueCheckpointResume: {
    command: "npm run e2e:migration:system",
    evidenceId: "F07-CI-MIGRATION-SYSTEM",
    resultId: "T-DR-001",
  },
  legalHoldIntegrity: {
    command: "mvn -B -f backend/pom.xml verify",
    evidenceId: "F07-CI-MAVEN-VERIFY",
    resultId: "F07-RET-002",
  },
  transactionBoundaryRecovery: {
    command: "npm run e2e:migration:system",
    evidenceId: "F07-CI-MIGRATION-SYSTEM",
    resultId: "F07-DR-003",
  },
  noOrphanedMetadata: {
    command: "npm run e2e:migration:system",
    evidenceId: "F07-CI-MIGRATION-SYSTEM",
    resultId: "F07-DR-003",
  },
  noDuplicateBusinessEffects: {
    command: "npm run e2e:migration:system",
    evidenceId: "F07-CI-MIGRATION-SYSTEM",
    resultId: "F07-DR-003",
  },
  providerStateExplicitlyStale: {
    command: "npm run e2e:migration:system",
    evidenceId: "F07-CI-MIGRATION-SYSTEM",
    resultId: "F07-DR-003",
  },
};

export async function validateLocalAssertions(path, context = {}) {
  if (!path) {
    return {
      missing: [...requiredRestoreAssertions],
      result: "ACTION_REQUIRED",
    };
  }
  const document = await readJson(
    await existingRepoPath(path, "restore assertion evidence"),
  );
  const missing = [];
  if (
    document.schemaVersion !== 1 ||
    document.releaseCommit !== context.releaseCommit ||
    !document.assertions ||
    typeof document.assertions !== "object"
  ) {
    return {
      missing: ["restore-assertion-document:invalid-metadata"],
      result: "FAIL",
    };
  }
  let sharedProvenance;
  for (const assertion of requiredRestoreAssertions) {
    const entry = document.assertions[assertion];
    const expected = requiredRestoreAssertionProofs[assertion];
    if (
      entry?.result !== "PASS" ||
      entry.command !== expected.command ||
      !Number.isFinite(entry.durationMs) ||
      !entry.evidence?.path ||
      !entry.evidence?.sha256
    ) {
      missing.push(assertion);
      continue;
    }
    try {
      const evidencePath = await existingRepoPath(
        entry.evidence.path,
        `${assertion} evidence`,
      );
      const actual = await sha256File(evidencePath);
      if (actual !== entry.evidence.sha256) {
        missing.push(`${assertion}:checksum-drift`);
        continue;
      }
      const proof = await readJson(evidencePath);
      if (
        proof.schemaVersion !== 1 ||
        proof.releaseCommit !== context.releaseCommit ||
        proof.runner?.name !== "scripts/f07/command-evidence.mjs" ||
        proof.runner?.schemaVersion !== 1 ||
        proof.record?.id !== expected.evidenceId ||
        proof.record?.kind !== "test" ||
        proof.record?.result !== "PASS" ||
        proof.record?.command !== expected.command ||
        proof.record?.environment !== "github-actions" ||
        proof.record?.durationMs !== entry.durationMs ||
        proof.commandOutput?.exitCode !== 0 ||
        !proof.verifiedResultIds?.includes(expected.resultId) ||
        !/^[0-9a-f]{64}$/.test(proof.commandOutput?.stdoutSha256 ?? "") ||
        !/^[0-9a-f]{64}$/.test(proof.commandOutput?.stderrSha256 ?? "")
      ) {
        missing.push(`${assertion}:invalid-structured-proof`);
        continue;
      }
      if (!sharedProvenance) {
        sharedProvenance = proof.provenance;
      } else if (stableJson(sharedProvenance) !== stableJson(proof.provenance)) {
        missing.push(`${assertion}:provenance-mismatch`);
      }
    } catch {
      missing.push(`${assertion}:evidence-unavailable`);
    }
  }
  if (missing.length === 0) {
    try {
      const provenancePath = await existingRepoPath(
        sharedProvenance.path,
        "restore assertion provenance",
      );
      if ((await sha256File(provenancePath)) !== sharedProvenance.sha256) {
        throw new Error("restore assertion provenance checksum drift");
      }
      const provenance = await readJson(provenancePath);
      if (
        provenance.schemaVersion !== 1 ||
        provenance.predicateType !== "https://slsa.dev/provenance/v1" ||
        provenance.commit !== context.releaseCommit ||
        provenance.worktreeDirty !== false
      ) {
        throw new Error("restore assertion provenance metadata is invalid");
      }
      const decisionTime = await createProvenance(provenance.expectedInputs, {
        expectedCommit: context.releaseCommit,
        requireClean: true,
      });
      if (
        stableJson(decisionTime.artifacts) !== stableJson(provenance.artifacts) ||
        stableJson(decisionTime.composeImages) !== stableJson(provenance.composeImages)
      ) {
        throw new Error("restore assertion provenance drift");
      }
    } catch {
      missing.push("restore-assertion-provenance:invalid");
    }
  }
  return {
    missing,
    result: missing.length === 0 ? "PASS" : "FAIL",
  };
}

async function restore(args) {
  if (!args.execute) {
    return {
      action: "RESTORE_PLAN",
      destructive: false,
      requiredEnvironment: [
        "F07_DRILL_DATABASE_URL (empty loopback database ending _f07_drill)",
        "--postgres-client-container <same digest-pinned running PostgreSQL container> (optional when host clients are absent)",
        "F07_CONFIRM_EMPTY_TARGET (exact target database name)",
        "F07_BACKUP_PASSPHRASE",
        "F07_BACKUP_INTEGRITY_KEY (independent, at least 32 characters)",
        "F07_DRILL_SIGNING_KEY",
        "F07_DRILL_OBJECT_ROOT (new path when object archive exists)",
      ],
      result: "ACTION_REQUIRED",
    };
  }
  const clientContainer = args["postgres-client-container"]
    ? validatePostgresClientContainer(String(args["postgres-client-container"]))
    : null;
  requireTools(["openssl"]);
  if (!args.input || !args.output) {
    throw new Error("--input backup directory and --output report path are required");
  }
  const lexicalInput = repoPath(args.input, "backup input");
  await assertNoSymlinkTraversal(lexicalInput);
  const input = await realpath(lexicalInput);
  const manifestPath = resolve(input, "integrity-manifest.json");
  await assertNoSymlinkTraversal(manifestPath);
  const authPath = resolve(input, "integrity-manifest.auth.json");
  await assertNoSymlinkTraversal(authPath);
  const manifest = await readJson(manifestPath);
  const authentication = await readJson(authPath);
  const integrityKey = process.env.F07_BACKUP_INTEGRITY_KEY ?? "";
  const passphrase = process.env.F07_BACKUP_PASSPHRASE ?? "";
  const signingKey = process.env.F07_DRILL_SIGNING_KEY ?? "";
  const signingKeyId = process.env.F07_DRILL_SIGNING_KEY_ID ?? "";
  if (
    integrityKey.length < 32 ||
    passphrase.length < 24 ||
    integrityKey === passphrase ||
    signingKey === integrityKey ||
    signingKey === passphrase
  ) {
    throw new Error("backup passphrase, integrity key and signing key must be pairwise distinct");
  }
  if (
    signingKey.length < 24 ||
    !/^[A-Za-z0-9._-]{3,128}$/.test(signingKeyId) ||
    manifest.keyReferences?.version !== 1 ||
    !/^[A-Za-z0-9._-]{3,128}$/.test(manifest.keyReferences?.passphraseKeyId ?? "") ||
    !/^[A-Za-z0-9._-]{3,128}$/.test(manifest.keyReferences?.integrityKeyId ?? "") ||
    new Set([
      signingKeyId,
      manifest.keyReferences.passphraseKeyId,
      manifest.keyReferences.integrityKeyId,
    ]).size !== 3
  ) {
    throw new Error("three distinct non-secret key IDs with version 1 are required");
  }
  validateDistinctKeyMaterial([
    {
      id: manifest.keyReferences.passphraseKeyId,
      minimumLength: 24,
      secret: passphrase,
    },
    {
      id: manifest.keyReferences.integrityKeyId,
      minimumLength: 32,
      secret: integrityKey,
    },
    { id: signingKeyId, minimumLength: 24, secret: signingKey },
  ]);
  verifyAuthenticatedManifest(manifest, authentication, integrityKey);
  const maxAgeHours = Number(args["max-age-hours"] ?? 24);
  validateBackupFreshness(
    manifest,
    args["expected-backup-commit"],
    maxAgeHours,
  );
  if (!process.env.F07_DRILL_DATABASE_URL) {
    throw new Error("F07_DRILL_DATABASE_URL is required");
  }
  const target = databaseConnection(
    process.env.F07_DRILL_DATABASE_URL,
    "_f07_drill",
    clientContainer,
  );
  requireDatabaseTools(["pg_restore", "psql"], target);
  if (process.env.F07_CONFIRM_EMPTY_TARGET !== target.descriptor.database) {
    throw new Error("F07_CONFIRM_EMPTY_TARGET must equal the isolated target database name");
  }
  const authorizedRetry = await validateRetryAuthorization(
    args["retry-authorization"],
    {
      backupId: manifest.backupId,
      targetDatabase: target.descriptor.database,
    },
    signingKey,
  );
  const restoreClaim = await claimRestoreAttempt(
    manifest.backupId,
    target.descriptor.database,
    ".f07-evidence/restore-ledger",
    { authorizedRetry },
  );
  const ledgerPath = restoreClaim.ledgerPath;
  let dispositionWritten = false;
  try {
  await writeJson(ledgerPath, {
    backupId: manifest.backupId,
    reservedAt: new Date().toISOString(),
    status: "RESERVED",
    target: target.descriptor,
  });
  const existingTableCount = Number(
    runDatabase(
      "psql",
      [
        "--no-psqlrc",
        "--set",
        "ON_ERROR_STOP=1",
        "--tuples-only",
        "--no-align",
        "--command",
        "SELECT count(*) FROM information_schema.tables WHERE table_schema NOT IN ('pg_catalog','information_schema');",
      ],
      target,
    ).trim(),
  );
  if (existingTableCount !== 0) {
    throw new Error("restore target is not empty; no destructive clean is performed");
  }
  if (
    passphrase.length < 24 ||
    signingKey.length < 24 ||
    signingKey === integrityKey
  ) {
    throw new Error("backup passphrase and drill signing key must each contain at least 24 characters");
  }
  const encryptedDump = resolve(input, manifest.artifacts.database.file);
  if (basename(manifest.artifacts.database.file) !== manifest.artifacts.database.file) {
    throw new Error("database backup manifest member must be a plain filename");
  }
  await assertNoSymlinkTraversal(encryptedDump);
  if ((await sha256File(encryptedDump)) !== manifest.artifacts.database.sha256) {
    throw new Error("encrypted database backup checksum mismatch");
  }
  const dumpTemporary = await createPrivateRestoreTemporary("database.dump");
  const temporaryDump = dumpTemporary.path;
  const started = Date.now();
  try {
    decryptFile(encryptedDump, temporaryDump, passphrase);
    restoreDatabase(target, temporaryDump);
  } finally {
    await rm(dumpTemporary.root, { force: true, recursive: true });
  }

  let objectResult = { result: "NOT_APPLICABLE" };
  if (manifest.artifacts.objects?.file) {
    requireTools(["tar"]);
    if (!process.env.F07_DRILL_OBJECT_ROOT) {
      throw new Error("F07_DRILL_OBJECT_ROOT is required for object content restoration");
    }
    const targetObjects = resolve(process.env.F07_DRILL_OBJECT_ROOT);
    await assertNoSymlinkTraversal(dirname(targetObjects));
    await mkdir(dirname(targetObjects), { recursive: true, mode: 0o700 });
    await assertNewDirectory(targetObjects);
    await mkdir(targetObjects, { mode: 0o700 });
    const encryptedObjects = resolve(input, manifest.artifacts.objects.file);
    if (basename(manifest.artifacts.objects.file) !== manifest.artifacts.objects.file) {
      throw new Error("object backup manifest member must be a plain filename");
    }
    await assertNoSymlinkTraversal(encryptedObjects);
    if ((await sha256File(encryptedObjects)) !== manifest.artifacts.objects.sha256) {
      throw new Error("encrypted object backup checksum mismatch");
    }
    const objectTemporary = await createPrivateRestoreTemporary("objects.tar");
    const temporaryObjects = objectTemporary.path;
    try {
      decryptFile(encryptedObjects, temporaryObjects, passphrase);
      const memberList = run("tar", ["-tf", temporaryObjects]);
      const verboseList = run("tar", ["-tvf", temporaryObjects]);
      if (memberList.status !== 0 || verboseList.status !== 0) {
        throw new Error("object archive cannot be safely enumerated");
      }
      validateTarMembers(
        memberList.stdout.split("\n").filter(Boolean),
        verboseList.stdout.split("\n").filter(Boolean),
      );
      const extracted = run("tar", ["-C", targetObjects, "-xf", temporaryObjects]);
      if (extracted.status !== 0) {
        throw new Error("object archive restore failed");
      }
    } finally {
      await rm(objectTemporary.root, { force: true, recursive: true });
    }
    const actualObjects = await inventoryTree(targetObjects);
    objectResult = {
      result:
        stableJson(actualObjects) === stableJson(manifest.artifacts.objects.inventory)
          ? "PASS"
          : "FAIL",
      restoredCount: actualObjects.length,
    };
  }

  const restoredCounts = queryJson(target, countSql);
  const restoredFlyway = queryJson(target, flywaySql);
  const discrepancies = [];
  if (stableJson(restoredCounts) !== stableJson(manifest.rowCounts)) {
    discrepancies.push("row counts differ from the source manifest");
  }
  if (stableJson(restoredFlyway) !== stableJson(manifest.flywayHistory)) {
    discrepancies.push("Flyway history differs from the source manifest");
  }
  if (objectResult.result === "FAIL") {
    discrepancies.push("object content inventory/checksums differ");
  }
  const localAssertions = await validateLocalAssertions(args.assertions, {
    releaseCommit: manifest.releaseCommit,
  });
  const unsignedReport = {
    backupId: manifest.backupId,
    backupManifestHmac: authentication.value,
    discrepancies,
    durationMs: Date.now() - started,
    immutableLineage: {
      flywayHistoryMatch: stableJson(restoredFlyway) === stableJson(manifest.flywayHistory),
      objectInventory: objectResult,
      rowCountsMatch: stableJson(restoredCounts) === stableJson(manifest.rowCounts),
    },
    localAssertions,
    recoveryBoundary: {
      localLogicalDrill:
        discrepancies.length === 0 && localAssertions.result === "PASS" ? "PASS" : "ACTION_REQUIRED",
      productionPitr: "ACTION_REQUIRED",
      reason:
        "local logical restore never proves provider PITR, regional failover, production RPO/RTO, or quarterly execution",
    },
    result:
      discrepancies.length > 0
        ? "FAIL"
        : localAssertions.result === "PASS"
          ? "PASS"
          : "ACTION_REQUIRED",
    schemaVersion: 1,
    target: target.descriptor,
  };
  const report = {
    ...unsignedReport,
    signature: {
      algorithm: "HMAC-SHA256",
      keyId: signingKeyId,
      value: createHmac("sha256", signingKey).update(stableJson(unsignedReport)).digest("hex"),
    },
  };
  const reportPath = repoPath(args.output, "restore report");
  await writeJson(reportPath, report);
  const disposition = report.result === "PASS" ? "SUCCESS" : "FAILED";
  await writeJson(ledgerDispositionPath(ledgerPath, disposition), {
    backupId: manifest.backupId,
    completedAt: new Date().toISOString(),
    reportSha256: await sha256File(reportPath),
    reservation: relative(repoRoot, ledgerPath),
    status: disposition,
    target: target.descriptor,
  });
  dispositionWritten = true;
  return report;
  } catch (error) {
    if (!dispositionWritten) {
      await writeJson(ledgerDispositionPath(ledgerPath, "FAILED"), {
        backupId: manifest.backupId,
        failedAt: new Date().toISOString(),
        reason: safeError(error),
        reservation: relative(repoRoot, ledgerPath),
        status: "FAILED",
        target: target.descriptor,
      }).catch(() => {});
    }
    throw error;
  } finally {
    await releaseRestoreClaim(restoreClaim);
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const command = args._[0] ?? "plan";
  let result;
  if (command === "backup") {
    result = await backup(args);
  } else if (command === "restore") {
    result = await restore(args);
  } else {
    result = {
      backup: await backup({}),
      restore: await restore({}),
    };
  }
  process.stdout.write(stableJson(result));
  if (args.execute) {
    process.exitCode =
      command === "backup"
        ? ["BACKUP_CREATED"].includes(result.result)
          ? 0
          : 1
        : result.result === "PASS"
          ? 0
          : 1;
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    process.stderr.write(`F07 backup/restore drill failed safely: ${safeError(error)}\n`);
    process.exitCode = 1;
  });
}
